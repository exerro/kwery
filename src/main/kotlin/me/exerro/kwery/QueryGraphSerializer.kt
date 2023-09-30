package me.exerro.kwery

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.reflect.KClass

/**
 * Used to serialize and deserialize [QueryGraph] instances.
 *
 * @see dump
 * @see load
 */
class QueryGraphSerializer private constructor(
    private val querySerializers: Map<KClass<out Query<*>>, KSerializer<Query<*>>>,
    private val valueSerializers: Map<KClass<out Query<*>>, KSerializer<Any?>>,
) {
    constructor(): this(mapOf(), mapOf())

    /**
     * Result of dumping a query graph containing a list of dump values. The
     * dump values contain an ordered sequence of query/value pairs encoded as
     * type [T] with some format.
     */
    @Serializable
    data class Dump<T>(
        val orderedValues: List<DumpValue<T>>,
    )

    /**
     * A single query/value pair in a dump representing a node in a query graph.
     */
    @Serializable
    data class DumpValue<T>(
        val serializedQuery: T,
        val serializedValue: T,
        val validity: QueryGraph.Validity,
        val localDependencies: Set<Int>,
        val transientDependencies: Set<T>,
    )

    fun <Q: Query<*>> addQuerySerializer(
        klass: KClass<Q>,
        querySerializer: KSerializer<Q>
    ): QueryGraphSerializer {
        if (klass in querySerializers)
            throw IllegalArgumentException("Query serializer already registered for $klass")

        @Suppress("UNCHECKED_CAST")
        val projectedQuerySerializer = querySerializer as KSerializer<Query<Any?>>
        return QueryGraphSerializer(
            querySerializers = querySerializers + mapOf(klass to projectedQuerySerializer),
            valueSerializers = valueSerializers)
    }

    inline fun <reified Q: Query<*>> addQuerySerializer() = addQuerySerializer(
        klass = Q::class,
        querySerializer = serializer<Q>(),
    )

    fun <Q: Query<T>, T> addSerializer(
        klass: KClass<Q>,
        querySerializer: KSerializer<Q>,
        valueSerializer: KSerializer<T>,
    ): QueryGraphSerializer {
        if (klass in querySerializers)
            throw IllegalArgumentException("Query serializer already registered for $klass")

        @Suppress("UNCHECKED_CAST")
        val projectedQuerySerializer = querySerializer as KSerializer<Query<Any?>>
        @Suppress("UNCHECKED_CAST")
        val projectedValueSerializer = valueSerializer as KSerializer<Any?>
        return QueryGraphSerializer(
            querySerializers = querySerializers + mapOf(klass to projectedQuerySerializer),
            valueSerializers = valueSerializers + mapOf(klass to projectedValueSerializer))
    }

    inline fun <reified Q: Query<T>, reified T> addSerializer() = addSerializer(
        klass = Q::class,
        querySerializer = serializer<Q>(),
        valueSerializer = serializer<T>(),
    )

    fun serializersModule() = SerializersModule {
        polymorphic(Query::class) {
            querySerializers.forEach { (klass, serializer) ->
                @Suppress("UNCHECKED_CAST")
                subclass(klass as KClass<Query<*>>, serializer)
            }
        }
    }

    fun loadString(dump: Dump<String>, format: StringFormat) =
        loadStringInto(MutableQueryGraph(), dump, format)

    fun loadByteArray(dump: Dump<ByteArray>, format: BinaryFormat) =
        loadByteArrayInto(MutableQueryGraph(), dump, format)

    fun loadHex(dump: Dump<String>, format: BinaryFormat) =
        loadHexInto(MutableQueryGraph(), dump, format)

    fun <T> load(dump: Dump<T>, loadQuery: (KSerializer<Query<*>>, T) -> Query<*>, loadValue: (KSerializer<Any?>, T) -> Any?) =
        loadInto(MutableQueryGraph(), dump, loadQuery, loadValue)

    fun loadStringInto(graph: MutableQueryGraph, dump: Dump<String>, format: StringFormat) = loadInto(
        graph = graph,
        dump = dump,
        loadQuery = { s, v -> format.decodeFromString(s, v) },
        loadValue = { s, v -> format.decodeFromString(s, v) },
    )

    fun loadByteArrayInto(graph: MutableQueryGraph, dump: Dump<ByteArray>, format: BinaryFormat) = loadInto(
        graph = graph,
        dump = dump,
        loadQuery = { s, v -> format.decodeFromByteArray(s, v) },
        loadValue = { s, v -> format.decodeFromByteArray(s, v) },
    )

    fun loadHexInto(graph: MutableQueryGraph, dump: Dump<String>, format: BinaryFormat) = loadInto(
        graph = graph,
        dump = dump,
        loadQuery = { s, v -> format.decodeFromHexString(s, v) },
        loadValue = { s, v -> format.decodeFromHexString(s, v) },
    )

    fun <T> loadInto(
        graph: MutableQueryGraph,
        dump: Dump<T>,
        loadQuery: (KSerializer<Query<*>>, T) -> Query<*>,
        loadValue: (KSerializer<Any?>, T) -> Any?,
    ): MutableQueryGraph {
        val deserializedQueries = mutableMapOf<Int, Query<*>>()

        for ((index, dumpValue) in dump.orderedValues.withIndex()) {
            val query = loadQuery(querySerializer, dumpValue.serializedQuery)
            val value = loadValue(valueSerializers[query::class] ?: continue, dumpValue.serializedValue)

            deserializedQueries[index] = query

            var validity = dumpValue.validity
            val dependencies = dumpValue.localDependencies.map {
                deserializedQueries[it] ?: run {
                    validity = QueryGraph.Validity.STRONGLY_INVALID
                    null
                }
            } + dumpValue.transientDependencies.map {
                loadQuery(querySerializer, it)
            }

            graph.put(
                query = query,
                value = Result.success(value),
                dependencies = dependencies.filterNotNull().toSet(),
                validity = validity,
            )
        }

        return graph
    }

    fun dumpToString(graph: QueryGraph, format: StringFormat) = dump(
        graph = graph,
        dumpQuery = { s, v -> format.encodeToString(s, v) },
        dumpValue = { s, v -> format.encodeToString(s, v) },
    )

    fun dumpToByteArray(graph: QueryGraph, format: BinaryFormat) = dump(
        graph = graph,
        dumpQuery = { s, v -> format.encodeToByteArray(s, v) },
        dumpValue = { s, v -> format.encodeToByteArray(s, v) },
    )

    fun dumpToHex(graph: QueryGraph, format: BinaryFormat) = dump(
        graph = graph,
        dumpQuery = { s, v -> format.encodeToHexString(s, v) },
        dumpValue = { s, v -> format.encodeToHexString(s, v) },
    )

    fun <T> dump(
        graph: QueryGraph,
        dumpQuery: (KSerializer<Query<*>>, Query<*>) -> T,
        dumpValue: (KSerializer<Any?>, Any?) -> T,
    ): Dump<T> {
        val dumpValues = mutableListOf<DumpValue<T>>()
        val queryIndices = mutableMapOf<Query<*>, Int>()

        skipEntry@for ((query, value) in orderedEntries(graph)) {
            val valueSerializer = valueSerializers[query::class] ?: continue
            val dependencies = graph.dependencies(query)
            val localDependencies = mutableSetOf<Int>()
            val transientDependencies = mutableSetOf<T>()

            for (dependency in dependencies) {
                if (dependency in queryIndices) {
                    localDependencies += queryIndices[dependency]!!
                    continue
                }

                if (dependency::class !in querySerializers)
                    continue@skipEntry

                transientDependencies += dumpQuery(querySerializer, dependency)
            }

            var validity = when (transientDependencies.isNotEmpty()) {
                true -> QueryGraph.Validity.STRONGLY_INVALID
                else -> graph.validity(query)
            }

            for (localDependency in localDependencies) {
                if (validity != QueryGraph.Validity.VALID) break

                if (dumpValues[localDependency].validity != QueryGraph.Validity.VALID) {
                    validity = QueryGraph.Validity.WEAKLY_INVALID
                    break
                }
            }

            val serializedQuery = dumpQuery(querySerializer, query)
            val serializedValue = dumpValue(valueSerializer, value)

            queryIndices[query] = dumpValues.size
            dumpValues += DumpValue(
                serializedQuery = serializedQuery,
                serializedValue = serializedValue,
                validity = validity,
                localDependencies = localDependencies,
                transientDependencies = transientDependencies,
            )
        }

        return Dump(dumpValues)
    }

    private fun orderedEntries(graph: QueryGraph): List<Pair<Query<*>, Any?>> {
        val readyQueries = mutableListOf<Query<*>>()
        val queuedQueries = mutableMapOf<Query<*>, MutableSet<Query<*>>>()
        val result = mutableListOf<Pair<Query<*>, Any?>>()

        for ((query, _) in graph.asMap()) {
            if (graph.dependencies(query).isEmpty()) {
                readyQueries += query
            } else {
                queuedQueries[query] = graph.dependencies(query).toMutableSet()
            }
        }

        while (readyQueries.isNotEmpty()) {
            val query = readyQueries.removeFirst()

            if (graph[query]?.isSuccess == true)
                result += query to graph[query]!!.getOrThrow()

            for ((dependent, dependencies) in queuedQueries.toList()) {
                dependencies -= query
                if (dependencies.isEmpty()) {
                    readyQueries += dependent
                    queuedQueries -= dependent
                }
            }
        }

        if (queuedQueries.isNotEmpty()) {
            throw IllegalStateException("Cyclic dependency detected")
        }

        return result
    }

    private val querySerializer = PolymorphicSerializer(Query::class)
}
