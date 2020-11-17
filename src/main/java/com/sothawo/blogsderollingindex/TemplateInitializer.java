/*
 Copyright 2020 the original author(s)
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
        http://www.apache.org/licenses/LICENSE-2.0
    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
 */
package com.sothawo.blogsderollingindex;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.PutTemplateRequest;
import org.springframework.stereotype.Component;

/**
 * @author Peter-Josef Meisch
 */
@Component
public class TemplateInitializer {

    private static final String TEMPLATE_NAME = "msg-template";
    private static final String TEMPLATE_PATTERN = "msg-*";

        private final ElasticsearchOperations operations;

    public TemplateInitializer(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @Autowired
    public void setup() {

        var indexOps = operations.indexOps(Message.class);

        if (!indexOps.existsTemplate(TEMPLATE_NAME)) {

            var mapping = indexOps.createMapping();

            var aliasActions = new AliasActions().add(
                    new AliasAction.Add(AliasActionParameters.builderForTemplate()
                            .withAliases(indexOps.getIndexCoordinates().getIndexNames())
                            .build())
            );

            var request = PutTemplateRequest.builder(TEMPLATE_NAME, TEMPLATE_PATTERN)
                    .withMappings(mapping)
                    .withAliasActions(aliasActions)
                    .build();

            indexOps.putTemplate(request);
        }
    }
}
