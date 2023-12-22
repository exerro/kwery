package me.exerro.kwery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.exerro.observables.Observable
import me.exerro.observables.ObservableSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

data class QueryWithDefaultHandlerTest(
    val value: Int,
): QueryWithDefaultHandler<Int> {
    context(QueryContext, CoroutineScope)
    override suspend fun handleByDefault() = value
}

data class CanonicalTestQuery1(val value: Int): Query<Int>
data class CanonicalTestQuery2(val value: String): Query<String>

@Canonical
object CanonicalTestQuery1Handler: QueryHandler<CanonicalTestQuery1, Int> {
    context (QueryContext, CoroutineScope)
    override suspend fun handle(query: CanonicalTestQuery1): Int {
        return query.value
    }
}

@Canonical
object CanonicalTestQuery2Handler: QueryHandler<CanonicalTestQuery2, String> {
    context (QueryContext, CoroutineScope)
    override suspend fun handle(query: CanonicalTestQuery2): String {
        return query.value
    }
}

class SimpleHandler(
    private val delay: Duration,
    private val sumQueries: Boolean,
): QueryHandler<TestQuery, Int> {
    var timesHandled = 0

    context (QueryContext, CoroutineScope)
    override suspend fun handle(query: TestQuery): Int {
        ++timesHandled
        delay(delay)

        if (!sumQueries)
            return query.value

        if (query.value <= 0)
            return 0

        return evaluate(TestQuery(query.value - 1)) + query.value
    }
}

class InvalidatingHandler(
    val throwOnZero: Boolean,
): ObservableQueryHandler<TestQuery, Int> {
    var timesHandled = 0

    fun invalidate(query: TestQuery) {
        emitChanged(query)
    }

    fun setOffset(offset: Int) {
        this.offset = offset
    }

    data class Error(val value: Int): Exception()

    context (QueryContext, CoroutineScope)
    override suspend fun handle(query: TestQuery): Int {
        ++timesHandled

        if (query.value <= 0 && throwOnZero)
            throw Error(errorCounter++)

        if (query.value <= 0)
            return 0

        return evaluate(TestQuery(query.value - 1)) + query.value + offset
    }

    override val changed: Observable<(TestQuery) -> Unit>
    private val emitChanged: (TestQuery) -> Unit
    private var offset = 0
    private var errorCounter = 0

    init {
        val (signal, emit) = ObservableSignal.createSignal<TestQuery>()
        changed = signal
        emitChanged = emit
    }
}

class QueryEngineTests {
    @Test
    fun testSimpleQuery() {
        runBlocking {
            val engine = QueryEngine.Builder()
                .addQueryHandler(SimpleHandler(delay=0.milliseconds, sumQueries=false))
                .build()
            val result = engine.evaluate(TestQuery(5))
            assertEquals(5, result)
        }
    }

    @Test
    fun testCachedQuery() {
        runBlocking {
            val handler = SimpleHandler(delay=0.milliseconds, sumQueries=false)
            val engine = QueryEngine.Builder()
                .addQueryHandler(handler)
                .build()
            val result = engine.evaluate(TestQuery(5))
            val result2 = engine.evaluate(TestQuery(5))
            assertEquals(5, result)
            assertEquals(5, result2)
            assertEquals(1, handler.timesHandled)
        }
    }

    @Test
    fun testLongRunningQuery() {
        runBlocking {
            val engine = QueryEngine.Builder()
                .addQueryHandler(SimpleHandler(delay=500.milliseconds, sumQueries=false))
                .build()
            val result = engine.evaluate(TestQuery(5))
            val secondResult = engine.evaluate(TestQuery(5))
            assertEquals(5, result)
            assertEquals(5, secondResult)
        }
    }

    @Test
    fun noParallelQueries() {
        runBlocking {
            val handler = SimpleHandler(delay=500.milliseconds, sumQueries=false)
            val engine = QueryEngine.Builder()
                .addQueryHandler(handler)
                .build()

            val startTime = TimeSource.Monotonic.markNow()

            val a = launch {
                assertEquals(5, engine.evaluate(TestQuery(5)))
            }

            val b = launch {
                assertEquals(5, engine.evaluate(TestQuery(5)))
            }

            val c = launch {
                delay(300.milliseconds)
                assertEquals(5, engine.evaluate(TestQuery(5)))
            }

            a.join()
            b.join()
            c.join()

            val endTime = TimeSource.Monotonic.markNow()

            assertEquals(1, handler.timesHandled)

            // checking the C job didn't delay evaluation despite starting late:
            assert(endTime - startTime >= 400.milliseconds)
            assert(endTime - startTime <= 600.milliseconds)
        }
    }

    @Test
    fun testNestedQuery() {
        runBlocking {
            val handler = SimpleHandler(delay=0.milliseconds, sumQueries=true)
            val engine = QueryEngine.Builder()
                .addQueryHandler(handler)
                .build()
            val result = engine.evaluate(TestQuery(5))
            assertEquals(15, result)
            assertEquals(6, handler.timesHandled)
        }
    }

