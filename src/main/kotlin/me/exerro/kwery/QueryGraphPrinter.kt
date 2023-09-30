package me.exerro.kwery

import kotlin.reflect.KClass

class QueryGraphPrinter private constructor(
    private val printers: Map<KClass<out Query<*>>, context(QueryGraphPrinter) (Query<*>) -> String>
) {
    constructor(): this(emptyMap())

    fun <T: Query<*>> addPrettyPrinter(klass: KClass<T>, printer: context(QueryGraphPrinter) (T) -> String): QueryGraphPrinter {
        @Suppress("UNCHECKED_CAST")
        val castedPrinter = printer as context(QueryGraphPrinter) (Query<*>) -> String
        return QueryGraphPrinter(printers + (klass to castedPrinter))
    }

    inline fun <reified T: Query<*>> addPrettyPrinter(noinline printer: context(QueryGraphPrinter) (T) -> String) =
        addPrettyPrinter(T::class, printer)

    fun prettyPrintQuery(query: Query<*>): String {
        return when (val printer = printers[query::class]) {
            null -> query.toString()
            else -> printer(this, query)
        }
    }

    /** Generate Graphviz/dot code for the graph. */
    fun prettyPrintGraph(graph: QueryGraph): String {
        val builder = StringBuilder()
        var nodeIndex = 0
        val indices = graph.asMap().mapValues { nodeIndex++ }

        builder.append("digraph {\n")
        for ((query, _) in graph.asMap()) {
            val thisIndex = indices[query]!!
            val validity = graph.validity(query)
            val outlineColour = when (validity) {
                QueryGraph.Validity.VALID -> "green"
                QueryGraph.Validity.WEAKLY_INVALID -> "blue"
                QueryGraph.Validity.STRONGLY_INVALID -> "red"
            }
            val prettyPrinted = prettyPrintQuery(query)
                .replace("\"", "\\\"")
                .replace("\\", "\\\\")

            builder.append("  n$thisIndex [label=\"$prettyPrinted\", color=$outlineColour, shape=ellipse]\n")

            for (dependency in graph.dependencies(query)) {
                val dependencyIndex = indices[dependency]!!
                builder.append("  n$dependencyIndex -> n$thisIndex\n")
            }
        }
        builder.append("}\n")
        return builder.toString()
    }

    companion object {
        fun <T> prettyPrinter(printer: context(QueryGraphPrinter) (T) -> String) =
            printer
    }
}
