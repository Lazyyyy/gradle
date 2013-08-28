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

package org.gradle.tooling.internal.consumer.connection;

import org.gradle.tooling.BuildController;
import org.gradle.tooling.UnknownModelException;
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter;
import org.gradle.tooling.internal.consumer.versioning.ModelMapping;
import org.gradle.tooling.internal.protocol.BuildResult;
import org.gradle.tooling.internal.protocol.InternalBuildController;
import org.gradle.tooling.internal.protocol.InternalUnsupportedModelException;
import org.gradle.tooling.internal.protocol.ModelIdentifier;
import org.gradle.tooling.model.Element;
import org.gradle.tooling.model.GradleBuild;
import org.gradle.tooling.model.internal.Exceptions;

class BuildControllerAdapter implements BuildController {
    private final InternalBuildController buildController;
    private final ProtocolToModelAdapter adapter;
    private final ModelMapping modelMapping;

    public BuildControllerAdapter(ProtocolToModelAdapter adapter, InternalBuildController buildController, ModelMapping modelMapping) {
        this.adapter = adapter;
        this.buildController = buildController;
        this.modelMapping = modelMapping;
    }

    public <T> T getModel(Class<T> modelType) throws UnknownModelException {
        ModelIdentifier modelIdentifier = modelMapping.getModelIdentifierFromModelType(modelType);

        BuildResult<?> result;
        try {
            result = buildController.getModel(modelIdentifier);
        } catch (InternalUnsupportedModelException e) {
            throw Exceptions.unknownModel(modelType, e);
        }

        return adapter.adapt(modelType, result.getModel());
    }

    public GradleBuild getBuildModel() {
        return getModel(GradleBuild.class);
    }

    public <T> T getModel(Element target, Class<T> modelType) throws UnknownModelException {
        throw new UnsupportedOperationException();
    }
}
