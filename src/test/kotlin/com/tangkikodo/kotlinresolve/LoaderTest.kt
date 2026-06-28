package com.tangkikodo.kotlinresolve

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoaderTest {

    @Test
    fun `load coalesces concurrent calls into one batch`() = runBlocking {
        var batchInvocations = 0
        var keysObserved: List<Int>? = null

        val loader = DataLoader<Int, String>(
            batchFn = { keys ->
                batchInvocations++
                keysObserved = keys
                keys.map { "v$it" }
            },
            scope = this,
        )

        coroutineScope {
            val jobs = (1..5).map { i ->
                async { loader.load(i) }
            }
            val results = jobs.awaitAll()
            assertEquals(listOf("v1", "v2", "v3", "v4", "v5"), results)
        }

        assertEquals(1, batchInvocations, "expected a single batch invocation")
        assertEquals((1..5).toList(), keysObserved)
    }

    @Test
    fun `load deduplicates identical keys`() = runBlocking {
        var keysObserved: List<Int>? = null
        val loader = DataLoader<Int, String>(
            batchFn = { keys -> keysObserved = keys; keys.map { "v$it" } },
            scope = this,
        )
        coroutineScope {
            val jobs = listOf(1, 1, 2, 2, 3, 3).map { k -> async { loader.load(k) } }
            val results = jobs.awaitAll()
            assertEquals(listOf("v1", "v1", "v2", "v2", "v3", "v3"), results)
        }
        // Two distinct keys, deduplicated from six calls.
        assertEquals(listOf(1, 2, 3), keysObserved)
    }

    @Test
    fun `separate BFS levels produce separate batches`() = runBlocking {
        val batchSizes = mutableListOf<Int>()
        val loader = DataLoader<Int, String>(
            batchFn = { keys -> batchSizes.add(keys.size); keys.map { "v$it" } },
            scope = this,
        )

        // Two sequential coroutineScopes — emulating two BFS levels.
        coroutineScope {
            listOf(1, 2, 3).map { async { loader.load(it) } }.awaitAll()
        }
        coroutineScope {
            listOf(10, 20).map { async { loader.load(it) } }.awaitAll()
        }

        assertEquals(listOf(3, 2), batchSizes, "each level should produce exactly one batch")
    }

    @Test
    fun `buildObject aligns results with requested keys`() {
        data class User(val id: Int, val name: String)
        val users = listOf(User(2, "B"), User(4, "D"))
        val result = buildObject(users, listOf(1, 2, 3, 4)) { it.id }
        assertEquals(listOf(null, User(2, "B"), null, User(4, "D")), result)
    }

    @Test
    fun `buildList groups items by key and respects key order`() {
        data class Task(val sprintId: Int, val title: String)
        val tasks = listOf(
            Task(2, "T2a"), Task(1, "T1"), Task(2, "T2b"), Task(3, "T3"),
        )
        val result = buildList(tasks, listOf(1, 2, 3, 4)) { it.sprintId }
        assertEquals(4, result.size)
        assertEquals(listOf(Task(1, "T1")), result[0])
        assertEquals(listOf(Task(2, "T2a"), Task(2, "T2b")), result[1])
        assertEquals(listOf(Task(3, "T3")), result[2])
        assertTrue(result[3].isEmpty(), "missing key should yield empty list, not null")
    }
}
