package me.exerro.kwery

/**
 * A [Query] can be made to a [QueryEngine], delegating evaluation to a
 * [QueryHandler]. Queries are simple data classes with no implicit behaviour,
 * instead relying on query handlers to provide behaviour.
 */
interface Query<out T> {
    override fun toString(): String
    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
}
