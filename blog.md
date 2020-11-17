With the release of version 4.1 Spring Data Elasticsearch now supports the index templates of Elasticsearch. Index templates allow the user to define settings, mappings and aliases for indices that are automatically created by Elasticsearch when documents are saved to a not yet existing index.

In this blog post I will show how index templates can be used in combination with Spring Data Repository customizations to implement a rolling index strategy where new indices will be created based on the date.

You should be familiar with the basic concept of Spring Data Repositories and the use of Spring Data Elasticsearch.

As the most popular use case for rolling indexes is storing log entries in Elasticsearch, we will do something similar. Our application will offer an HTTP endpoint where a client can POST  a message, this message will be stored in an index that is named _msg-HH-MM_ where the index name will contain the hour and the minute when the message was received. Normally that would be something containing the date, but to be able to see this working, we need some different naming scheme.

When the user issues a GET request with a search word, the application will search across all indices by using the alias name _msg_ which we will set up as an alias for all the _msg-*_ inidices.

## Basic setup

### The program

The source code for this example is [available on GitHub](https://github.com/sothawo/blog-sde-rolling-index). This project was set up using [start.spring.io](https://start.spring.io), selecting a Spring Boot 2.4.0 application with _web_ and _spring-data-elasticsearch_ support and Java version 15.

**Note**: I make use of Java 15 features like _var_ definition of variables, this is not necessary for Spring Data Elasticsearch, you still can use Java 8 if you need to.

### Elasticsearch

In order to run this example we need an Elasticsearch cluster, I use version 7.9.3 because that's the version that Spring Data Elasticsearch 4.1, the version Spring Boot pulls in, is built with. I have downloaded Elasticsearch and have it running on my machine, accessible at http://localhost:9200. Please adjust the setup in the application configuration at _src/main/resources/application.yml_ accordingly.

### Command line client

In order to access our program and to check what is stored in Elasticsearch I use [httpie](https://httpie.org). An alternative would be curl. 

## The different parts in the application

## The entity

The entity we use in this example looks like this:

```java
@Document(indexName = "msg", createIndex = false)
public class Message {
    @Id private String id;

    @Field(type = FieldType.Text)
    private String message;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime timestamp = LocalDateTime.now();

    // getter/setter omitted here for brevity
}
```

Please note the following points:

* The index name is set to _msg_, this will be the alias name that will point to all the different indices that will be created. Spring Data Repository methods will without adaptionuse this name. This is ok for reading, we will set up the writing part later.
* the _createIndex_ argument of the `@Document` annotation is set to **false**. We don't want the application to automatically create an index named _msg_ as Elasticsearch will automatically create the indices when documents are stored.
* The properties are explicitly annotated with their types, so that the correct index mapping can be stored in the index template and later automatically be applied to a new created index.  
 
### The index template

To initialize the index template, we use a Spring Component:

```java
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
```

This bean class has a method _setup()_ that is annotated with `@Autowired`. A method with this annotation will be executed once when the beans in the Spring ApplicationContext are all setup. So in the _setup()_ method we can be sure that the injected `ElasticsearchOperations` instance has been set.

To work with the index templates we need an implementation of the `IndexOperations` interface which we get from the `operations` object. We then check if the index template already exists, as this initialization should only be done once.

If the index template does not exist, we first create the index mapping with `indexOps.createMapping()`. As the `indexOps` was bound to the `Message` class when we created it, the annotations from the `Message` class are used to create the mapping.

The next step is to create an `AliasAction` that will add an alias to an index when it is created. The name for the alias is retrieved from the `Message` class with `indexOps.getIndexCoordinates().getIndexNames()`.

We then put the mapping and the alias action in a `PutTemplateRequest` together with a name for the template and the pattern when this template should be applied and send it off to Elasticsearch.

### The repository

The Spring Data Repository we use is pretty simple:

```java
public interface MessageRepository extends ElasticsearchRepository<Message, String> {

    SearchHits<Message> searchAllBy();

    SearchHits<Message> searchByMessage(String text);
}
```

It extends `ElasticsearchRepository` and defines one method to retrieve all messages and a second one to search for text in a message.

### The repository customization  

We now need to customize the repository as we want our own methods to be used when saving `Message` ojects to the index. In these methods we will set the correct index name. we do this by defining a new interface `CustomMessageRepository`. As we want to redefine methods that are already defined in the `CrudRepository` interface (which our `MessageRepository` already extends), it is important that our methods **have exactly the same signature as the methods from `CrudRepository`**. This is the reason we need to make this interface generic:

```java
public interface CustomMessageRepository<T> {

    <S extends T> S save(S entity);

    <S extends T> Iterable<S> saveAll(Iterable<S> entities);
}
```

We provide an implementation of this interface in the class `CustomMessageRepositoryImpl`. This must have the same name as the interface with the suffix **Impl**, so that Spring Data can pick up this implementation:

```java
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
```

We have an `ElasticsearchOperation` instance injected (no need to define this as `@Component`, Spring Data detects this by the class name and does the injection). The index name is provided by the `indexName()` method which uses the hour and minute to provide an index name of the pattern _msg-HH-MM_ using the current time. A real life scenario would probably use the date instead of the time, but as we want test this with different entities and not wait a whole day between inserting them, this should be fine for now.

In the implementations of our _save_ methods, we call the `ElasticsearchOperations`´s save method but provide our own index name, so that the one from the `@Document` annotation is **not** taken.

A last step we need to do is to have our `MessageRepository` implement this new repository as well:

```java
public interface MessageRepository extends ElasticsearchRepository<Message, String>, CustomMessageRepository<Message> {

    SearchHits<Message> searchAllBy();

    SearchHits<Message> searchAllByMessage(String text);
}
```

## oops, the controller

And of cours we need something to test this all, so here we hav a simple controller to store and retrieve messages:

```java
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
``` 

This is just a plain old Spring REST controller with nothing special.

## Let's see it in action

Now let's start up the program and check what we have (remember, I use [httpie](https:httpie.org) as a client)

In the beginning there are no indices:

```
$ http :9200/_cat/indices
HTTP/1.1 200 OK
content-length: 0
content-type: text/plain; charset=UTF-8
```
We check out the templates:

```
$ http :9200/_template/msg-template
HTTP/1.1 200 OK
content-encoding: gzip
content-length: 165
content-type: application/json; charset=UTF-8

{
    "msg-template": {
        "aliases": {
            "msg": {}
        },
        "index_patterns": [
            "msg-*"
        ],
        "mappings": {
            "properties": {
                "message": {
                    "type": "text"
                },
                "timestamp": {
                    "format": "date_hour_minute_second",
                    "type": "date"
                }
            }
        },
        "order": 0,
        "settings": {}
    }
}
```
The template definition with the mapping and alias definition is there. Now let's add an entry:

```
$ http post :8080/messages message="this is the first message"
HTTP/1.1 200
Connection: keep-alive
Content-Type: application/json
Date: Tue, 17 Nov 2020 21:10:59 GMT
Keep-Alive: timeout=60
Transfer-Encoding: chunked

{
    "id": "TwYL2HUBIlu2470f4r6Y",
    "message": "this is the first message",
    "timestamp": "2020-11-17T22:10:58.541117"
}
```
We see that this message was persisted at 22:10, what about the indices?
```
$ http :9200/_cat/indices
HTTP/1.1 200 OK
content-encoding: gzip
content-length: 83
content-type: text/plain; charset=UTF-8

yellow open msg-22-10 bFfnss5wR8CuLOmSfJPDDw 1 1 1 0 4.5kb 4.5kb
```
We have a new index named _msg-22-10_, let's check it's setup:
```
$ http :9200/msg-22-10
HTTP/1.1 200 OK
content-encoding: gzip
content-length: 326
content-type: application/json; charset=UTF-8

{
    "msg-22-10": {
        "aliases": {
            "msg": {}
        },
        "mappings": {
            "properties": {
                "_class": {
                    "fields": {
                        "keyword": {
                            "ignore_above": 256,
                            "type": "keyword"
                        }
                    },
                    "type": "text"
                },
                "message": {
                    "type": "text"
                },
                "timestamp": {
                    "format": "date_hour_minute_second",
                    "type": "date"
                }
            }
        },
        "settings": {
            "index": {
                "creation_date": "1605647458601",
                "number_of_replicas": "1",
                "number_of_shards": "1",
                "provided_name": "msg-22-10",
                "routing": {
                    "allocation": {
                        "include": {
                            "_tier_preference": "data_content"
                        }
                    }
                },
                "uuid": "bFfnss5wR8CuLOmSfJPDDw",
                "version": {
                    "created": "7100099"
                }
            }
        }
    }
}

```
Let's add another one:
```
$ http post :8080/messages message="this is the second message"                                           
HTTP/1.1 200
Connection: keep-alive
Content-Type: application/json
Date: Tue, 17 Nov 2020 21:13:52 GMT
Keep-Alive: timeout=60
Transfer-Encoding: chunked

{
    "id": "UAYO2HUBIlu2470fiL7G",
    "message": "this is the second message",
    "timestamp": "2020-11-17T22:13:52.336695"
}


$ http :9200/_cat/indices
HTTP/1.1 200 OK
content-encoding: gzip
content-length: 112
content-type: text/plain; charset=UTF-8

yellow open msg-22-13 gvs12CQvTOmdvqsQz7k6yw 1 1 1 0 4.5kb 4.5kb
yellow open msg-22-10 bFfnss5wR8CuLOmSfJPDDw 1 1 1 0 4.5kb 4.5kb
```

So we have two indices now. Now let's get all the entries from our application:
```
$ http :8080/messages
HTTP/1.1 200
Connection: keep-alive
Content-Type: application/json
Date: Tue, 17 Nov 2020 21:15:57 GMT
Keep-Alive: timeout=60
Transfer-Encoding: chunked

{
    "aggregations": null,
    "empty": false,
    "maxScore": 1.0,
    "scrollId": null,
    "searchHits": [
        {
            "content": {
                "id": "TwYL2HUBIlu2470f4r6Y",
                "message": "this is the first message",
                "timestamp": "2020-11-17T22:10:58"
            },
            "highlightFields": {},
            "id": "TwYL2HUBIlu2470f4r6Y",
            "index": "msg-22-10",
            "innerHits": {},
            "nestedMetaData": null,
            "score": 1.0,
            "sortValues": []
        },
        {
            "content": {
                "id": "UAYO2HUBIlu2470fiL7G",
                "message": "this is the second message",
                "timestamp": "2020-11-17T22:13:52"
            },
            "highlightFields": {},
            "id": "UAYO2HUBIlu2470fiL7G",
            "index": "msg-22-13",
            "innerHits": {},
            "nestedMetaData": null,
            "score": 1.0,
            "sortValues": []
        }
    ],
    "totalHits": 2,
    "totalHitsRelation": "EQUAL_TO"
}
```
 
 We get both entries. As we are returning `SearchHits<Message>` we also get the information in which index each result was found; this is important if you migght want to edit one of these entries and store it again in it's original index.
 
 # Let's sum it up.
 
 We have defined and stored an index template that allows us to specify mappings and aliases for autmatically created indices. We have set up our applicaion to read from the alias and to write to a dynamically created index name and so have implemented a rolling index pattern for our Elasticsearch storage all from within Spring Data Elasticsearch.
 
 I hope you enjoyed this example. 
