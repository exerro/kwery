package me.exerro.kwery

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.exerro.observables.ObservableConnection
import kotlin.reflect.KClass

// TODO: work out how this would work over a network
//       NTS: I think just have a query handler that sends the query over the
//            network and emits changed upon a specific network event
// TODO: deadlock detection/reentrancy/cycling

/**
 * A query engine wraps a [QueryGraph] and facilitates re-evaluating queries
 * when necessary. As an implementor of [QueryContext], it exposes an [evaluate]
 * method to return the result of a query. This will use information from its
 * embedded query graph to determine what needs to be re-evaluated so that an
 * up-to-date value is always returned. Evaluation of a specific query is
 * delegated to a query handler, provided by a [Builder].
 *
 * Additionally, this class accounts for changing query handlers, for example
 * file content readers with FS watchers. When a query handler changes, the
 * query graph is invalidated and potentially sparsely re-evaluated.
 */
class QueryEngine private constructor(
    graph: MutableQueryGraph,
    handlers: Map<KClass<out Query<*>>, QueryHandler<*, *>>,
): QueryContext {
    /**
     * Thrown when there are two or more handlers registered for a particular
     * class of query.
     */
    @Suppress("CanBeParameter", "MemberVisibilityCanBePrivate")
    class MultipleHandlersException(
        val queryClass: KClass<out Query<*>>,
    ): RuntimeException("Multiple handlers for query type: ${queryClass.qualifiedName}")

    /**
     * Thrown when trying to evaluate a query which does not have a
     * corresponding handler.
     */
    @Suppress("CanBeParameter", "MemberVisibilityCanBePrivate")
    class QueryNotHandledException(
        val query: Query<*>
    ): RuntimeException("Query not handled: $query")

    /** Builder for [QueryEngine]s. Add query handlers then [build]. */
    class Builder {
        fun build(): QueryEngine {
            return QueryEngine(graph, handlers)
        }

        fun <Q: Query<T>, T> addQueryHandler(
            queryClass: KClass<Q>,
            handler: QueryHandler<Q, T>,
        ): Builder {
            if (queryClass in handlers)
                throw MultipleHandlersException(queryClass)

            handlers[queryClass] = handler
            return this
        }

        inline fun <reified Q: Query<T>, reified T> addQueryHandler(
            handler: QueryHandler<Q, T>,
        ) = addQueryHandler(
            queryClass = Q::class,
            handler = handler,
        )

        fun <Q: Query<T>, T> addQueryHandler(
            queryClass: KClass<Q>,
            provider: QueryHandlerProvider,
        ) = addQueryHandler(
            queryClass = queryClass,
            handler = provider.provideHandler(queryClass)
        )

        inline fun <reified Q: Query<T>, reified T> addQueryHandler(
            provider: QueryHandlerProvider,
        ) = addQueryHandler(
            queryClass = Q::class,
            provider = provider,
        )

        /**
         * Set the underlying graph to be used by the engine from the beginning.
         * The provided graph will be cloned and therefore not mutated.
         *
         * One use case would be to load a serialized graph from disk.
         */
        fun setGraph(graph: QueryGraph): Builder {
            this.graph = graph.clone()
            return this
        }

        private var graph = MutableQueryGraph()
        private val handlers = mutableMapOf<KClass<out Query<*>>, QueryHandler<*, *>>()
    }

    val graph: QueryGraph = graph

    context (CoroutineScope)
    override suspend fun <T> evaluate(query: Query<T>): T {
        deferredMutex.lock()

        return when (val d = deferred[query]) {
            null -> {
                val d = async { evaluateAsync(query) }

                deferred[query] = d
                deferredMutex.unlock()

                return d.await()
            }
            else -> {
                deferredMutex.unlock()
                @Suppress("UNCHECKED_CAST")
                d.await() as T
            }
        }
    }

    context (CoroutineScope)
    private suspend fun <T> evaluateAsync(query: Query<T>): T {
        evaluateDependencies(query)

        if (mutableGraph.validity(query) == QueryGraph.Validity.VALID) {
            deferredMutex.withLock { deferred.remove(query) }
            return (mutableGraph[query] as Result<T>).getOrThrow()
        }

        val dependencies = mutableSetOf<Query<*>>()

        try {
            @Suppress("UNCHECKED_CAST")
            val handler = (handlerClassLookup[query::class]
                ?: throw QueryNotHandledException(query))
                as QueryHandler<Query<Any?>, Any?>
            val engine = this@QueryEngine
            val ctx = object : QueryContext {
                context(CoroutineScope)
                override suspend fun <T> evaluate(query: Query<T>): T {
                    dependencies += query
                    return engine.evaluate(query)
                }
            }
            return with(ctx) {
                val value = handler.handle(query)
                mutableGraph.put(query, Result.success(value), dependencies)
                deferredMutex.withLock { deferred.remove(query) }
                @Suppress("UNCHECKED_CAST")
                value as T
            }
        }
        catch (e: Throwable) {
            mutableGraph.put(query, Result.failure(e), dependencies)
            deferredMutex.withLock { deferred.remove(query) }
            throw e
        }
    }

    context (CoroutineScope)
    private suspend fun evaluateDependencies(query: Query<*>) {
        // If we're weakly invalid, evaluate dependencies. Most will be cached,
        // and if any needs to be re-evaluated it'll mark this as strongly
        // invalid.
        // At the end, we fix our validity which will likely leave us as
        // strongly invalid (a dependency was re-evaluated) or as valid (all our
        // dependencies are evaluated, valid, and unchanged).
        // Note, if we're strongly invalid, we'll be re-evaluating this which
        // will in turn re-evaluate dependencies, so we don't need to do
        // anything.
        if (mutableGraph.validity(query) == QueryGraph.Validity.WEAKLY_INVALID) {
            for (dependency in mutableGraph.dependencies(query)) {
                try { evaluate(dependency) }
                catch (e: Throwable) { /* do nothing */ }
                if (mutableGraph.validity(query) == QueryGraph.Validity.STRONGLY_INVALID)
                    break
            }
            mutableGraph.validateWeakQuery(query)
        }
    }

    context (CoroutineScope)
    private suspend fun <T> executeQuery(query: Query<T>): Result<T> {
        @Suppress("UNCHECKED_CAST")
        val handler = (handlerClassLookup[query::class]
            ?: throw QueryNotHandledException(query))
            as QueryHandler<Query<Any?>, Any?>
        val dependencies = mutableSetOf<Query<*>>()
        val engine = this@QueryEngine
        val ctx = object : QueryContext {
            context(CoroutineScope)
            override suspend fun <T> evaluate(query: Query<T>): T {
                dependencies += query
                return engine.evaluate(query)
            }
        }

        return try {
            Result.success(with(ctx) {
                val value = handler.handle(query)
                mutableGraph.put(query, Result.success(value), dependencies)
                @Suppress("UNCHECKED_CAST")
                value as T
            })
        }
        catch (e: Throwable) {
            mutableGraph.put(query, Result.failure(e), dependencies)
            Result.failure(e)
        }
    }

    private val mutableGraph = graph
    private val deferredMutex = Mutex()
    private val handlerClassLookup = handlers
    private val deferred = mutableMapOf<Query<*>, Deferred<Any?>>()
    private val connections: ObservableConnection
    init {
        val innerConnections = handlers.values
            .filterIsInstance<ObservableQueryHandler<*, *>>()
            .map { queryHandler ->
                queryHandler.changed.connect { query ->
                    graph.invalidate(query)
                }
            }
        connections = ObservableConnection.join(innerConnections)
    }
}
