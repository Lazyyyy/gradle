/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.integtests.tooling.r18;

import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.model.GradleBuild;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.HierarchicalElement;

import java.util.HashMap;
import java.util.Map;

public class MultiProjectAction implements BuildAction<Map<String, GradleProject>> {
    public Map<String, GradleProject> execute(BuildController controller) {
        GradleBuild gradleBuild = controller.getBuildModel();
        Map<String, GradleProject> projects = new HashMap<String, GradleProject>();
        for (HierarchicalElement project : gradleBuild.getProjects()) {
            projects.put(project.getName(), controller.getModel(project, GradleProject.class));
        }
        return projects;
    }
}
