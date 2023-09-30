package me.exerro.kwery

import me.exerro.observables.Observable

/**
 * Query handler that can be observed for changes. When the value of a query
 * changes, the [changed] signal will be emitted with the changed query as its
 * parameter.
 *
 * Note, this should not be used for handling normal dependencies between
 * queries. This is primarily intended for external events such as files
 * changing or user input.
 */
interface ObservableQueryHandler<Q: Query<T>, T>: QueryHandler<Q, T> {
    val changed: Observable<(Query<T>) -> Unit>
}
