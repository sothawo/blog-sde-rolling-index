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

import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.web.bind.annotation.*;

/**
 * @author Peter-Josef Meisch
 */
@RestController
@RequestMapping("/messages")
public class MessageController {

    private final MessageRepository repository;

    public MessageController(MessageRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public Message add(@RequestBody Message message) {
        return repository.save(message);
    }

    @GetMapping
    public SearchHits<Message> messages() {
        return repository.searchAllBy();
    }

    @GetMapping("/{text}")
    public SearchHits<Message> messages(@PathVariable("text") String text) {
        return repository.searchAllByMessage(text);
    }
}
