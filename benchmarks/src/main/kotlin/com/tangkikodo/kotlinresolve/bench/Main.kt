package com.tangkikodo.kotlinresolve.bench

import com.tangkikodo.kotlinresolve.AncestorContext
import com.tangkikodo.kotlinresolve.Collector
import com.tangkikodo.kotlinresolve.CollectorParam
import com.tangkikodo.kotlinresolve.DataLoader
import com.tangkikodo.kotlinresolve.ExposeAs
import com.tangkikodo.kotlinresolve.LoaderDep
import com.tangkikodo.kotlinresolve.Post
import com.tangkikodo.kotlinresolve.Resolve
import com.tangkikodo.kotlinresolve.Resolver
import com.tangkikodo.kotlinresolve.buildObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.min
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private const val WARMUP = 3
private const val ROUNDS = 10

private fun report(name: String, samplesMs: List<Double>) {
    val sorted = samplesMs.sorted()
    val minMs = sorted.first()
    val maxMs = sorted.last()
    val meanMs = sorted.average()
    val medianMs = sorted[sorted.size / 2]
    val ops = if (meanMs > 0) 1000.0 / meanMs else 0.0
    println(
        "  %-50s min=%8.2fms  mean=%8.2fms  median=%8.2fms  max=%8.2fms  ops/s=%6.1f".format(
            name, minMs, meanMs, medianMs, maxMs, ops,
        )
    )
}

private fun bench(name: String, fn: () -> Unit) {
    repeat(WARMUP) { fn() }
    val samples = (1..ROUNDS).map {
        val t0 = System.nanoTime()
        fn()
        (System.nanoTime() - t0) / 1_000_000.0
    }
    report(name, samples)
}

// ============================================================================
// Case 1: basic_resolve_sync — 100 students, sync resolve_display_name
// ============================================================================

class SyncStudent(val id: Int, val name: String) {
    var displayName: String = ""

    @Resolve("displayName")
    fun resolveDisplayName(): String = "Student: $name"
}

// ============================================================================
// Case 2: basic_resolve_async — 100 students, async resolve with 1ms sleep
// ============================================================================

class AsyncStudent(val id: Int, val name: String) {
    var courses: List<String> = emptyList()

    @Resolve("courses")
    suspend fun resolveCourses(): List<String> {
        delay(1)
        return listOf("Math", "Science", "History")
    }
}

// ============================================================================
// Case 3: dataloader — 1000 tasks → owner user via DataLoader
// ============================================================================

data class SimpleUser(val id: Int, val name: String, val email: String)

private val userDb: Map<Int, SimpleUser> = (0 until 100).associate {
    it to SimpleUser(it, "User $it", "u$it@x")
}

class TaskWithUser(val id: Int, val title: String, val ownerId: Int) {
    var owner: SimpleUser? = null

    @Resolve("owner")
    suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, SimpleUser>) =
        loader.load(ownerId)
}

// ============================================================================
// Case 4: expose — 3-level tree 20 × 10 × 5 = 1000 nodes
// ============================================================================

class GrandChildEx(val id: Int, val name: String) {
    var rootName: String = ""
    var parentId: String = ""

    @Post("rootName")
    fun postRootName(ancestorContext: AncestorContext): String =
        ancestorContext["rootName"]?.toString() ?: ""

    @Post("parentId")
    fun postParentId(ancestorContext: AncestorContext): String =
        ancestorContext["parentPath"]?.toString() ?: ""
}

class ChildEx(val id: Int, val name: String) {
    @ExposeAs("parentPath")
    val exposedId: Int get() = id

    var grandChildren: List<GrandChildEx> = emptyList()

    @Resolve("grandChildren")
    suspend fun resolveGrandChildren(): List<GrandChildEx> {
        delay(1)
        return (0 until 5).map { GrandChildEx(it, "GC $it") }
    }
}

class RootEx(val id: Int, val name: String) {
    @ExposeAs("rootName")
    val exposedName: String get() = name

    var children: List<ChildEx> = emptyList()

    @Resolve("children")
    suspend fun resolveChildren(): List<ChildEx> {
        delay(1)
        return (0 until 10).map { ChildEx(it, "Child $it") }
    }
}

// ============================================================================
// Case 5: deep_nesting — 363 nodes, max_depth=5, branch=3
// ============================================================================

class Node(val id: Int, val level: Int) {
    var children: List<Node> = emptyList()
    var descendantCount: Int = 0

    @Resolve("children")
    suspend fun resolveChildren(context: Map<String, Any?>?): List<Node> {
        val ctx = context ?: return emptyList()
        val maxDepth = ctx["maxDepth"] as Int
        if (level >= maxDepth) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val remaining = ctx["remaining"] as MutableList<Int>
        if (remaining[0] <= 0) return emptyList()
        val branch = min(ctx["branch"] as Int, remaining[0])
        remaining[0] = remaining[0] - branch
        delay(100.toDuration(DurationUnit.MICROSECONDS))  // 0.1ms
        return (0 until branch).map { Node(it, level + 1) }
    }

    @Post("descendantCount")
    fun postDescendantCount(): Int = 1 + children.sumOf { it.descendantCount }
}

// ============================================================================
// Case 6: large_dataset_with_post — 2000 items, post_calculated
// ============================================================================

class LargeItem(val id: Int, val value: Int) {
    var calculated: Int = 0

    @Post("calculated")
    fun postCalculated(): Int = value * 2
}

// ============================================================================
// Run
// ============================================================================

fun main() {
    println("Kotlin: warmup=$WARMUP, rounds=$ROUNDS")
    println("-".repeat(100))

    bench("basic_resolve_sync (100)") {
        val students = (0 until 100).map { SyncStudent(it, "Student $it") }
        runBlocking { Resolver().resolve(students) }
    }

    bench("basic_resolve_async (100, 1ms each)") {
        val students = (0 until 100).map { AsyncStudent(it, "Student $it") }
        runBlocking { Resolver().resolve(students) }
    }

    bench("dataloader (1000 tasks, 10ms batch)") {
        val tasks = (0 until 1000).map { TaskWithUser(it, "Task $it", it % 10) }
        runBlocking {
            Resolver(
                loaderFactory = mapOf(
                    "user" to {
                        DataLoader(
                            batchFn = { keys ->
                                delay(10)
                                buildObject(userDb.values.toList(), keys) { it.id }
                            },
                            scope = this,
                        )
                    }
                )
            ).resolve(tasks)
        }
    }

    bench("expose (20x10x5=1000 nodes, 1ms each)") {
        val roots = (0 until 20).map { RootEx(it, "Root $it") }
        runBlocking { Resolver().resolve(roots) }
    }

    bench("deep_nesting (363 nodes, 0.1ms each)") {
        runBlocking {
            val ctx = mapOf(
                "remaining" to mutableListOf(363),
                "maxDepth" to 5,
                "branch" to 3,
            )
            Resolver(context = ctx).resolve(Node(0, 0))
        }
    }

    bench("large_dataset_post (2000 items)") {
        val items = (0 until 2000).map { LargeItem(it, it) }
        runBlocking { Resolver().resolve(items) }
    }
}
