package me.exerro.kwery

/** TODO */
class MutableQueryGraph: QueryGraph {
    /** TODO */
    fun <T> put(query: Query<T>, value: Result<T>, dependencies: Set<Query<*>>, validity: QueryGraph.Validity = QueryGraph.Validity.VALID) {
        if (nodeValues[query] != value)
            notifyChanged(query)

        val oldDependencies = this.dependencies[query] ?: emptySet()
        for (addedDependency in dependencies - oldDependencies) {
            dependents.getOrPut(addedDependency) { mutableSetOf() } += query
        }
        for (removedDependency in oldDependencies - dependencies) {
            dependents[removedDependency]!! -= query
        }
        this.dependencies[query] = dependencies
        this.validity[query] = validity
        nodeValues[query] = value
    }

    /** TODO */
    @JvmName("putValue")
    fun <T> put(query: Query<T>, value: T, dependencies: Set<Query<*>>, validity: QueryGraph.Validity = QueryGraph.Validity.VALID) =
        put(query, Result.success(value), dependencies, validity)

    /** TODO */
    fun invalidate(query: Query<*>) {
        for (dependent in transitiveDependents(query))
            validity[dependent] = QueryGraph.Validity.WEAKLY_INVALID

        validity[query] = QueryGraph.Validity.STRONGLY_INVALID
    }

    /** TODO */
    fun remove(query: Query<*>) {
        notifyChanged(query)

        for (removedDependency in dependencies[query] ?: emptySet()) {
            dependents[removedDependency]!! -= query
        }

        nodeValues.remove(query)
        dependencies.remove(query)
        validity.remove(query)
    }

    // TODO: Add a way to set validity? engine could then strongly invalidate
    //       changed queries rather than removing them
    //       In fact, do we ever want to remove something?

    /** TODO */
    fun fixValidity(query: Query<*>) {
        if (validity[query] != QueryGraph.Validity.WEAKLY_INVALID) return

        for (dependency in dependencies[query] ?: emptySet()) {
            if (validity[dependency] != QueryGraph.Validity.VALID) return
        }

        validity[query] = QueryGraph.Validity.VALID
    }

    override operator fun <T> get(query: Query<T>): Result<T>? {
        @Suppress("UNCHECKED_CAST")
        return nodeValues[query] as Result<T>?
    }

    override fun validity(query: Query<*>): QueryGraph.Validity {
        return validity[query] ?: QueryGraph.Validity.STRONGLY_INVALID
    }

    override fun dependencies(query: Query<*>): Set<Query<*>> {
        return dependencies[query] ?: emptySet()
    }

    override fun dependents(query: Query<*>): Set<Query<*>> {
        return dependents[query] ?: emptySet()
    }

    override fun transitiveDependencies(query: Query<*>): Set<Query<*>> {
        val queue = ArrayDeque<Query<*>>()
        val visited = mutableSetOf<Query<*>>()
        queue += dependencies[query] ?: emptySet()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in visited) continue
            visited += current
            queue += dependencies[current] ?: emptySet()
        }
        return visited
    }

    override fun transitiveDependents(query: Query<*>): Set<Query<*>> {
        val queue = ArrayDeque<Query<*>>()
        val visited = mutableSetOf<Query<*>>()
        queue += dependents[query] ?: emptySet()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in visited) continue
            visited += current
            queue += dependents[current] ?: emptySet()
        }
        return visited
    }

    override fun asMap() = object: Map<Query<*>, Result<Any?>> {
        override val entries get() = nodeValues.entries
        override val keys get() = nodeValues.keys
        override val values get() = nodeValues.values
        override val size get() = nodeValues.size
        override fun containsKey(key: Query<*>) = nodeValues.containsKey(key)
        override fun containsValue(value: Result<Any?>) = nodeValues.containsValue(value)
        override fun get(key: Query<*>) = nodeValues[key]
        override fun isEmpty() = nodeValues.isEmpty()
    }

    override fun clone(): MutableQueryGraph {
        val graph = MutableQueryGraph()

        for ((k, v) in nodeValues)
            graph.nodeValues[k] = v

        for ((k, v) in validity)
            graph.validity[k] = v

        for ((k, v) in dependencies)
            graph.dependencies[k] = v

        for ((k, v) in dependents)
            graph.dependents[k] = v.toMutableSet()

        return graph
    }

    private fun notifyChanged(query: Query<*>) {
        val directDependents = dependents[query] ?: emptySet()
        val allDependents = transitiveDependents(query) - directDependents

        for (dependent in directDependents) {
            validity[dependent] = QueryGraph.Validity.STRONGLY_INVALID
        }

        for (dependent in allDependents) {
            validity[dependent] = when (validity[dependent]) {
                QueryGraph.Validity.STRONGLY_INVALID -> QueryGraph.Validity.STRONGLY_INVALID
                else -> QueryGraph.Validity.WEAKLY_INVALID
            }
        }
    }

    private val nodeValues: MutableMap<Query<*>, Result<Any?>> = mutableMapOf()
    private val validity: MutableMap<Query<*>, QueryGraph.Validity> = mutableMapOf()
    private val dependencies: MutableMap<Query<*>, Set<Query<*>>> = mutableMapOf()
    private val dependents: MutableMap<Query<*>, MutableSet<Query<*>>> = mutableMapOf()
}
