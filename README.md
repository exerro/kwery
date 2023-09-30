# kwery

Kwery is a general purpose query engine primarily intended for compilers.

* Queries are evaluated by a `QueryEngine`, which delegates the evaluation to
  a `QueryHandler` for the specific query type.
* Queries return a dependent type, i.e. a query of type `Query<T>` will evaluate
  to `T`.
* Queries can depend on other queries, which are evaluated during the execution
  of the query as needed.
* Queries are cached by default and are lazily re-evaluated when their
  dependencies change, upon being evaluated themselves.
* Queries are evaluated as coroutines and can suspend arbitrarily.
* Query graphs can be safely and sparsely serialized and deserialized, allowing
  persistent caching of query results.
* Observable query handlers can be used to notify when queries change, in turn
  invalidating (indirect) dependent queries. When a query is invalidated due to
  a dependency changing, it will retain its cached value, only re-evaluating
  when the parent value actually changes.

## Getting started

### Installation

#### Gradle

```groovy
implementation 'me.exerro.kwery:kwery:0.1.0'
```

#### Gradle (kotlin dsl)

```kotlin
implementation("me.exerro.kwery:kwery:0.1.0")
```

### Usage

```kotlin
val engine = QueryEngine.Builder()
    .addQueryHandler(MyQueryHandler())
    .build()

launch {
    val result = engine.evaluate(MyQuery())
    println(result)
}
```

#### Serialization

```kotlin
val serializer = QueryGraphSerializer()
    .addSerializer<MyQuery>()
val jsonFormat = Json { serializersModule = serializer.serializersModule() }
val stringDump = serializer.dumpToString(engine.graph, jsonFormat)
val engineCopy = QueryEngine.Builder()
    .addQueryHandler(MyQueryHandler())
    .setGraph(serializer.loadString(stringDump, jsonFormat))
    .build()
```

### Known issues

* Queries which are invalidated during evaluation will not restart their
  evaluation and will cache the now out-of-date result upon completion.
