package me.exerro.kwery

import kotlinx.coroutines.CoroutineScope

data class TestQuery(val value: Int): Query<Int>

object TestQueryHandler: QueryHandler<TestQuery, Int> {
    context(QueryContext, CoroutineScope)
    override suspend fun handle(query: TestQuery) =
        query.value
}
