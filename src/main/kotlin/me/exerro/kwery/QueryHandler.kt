package me.exerro.kwery

import kotlinx.coroutines.CoroutineScope

/**
 * Handles queries of type [Q] and returns a value of type [T].
 *
 * Added to a [QueryEngine] using [QueryEngine.Builder.addQueryHandler].
 *
 * @see ObservableQueryHandler
 * @see Query
 */
fun interface QueryHandler<in Q: Query<T>, out T> {
    context (QueryContext, CoroutineScope)
    suspend fun handle(query: Q): T
}
