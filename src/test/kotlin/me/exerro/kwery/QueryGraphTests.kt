package me.exerro.kwery

import kotlin.test.Test
import kotlin.test.assertContains

class QueryGraphTests {
    @Test
    fun testPut() {
        val graph = MutableQueryGraph()
        graph.put(TestQuery(1), 1, emptySet())
        graph.put(TestQuery(2), 2, setOf(TestQuery(1)), QueryGraph.Validity.STRONGLY_INVALID)

        assert(graph[TestQuery(1)]?.getOrThrow() == 1)
        assert(graph.validity(TestQuery(1)) == QueryGraph.Validity.VALID)
        assert(graph.dependencies(TestQuery(1)).isEmpty())
        assertContains(graph.dependents(TestQuery(1)), TestQuery(2))

        assert(graph[TestQuery(2)]?.getOrThrow() == 2)
        assert(graph.validity(TestQuery(2)) == QueryGraph.Validity.STRONGLY_INVALID)
        assertContains(graph.dependencies(TestQuery(2)), TestQuery(1))
        assert(graph.dependents(TestQuery(2)).isEmpty())
    }

    @Test
    fun testPutUnchangedValuePropagation() {
        for (startingValidity in QueryGraph.Validity.entries) {
            val graph = MutableQueryGraph()
            graph.put(TestQuery(1), 1, emptySet(), startingValidity)
            graph.put(TestQuery(2), 2, setOf(TestQuery(1)), QueryGraph.Validity.WEAKLY_INVALID)
            graph.put(TestQuery(3), 3, setOf(TestQuery(2)))

            graph.put(TestQuery(1), 1, emptySet())

            assert(graph[TestQuery(1)]?.getOrThrow() == 1)
            assert(graph.validity(TestQuery(1)) == QueryGraph.Validity.VALID)
            assert(graph.validity(TestQuery(2)) == QueryGraph.Validity.WEAKLY_INVALID)
            assert(graph.validity(TestQuery(3)) == QueryGraph.Validity.VALID)
        }
    }

    @Test
    fun testPutChangedValuePropagation() {
        for (startingValidity in QueryGraph.Validity.entries) {
            val graph = MutableQueryGraph()
            graph.put(TestQuery(1), 0, emptySet(), startingValidity)
            graph.put(TestQuery(2), 2, setOf(TestQuery(1)), QueryGraph.Validity.WEAKLY_INVALID)
            graph.put(TestQuery(3), 3, setOf(TestQuery(1)))
            graph.put(TestQuery(4), 4, setOf(TestQuery(3)))

            graph.put(TestQuery(1), 1, emptySet())

            assert(graph[TestQuery(1)]?.getOrThrow() == 1)
            assert(graph.validity(TestQuery(1)) == QueryGraph.Validity.VALID)
            assert(graph.validity(TestQuery(2)) == QueryGraph.Validity.STRONGLY_INVALID)
            assert(graph.validity(TestQuery(3)) == QueryGraph.Validity.STRONGLY_INVALID)
            assert(graph.validity(TestQuery(4)) == QueryGraph.Validity.WEAKLY_INVALID)
        }
    }

    @Test
    fun testInvalidate() {
        for (startingValidity in QueryGraph.Validity.entries) {
            val graph = MutableQueryGraph()
            graph.put(TestQuery(1), 1, emptySet(), startingValidity)
            graph.put(TestQuery(2), 2, setOf(TestQuery(1)))

            graph.invalidate(TestQuery(1))
            assert(graph[TestQuery(1)]?.getOrThrow() == 1)
            assert(graph.validity(TestQuery(1)) == QueryGraph.Validity.STRONGLY_INVALID)
            assert(graph.dependencies(TestQuery(1)).isEmpty())
            assertContains(graph.dependents(TestQuery(1)), TestQuery(2))

            assert(graph[TestQuery(2)]?.getOrThrow() == 2)
            assert(graph.validity(TestQuery(2)) == QueryGraph.Validity.WEAKLY_INVALID)
            assertContains(graph.dependencies(TestQuery(2)), TestQuery(1))
            assert(graph.dependents(TestQuery(2)).isEmpty())
        }
    }

    @Test
    fun testRemove() {
        val graph = MutableQueryGraph()
        graph.put(TestQuery(1), 1, emptySet())
        graph.put(TestQuery(2), 2, setOf(TestQuery(1)))

        graph.remove(TestQuery(1))
        assert(graph[TestQuery(1)] == null)
        assert(graph.validity(TestQuery(1)) == QueryGraph.Validity.STRONGLY_INVALID)
        assert(graph.dependencies(TestQuery(1)).isEmpty())
        assertContains(graph.dependents(TestQuery(1)), TestQuery(2))

        assert(graph[TestQuery(2)]?.getOrThrow() == 2)
        assert(graph.validity(TestQuery(2)) == QueryGraph.Validity.STRONGLY_INVALID)
        assertContains(graph.dependencies(TestQuery(2)), TestQuery(1))
        assert(graph.dependents(TestQuery(2)).isEmpty())
    }

