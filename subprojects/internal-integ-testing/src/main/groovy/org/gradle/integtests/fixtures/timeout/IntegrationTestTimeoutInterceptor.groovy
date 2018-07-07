/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.fixtures.timeout

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import org.gradle.api.Action
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.executer.InProcessGradleExecuter
import org.gradle.integtests.fixtures.executer.IntegrationTestBuildContext
import org.gradle.internal.os.OperatingSystem
import org.spockframework.runtime.SpockAssertionError
import org.spockframework.runtime.SpockTimeoutError
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.extension.MethodInvocation
import org.spockframework.runtime.extension.builtin.TimeoutInterceptor
import org.spockframework.runtime.model.MethodInfo

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

class IntegrationTestTimeoutInterceptor extends TimeoutInterceptor {
    private static final Pattern UNIX_JAVA_COMMAND_PATTERN = ~/(?i)([^\s]+\/bin\/java)/
    private static final Pattern WINDOWS_JAVA_COMMAND_PATTERN = ~/(?i)^"(.*[\/\\]bin[\/\\]java\.exe)"/
    private static final Pattern WINDOWS_PID_PATTERN = ~/([0-9]+)\s*$/
    private static final Pattern UNIX_PID_PATTERN = ~/([0-9]+)/

    IntegrationTestTimeoutInterceptor(IntegrationTestTimeout timeout) {
        super(new TimeoutAdapter(timeout))
    }

    IntegrationTestTimeoutInterceptor(int timeoutSeconds) {
        super(new TimeoutAdapter(timeoutSeconds, TimeUnit.SECONDS))
    }

    @Override
    void intercept(final IMethodInvocation invocation) throws Throwable {
        try {
            super.intercept(invocation)
        } catch (SpockTimeoutError e) {
            String allThreadStackTraces = getAllStackTraces(invocation)
            throw new SpockAssertionError(allThreadStackTraces, e)
        } catch (Throwable t) {
            throw t
        }
    }

    void intercept(Action<Void> action) {
        MethodInfo methodInfo = new MethodInfo()
        methodInfo.setName('MockMethod')
        intercept(new MethodInvocation(null, null, null, null, null, methodInfo, null) {
            void proceed() throws Throwable {
                action.execute(null)
            }
        })
    }

    static boolean isInProcessExecuter(AbstractIntegrationSpec spec) {
        return spec.executer.gradleExecuter.class == InProcessGradleExecuter
    }

    static String getAllStackTraces(IMethodInvocation invocation) {
        try {
            Object instance = invocation.getInstance()
            if (instance instanceof AbstractIntegrationSpec && isInProcessExecuter(instance)) {
                return getAllStackTracesInCurrentJVM()
            } else {
                return getAllStackTracesByJstack()
            }
        } catch (Throwable e) {
            def stream = new ByteArrayOutputStream()
            e.printStackTrace(new PrintStream(stream))
            return "Error in attempt to fetch  stacktraces: ${stream.toString()}"
        }
    }

    @EqualsAndHashCode
    @ToString
    static class JavaProcessInfo {
        String pid
        String javaCommand

        String getJstackCommand() {
            assert javaCommand.endsWith("java") || javaCommand.endsWith("java.exe"): "Unknown java command： $javaCommand"

            Path javaPath = Paths.get(javaCommand)
            String jstackExe = OperatingSystem.current().getExecutableName('jstack')
            if (javaPath.parent.fileName.toString() == 'bin' && javaPath.parent.parent.fileName.toString() == 'jre') {
                return javaPath.resolve("../../../bin/$jstackExe").normalize().toString()
            } else {
                return javaPath.resolve("../../bin/$jstackExe").normalize().toString()
            }
        }

        String jstack() {
            def process = "${jstackCommand} ${pid}".execute()
            def stdout = new StringBuffer()
            def stderr = new StringBuffer()
            process.consumeProcessOutput(stdout, stderr)
            process.waitFor()
            return "Run ${jstackCommand} ${pid}:\n${stdout}\n---------\n${stderr}\n"
        }
    }

    static class StdoutAndPatterns {
        String stdout
        Pattern pidPattern
        Pattern javaCommandPattern

        StdoutAndPatterns(String stdout) {
            this.stdout = stdout
            if (OperatingSystem.current().isWindows()) {
                pidPattern = WINDOWS_PID_PATTERN
                javaCommandPattern = WINDOWS_JAVA_COMMAND_PATTERN
            } else {
                pidPattern = UNIX_PID_PATTERN
                javaCommandPattern = UNIX_JAVA_COMMAND_PATTERN
            }
        }

        List<JavaProcessInfo> getSuspiciousDaemons() {
            stdout.readLines().findAll(this.&isSuspiciousDaemon).collect(this.&extractProcessInfo)
        }

        private JavaProcessInfo extractProcessInfo(String line) {
            Matcher javaCommandMatcher = javaCommandPattern.matcher(line)
            Matcher pidMatcher = pidPattern.matcher(line)

            javaCommandMatcher.find()
            pidMatcher.find()
            return new JavaProcessInfo(pid: pidMatcher.group(1), javaCommand: javaCommandMatcher.group(1))
        }

        private boolean isSuspiciousDaemon(String line) {
            return !isTeamCityAgent(line) && javaCommandPattern.matcher(line).find() && pidPattern.matcher(line).find()
        }

        private boolean isTeamCityAgent(String line) {
            return line.contains('jetbrains.buildServer.agent.AgentMain')
        }
    }

    private static StdoutAndPatterns ps() {
        String command = OperatingSystem.current().isWindows() ? "wmic process get processid,commandline" : "ps x"
        Process process = command.execute()

        def stdout = new StringBuffer()
        def stderr = new StringBuffer()

        process.consumeProcessOutput(stdout, stderr)

        if (process.waitFor() == 0) {
            println("Command $command stdout: ${stdout}")
            println("Command $command stderr: ${stderr}")
            return new StdoutAndPatterns(stdout.toString())
        } else {
            def logFile = IntegrationTestBuildContext.INSTANCE.gradleUserHomeDir.file("error-${System.currentTimeMillis()}.log")
            logFile << "stdout:\n"
            logFile << stdout
            logFile << "stderr:\n"
            logFile << stderr

            throw new RuntimeException("Error when fetching processes, see log file ${logFile.absolutePath}")
        }
    }

    static String getAllStackTracesByJstack() {
        return ps().getSuspiciousDaemons().collect { it.jstack() }.join("\n")
    }

    static String getAllStackTracesInCurrentJVM() {
        Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces()
        StringBuilder sb = new StringBuilder()
        sb.append("Threads in current JVM:\n")
        sb.append("--------------------------\n")
        allStackTraces.each { Thread thread, StackTraceElement[] stackTraces ->
            sb.append("Thread ${thread.getId()}: ${thread.getName()}\n")
            sb.append("--------------------------\n")
            stackTraces.each {
                sb.append("${it}\n")
            }
            sb.append("--------------------------\n")
        }

        return sb.toString()
    }
}