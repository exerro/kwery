package me.exerro.kwery

import kotlin.reflect.KClass

/**
 * Acts as a delegate to [QueryHandler] instances. Used by
 * [QueryEngine.Builder.addQueryHandler].
 */
interface QueryHandlerProvider {
    /**
     * Return a query handler for the specified descriptor, or throw
     * [QueryEngine.QueryNotHandledException] if no handler can be returned for
     * the given descriptor.
     */
    @Throws(QueryEngine.QueryNotHandledException::class)
    fun <Q: Query<T>, T> provideHandler(
        queryClass: KClass<Q>,
    ): QueryHandler<Q, T>
}