    @Test
    fun testFixValidityCouldBeValid() {
        val graph = MutableQueryGraph()
        graph.put(TestQuery(1), 1, emptySet())
        graph.put(TestQuery(2), 2, emptySet())
        graph.put(TestQuery(3), 3, setOf(TestQuery(1), TestQuery(2)), QueryGraph.Validity.WEAKLY_INVALID)

        graph.validateWeakQuery(TestQuery(3))
        assert(graph.validity(TestQuery(3)) == QueryGraph.Validity.VALID)
    }

    @Test
    fun testFixValidityMustStayInvalid() {
        for (testValidity in listOf(QueryGraph.Validity.WEAKLY_INVALID, QueryGraph.Validity.STRONGLY_INVALID)) {
            val graph = MutableQueryGraph()
            graph.put(TestQuery(1), 1, emptySet(), testValidity)
            graph.put(TestQuery(2), 2, emptySet())
            graph.put(TestQuery(3), 3, setOf(TestQuery(1), TestQuery(2)), QueryGraph.Validity.WEAKLY_INVALID)

            graph.validateWeakQuery(TestQuery(3))
            assert(graph.validity(TestQuery(3)) == QueryGraph.Validity.WEAKLY_INVALID)
        }
    }

    @Test
    fun testTransitiveDependencies() {
        val graph = MutableQueryGraph()
        graph.put(TestQuery(1), 1, emptySet())
        graph.put(TestQuery(2), 2, setOf(TestQuery(1)))
        graph.put(TestQuery(3), 3, setOf(TestQuery(1)))
        graph.put(TestQuery(4), 4, setOf(TestQuery(2), TestQuery(3)))

        assertContains(graph.transitiveDependencies(TestQuery(4)), TestQuery(1))
        assertContains(graph.transitiveDependencies(TestQuery(4)), TestQuery(2))
        assertContains(graph.transitiveDependencies(TestQuery(4)), TestQuery(3))
        assert(graph.transitiveDependencies(TestQuery(4)).size == 3)
    }

    @Test
    fun testTransitiveDependents() {
        val graph = MutableQueryGraph()
        graph.put(TestQuery(1), 1, emptySet())
        graph.put(TestQuery(2), 2, setOf(TestQuery(1)))
        graph.put(TestQuery(3), 3, setOf(TestQuery(1)))
        graph.put(TestQuery(4), 4, setOf(TestQuery(2), TestQuery(3)))

        assertContains(graph.transitiveDependents(TestQuery(1)), TestQuery(2))
        assertContains(graph.transitiveDependents(TestQuery(1)), TestQuery(3))
        assertContains(graph.transitiveDependents(TestQuery(1)), TestQuery(4))
        assert(graph.transitiveDependents(TestQuery(1)).size == 3)
    }

    @Test
    fun testAsMap() {
        val graph = MutableQueryGraph()
        graph.put(TestQuery(1), 1, emptySet())
        graph.put(TestQuery(2), 2, setOf(TestQuery(1)))
        graph.put(TestQuery(3), 3, setOf(TestQuery(2)))

        val map = graph.asMap()
        assert(map[TestQuery(1)]?.getOrThrow() == 1)
        assert(map[TestQuery(2)]?.getOrThrow() == 2)
        assert(map[TestQuery(3)]?.getOrThrow() == 3)
        assert(map[TestQuery(4)] == null)
        assert(map.size == 3)
    }

    @Test
    fun testClone() {
        val graph = MutableQueryGraph()
        graph.put(TestQuery(1), 1, emptySet())
        graph.put(TestQuery(2), 2, setOf(TestQuery(1)), QueryGraph.Validity.STRONGLY_INVALID)
        graph.put(TestQuery(3), 3, setOf(TestQuery(2)))
        val clone = graph.clone()
        graph.put(TestQuery(4), 4, setOf(TestQuery(3)))
        clone.put(TestQuery(5), 5, setOf(TestQuery(3)))

        assert(clone[TestQuery(1)]?.getOrThrow() == 1)
        assert(clone[TestQuery(2)]?.getOrThrow() == 2)
        assert(clone[TestQuery(3)]?.getOrThrow() == 3)
        assert(clone[TestQuery(4)] == null)
        assert(clone[TestQuery(5)]?.getOrThrow() == 5)
        assert(graph[TestQuery(5)] == null)

        assert(clone.validity(TestQuery(1)) == QueryGraph.Validity.VALID)
        assert(clone.validity(TestQuery(2)) == QueryGraph.Validity.STRONGLY_INVALID)

        assertContains(clone.dependencies(TestQuery(2)), TestQuery(1))
        assertContains(clone.dependencies(TestQuery(3)), TestQuery(2))
        assertContains(clone.dependencies(TestQuery(5)), TestQuery(3))
        assertContains(clone.transitiveDependencies(TestQuery(3)), TestQuery(1))

        assertContains(clone.dependents(TestQuery(1)), TestQuery(2))
        assertContains(clone.dependents(TestQuery(2)), TestQuery(3))
        assertContains(clone.dependents(TestQuery(3)), TestQuery(5))
        assertContains(clone.transitiveDependents(TestQuery(1)), TestQuery(3))
        assert(TestQuery(4) !in clone.dependents(TestQuery(3)))
        assert(TestQuery(5) !in graph.dependents(TestQuery(3)))
    }
}