    /**
     * Test that when invalidating a query, all dependent queries will be
     * re-evaluated when the invalidated query evaluates to something different.
     */
    @Test
    fun testChangedInvalidation() {
        runBlocking {
            val handler = InvalidatingHandler(throwOnZero=false)
            val engine = QueryEngine.Builder()
                .addQueryHandler(handler)
                .build()
            val result = engine.evaluate(TestQuery(5))
            assertEquals(15, result)
            assertEquals(6, handler.timesHandled)

            handler.setOffset(1)
            handler.invalidate(TestQuery(5))
            val result2 = engine.evaluate(TestQuery(5))
            assertEquals(16, result2)
            assertEquals(7, handler.timesHandled)

            val result3 = engine.evaluate(TestQuery(5))
            assertEquals(16, result3)
            assertEquals(7, handler.timesHandled)

            handler.setOffset(2)
            handler.invalidate(TestQuery(4))
            val result4 = engine.evaluate(TestQuery(5))
            assertEquals(19, result4)
            assertEquals(9, handler.timesHandled)

            handler.setOffset(3)
            handler.invalidate(TestQuery(3))
            val result5 = engine.evaluate(TestQuery(5))
            assertEquals(24, result5)
            assertEquals(12, handler.timesHandled)
        }
    }

    /**
     * Test that when invalidating a query, dependent queries will only be
     * re-evaluated when the invalidated query evaluates to something different.
     */
    @Test
    fun testUnchangedInvalidation() {
        runBlocking {
            val handler = InvalidatingHandler(throwOnZero=false)
            val engine = QueryEngine.Builder()
                .addQueryHandler(handler)
                .build()
            val result = engine.evaluate(TestQuery(5))
            assertEquals(15, result)
            assertEquals(6, handler.timesHandled)

            handler.invalidate(TestQuery(5))
            val result2 = engine.evaluate(TestQuery(5))
            assertEquals(15, result2)
            assertEquals(7, handler.timesHandled)

            val result3 = engine.evaluate(TestQuery(5))
            assertEquals(15, result3)
            assertEquals(7, handler.timesHandled)

            handler.invalidate(TestQuery(4))
            val result4 = engine.evaluate(TestQuery(5))
            assertEquals(15, result4)
            assertEquals(8, handler.timesHandled)

            handler.invalidate(TestQuery(3))
            val result5 = engine.evaluate(TestQuery(5))
            assertEquals(15, result5)
            assertEquals(9, handler.timesHandled)
        }
    }

    @Test
    fun testExceptionsEvaluatedOnce() {
        val handler = InvalidatingHandler(throwOnZero=true)
        val engine = QueryEngine.Builder()
            .addQueryHandler(handler)
            .build()

        assertFailsWith(InvalidatingHandler.Error::class) {
            runBlocking {
                engine.evaluate(TestQuery(0))
            }
        }
        assertEquals(1, handler.timesHandled)

        assertFailsWith(InvalidatingHandler.Error::class) {
            runBlocking {
                engine.evaluate(TestQuery(0))
            }
        }
        assertEquals(1, handler.timesHandled)
    }

    @Test
    fun testInvalidatedExceptionsReevaluated() {
        val handler = InvalidatingHandler(throwOnZero=true)
        val engine = QueryEngine.Builder()
            .addQueryHandler(handler)
            .build()

        assertFailsWith(InvalidatingHandler.Error::class) {
            runBlocking {
                engine.evaluate(TestQuery(0))
            }
        }
        assertEquals(1, handler.timesHandled)

        handler.invalidate(TestQuery(0))

        assertFailsWith(InvalidatingHandler.Error::class) {
            runBlocking {
                engine.evaluate(TestQuery(0))
            }
        }
        assertEquals(2, handler.timesHandled)
    }

    @Test
    fun testReevaluateWhenParentThrows() {
        val handler = InvalidatingHandler(throwOnZero=true)
        val engine = QueryEngine.Builder()
            .addQueryHandler(handler)
            .build()

        assertFailsWith(InvalidatingHandler.Error::class) {
            runBlocking {
                engine.evaluate(TestQuery(1))
            }
        }
        assertEquals(2, handler.timesHandled)

        handler.invalidate(TestQuery(0))

        assertFailsWith(InvalidatingHandler.Error::class) {
            runBlocking {
                engine.evaluate(TestQuery(1))
            }
        }
        assertEquals(4, handler.timesHandled)
    }

    @Test
    fun testFindsCanonicalHandler() {
        val engine = QueryEngine.Builder()
            .addCanonicalQueryHandler<CanonicalTestQuery1, _>()
            .addCanonicalQueryHandler<CanonicalTestQuery2, _>()
            .build()

        runBlocking {
            val result1 = engine.evaluate(CanonicalTestQuery1(5))
            val result2 = engine.evaluate(CanonicalTestQuery2("hello"))
            assertEquals(5, result1)
            assertEquals("hello", result2)
        }
    }

    @Test
    fun testFindsCanonicalHandlers() {
        val engine = QueryEngine.Builder()
            .addCanonicalQueryHandlers("me.exerro.kwery")
            .build()

        runBlocking {
            val result1 = engine.evaluate(CanonicalTestQuery1(5))
            val result2 = engine.evaluate(CanonicalTestQuery2("hello"))
            assertEquals(5, result1)
            assertEquals("hello", result2)
        }
    }

    @Test
    fun testQueryWithDefaultHandler() {
        val engine = QueryEngine.Builder().build()

        runBlocking {
            val result = engine.evaluate(QueryWithDefaultHandlerTest(5))
            assertEquals(5, result)
        }
    }
}
