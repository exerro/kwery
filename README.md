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

### Why queries?

#### Dependency tracking and caching

Utilising queries makes it possible and fairly straightforward to track
dependencies between queries. Tracking dependencies allows a highly efficient
evaluation strategy that only re-evaluates changed values when needed.

#### Intelligent invalidation

Queries are minimally invalidated when dependencies change. For example, if a
file were to change trivially (e.g. whitespace), further queries utilising the
AST would not be invalidated, drastically reducing the number of re-evaluations.

#### Pull-driven model

Using a query engine like this allows you to think in terms of units of data
that are required, rather than specifically how or when to get that data. It's
simple to compose simple, focused handlers for queries and forget about the
underlying complexity of dependency tracking, caching, and evaluation.

#### De-coupling

An alternative world would contain a set of functions calling each other
directly. To make a duplicate of one of the terminal functions which could be
selected at runtime, either a duplicate of the entire call graph would be
required, or contextual information would be needed to select the correct
function.

Instead, we keep that information separate in the query engine, keeping the
implementations fully separated from the topology and configuration of the
graph.

### Future work

* Queries which are invalidated during evaluation will not restart their
  evaluation and will cache the now out-of-date result upon completion.
* Things should be able to subscribe to values on a query engine to be notified
  when its validation changes and when it should be re-evaluated.
* The graph should be pruned (accounting for subscriptions) to avoid the current
  approach of growing the graph indefinitely.
