package me.exerro.kwery

import kotlinx.coroutines.CoroutineScope

/**
 * Specialisation of a [Query] which can be handled by default without
 * explicitly providing a query handler.
 *
 * @see Canonical
 */
interface QueryWithDefaultHandler<T>: Query<T>
{
    context (QueryContext, CoroutineScope)
    suspend fun handleByDefault(): T

    val defaultHandler get() = QueryHandler<QueryWithDefaultHandler<T>, T> { _ -> handleByDefault() }
}
