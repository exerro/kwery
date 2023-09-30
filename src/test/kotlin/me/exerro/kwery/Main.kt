package me.exerro.kwery

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import me.exerro.kwery.*
import me.exerro.kwery.queries.FileContentsQuery
import me.exerro.observables.Observable
import me.exerro.observables.ObservableSignal
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ConstantQuery(val value: Int): Query<Int>

@Serializable
data class VariableQuery(val name: String): Query<Int>

@Serializable
data class AddQuery(val a: Query<Int>, val b: Query<Int>): Query<Int>

@Serializable
data class LongRunningQuery(val time: Duration): Query<Int>

@Serializable
data class LinesInFile(
    @Serializable(with = PathSerializer::class)
    val path: Path
): Query<Int>

@Serializer(forClass = Path::class)
class PathSerializer: KSerializer<Path> {
    override val descriptor = serializer<String>().descriptor

    override fun deserialize(decoder: Decoder): Path {
        return Paths.get(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: Path) {
        encoder.encodeString(value.toString())
    }
}

class LongRunningQueryHandler: QueryHandler<LongRunningQuery, Int> {
    context(QueryContext, CoroutineScope)
    override suspend fun handle(query: LongRunningQuery): Int {
        println("Entering long running query (${query.time})")
        delay(query.time)
        return (Math.random() * 100).toInt()
    }
}

class VariableQueryHandler: ObservableQueryHandler<VariableQuery, Int> {
    fun increment(name: String) {
        println("Incrementing variable $name")
        data[name] = (data[name] ?: 0) + 1
        emitChanged(VariableQuery(name))
    }

    override val changed: Observable<(Query<Int>) -> Unit>

    context(QueryContext, CoroutineScope)
    override suspend fun handle(query: VariableQuery): Int {
        println("Computed variable ${query.name} as ${data[query.name] ?: 0}")
        return data[query.name] ?: 0
    }

    private val emitChanged: (VariableQuery) -> Unit
    private val data = mutableMapOf<String, Int>()

    init {
        val (changedImpl, emitChangedImpl) = ObservableSignal.createSignal<VariableQuery>()
        changed = changedImpl
        emitChanged = emitChangedImpl
    }
}

suspend fun main() {
    val variableHandler = VariableQueryHandler()

    val scope = CoroutineScope(EmptyCoroutineContext)
    val fileHandler = FileContentsQuery.WatchingHandler(scope,
        Paths.get("src/test/kotlin/me/exerro"))

    val engine = QueryEngine.Builder()
        .addQueryHandler<ConstantQuery, _> { query ->
            println("Computed constant ${query.value}")
            query.value
        }
        .addQueryHandler<AddQuery, _> { query ->
            val value = evaluate(query.a) + evaluate(query.b)
            println("Computed addition ${query.a} + ${query.b} as $value")
            value
        }
        .addQueryHandler(variableHandler)
        .addQueryHandler<LinesInFile, _> { query ->
            val contents = evaluate(FileContentsQuery(query.path))
            val lines = contents.getOrThrow().count { it == '\n'.code.toByte() } + 1
            println("Computed lines in file ${query.path} as $lines")
            lines
        }
        .addQueryHandler(fileHandler)
        .addQueryHandler(LongRunningQueryHandler())
        .build()

    val query = AddQuery(
        AddQuery(
            LinesInFile(Paths.get("src/test/kotlin/me/exerro/kwery/Main.kt")),
            VariableQuery("x"),
        ),
        ConstantQuery(1)
    )

    val job = scope.launch {
        println("TLQ query(): ${engine.evaluate(query)}")
    }

    job.join()

    val graphSerializer = QueryGraphSerializer()
        .addSerializer<ConstantQuery, _>()
        .addSerializer<VariableQuery, _>()
        .addSerializer<AddQuery, _>()
        .addSerializer<LinesInFile, _>()

    val protobufFormat = ProtoBuf { serializersModule = graphSerializer.serializersModule() }
    val jsonFormat = Json { serializersModule = graphSerializer.serializersModule() }
    val stringDump = graphSerializer.dumpToString(engine.graph, jsonFormat)
    val hexDump = graphSerializer.dumpToHex(engine.graph, protobufFormat)

    val otherEngine = QueryEngine.Builder()
        .addQueryHandler<AddQuery, _> { query ->
            val value = evaluate(query.a) + evaluate(query.b)
            println("Computed other addition ${query.a} + ${query.b} as $value")
            value
        }
        .addQueryHandler<LinesInFile, _> { 42 }
        .setGraph(graphSerializer.loadString(stringDump, jsonFormat))
        .build()

    scope.launch {
        println("PLQ query(): ${otherEngine.evaluate(query)}")
    } .join()

    variableHandler.increment("x")

    val printer = QueryGraphPrinter()
        .addPrettyPrinter<LinesInFile> { q -> "#lines in ${q.path}" }
        .addPrettyPrinter<ConstantQuery> { q -> "const ${q.value}" }
        .addPrettyPrinter<VariableQuery> { q -> "var ${q.name}" }
        .addPrettyPrinter<AddQuery> { q -> "Add\n${prettyPrintQuery(q.a)}\n${prettyPrintQuery(q.b)}" }
        .addPrettyPrinter(FileContentsQuery.prettyPrint)

    Files.write(Paths.get("graph.dot"), printer.prettyPrintGraph(engine.graph).toByteArray())
}
