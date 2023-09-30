package me.exerro.kwery

/**
 * A graph containing evaluated queries, dependencies between queries, and
 * validity for each query value (see [Validity]).
 */
interface QueryGraph {
    /**
     * Validity represents how a value may be used. There are 3 states:
     * - [VALID] - The value is up-to-date and can be used without re-computing.
     * - [WEAKLY_INVALID] - A transitive dependency of the value (not a direct
     *                      dependency) has changed and this value may be
     *                      out-of-date, but the dependency queries must be
     *                      checked to see if they differ and if this value is
     *                      therefore invalid.
     * - [STRONGLY_INVALID] - A direct dependency of the value has changed and
     *                        the value is now out-of-date. The value must be
     *                        recomputed before it can be used.
     */
    enum class Validity {
        VALID,
        WEAKLY_INVALID,
        STRONGLY_INVALID,
    }

    /**
     * Get the value of a query, or null if the query has not been evaluated.
     *
     * Note, this may return values for invalid queries. This is used to compare
     * old vs new values to prevent unnecessary re-computation. See [Validity].
     */
    operator fun <T> get(query: Query<T>): Result<T>?

    /**
     * Get the validity of a query. See [Validity].
     */
    fun validity(query: Query<*>): Validity

    /**
     * Get the direct dependencies of a query. A query is a direct dependency of
     * another query if it is directly used in the computation of the other
     * query.
     */
    fun dependencies(query: Query<*>): Set<Query<*>>

    /**
     * Get the direct dependents of a query. A query is a direct dependent of
     * another query if it directly uses the other query in its computation.
     */
    fun dependents(query: Query<*>): Set<Query<*>>

    /**
     * Get the transitive dependencies of a query. A query is a transitive
     * dependency of another query if it is used in the computation of the other
     * query, either directly or indirectly.
     */
    fun transitiveDependencies(query: Query<*>): Set<Query<*>>

    /**
     * Get the transitive dependents of a query. A query is a transitive
     * dependent of another query if it uses the other query in its computation,
     * either directly or indirectly.
     */
    fun transitiveDependents(query: Query<*>): Set<Query<*>>

    /**
     * Return a map of all queries and their values. The returned map is a
     * "live" view of the data in this dependency graph, meaning that it will
     * update as the graph changes.
     */
    fun asMap(): Map<Query<*>, Result<Any?>>

    /**
     * Create a mutable copy of this graph. Modifications to one will not affect
     * the other.
     */
    fun clone(): MutableQueryGraph
}
