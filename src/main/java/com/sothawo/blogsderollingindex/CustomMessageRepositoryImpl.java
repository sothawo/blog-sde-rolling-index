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

import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * @author Peter-Josef Meisch
 */
public class CustomMessageRepositoryImpl implements CustomMessageRepository<Message> {

    final private ElasticsearchOperations operations;

    public CustomMessageRepositoryImpl(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @Override
    public <S extends Message> S save(S entity) {
        return operations.save(entity, indexName());
    }

    @Override
    public <S extends Message> Iterable<S> saveAll(Iterable<S> entities) {
        return operations.save(entities, indexName());
    }

    public IndexCoordinates indexName() {
        var indexName = "msg-" +
                LocalTime.now().truncatedTo(ChronoUnit.MINUTES).toString().replace(':', '-');
        return IndexCoordinates.of(indexName);
    }
}
