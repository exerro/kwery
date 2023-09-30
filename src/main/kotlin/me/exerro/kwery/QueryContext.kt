package me.exerro.kwery

import kotlinx.coroutines.CoroutineScope

/**
 * Context in which queries can be evaluated.
 */
interface QueryContext {
    /**
     * Evaluate a query. In practise, this will likely track dependencies and
     * cache results.
     *
     * The returned value will be up-to-date at the time of being called but may
     * be invalidated immediately after.
     */
    context (CoroutineScope)
    suspend fun <T> evaluate(query: Query<T>): T
}
