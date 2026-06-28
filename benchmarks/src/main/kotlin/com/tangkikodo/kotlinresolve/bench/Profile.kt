package com.tangkikodo.kotlinresolve.bench

import com.tangkikodo.kotlinresolve.AncestorContext
import com.tangkikodo.kotlinresolve.DataLoader
import com.tangkikodo.kotlinresolve.ICollector
import com.tangkikodo.kotlinresolve.InvokeCtx
import com.tangkikodo.kotlinresolve.KotlinResolveAdapter
import com.tangkikodo.kotlinresolve.Resolve
import com.tangkikodo.kotlinresolve.Resolver
import com.tangkikodo.kotlinresolve.AdapterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Bucket profiler: split one resolve() of 100 SyncStudents into its constituent costs
 * so we can see where the remaining ~0.85ms goes.
 *
 * Each bucket is timed in isolation, N=100_000 iterations, then divided to get ns/op.
 * Final column shows what fraction of a single resolve() (85µs avg per case run) the
 * bucket would represent if executed 100 times (once per node).
 */
class ProfileSyncStudent(val id: Int, val name: String) {
    var displayName: String = ""

    @Resolve("displayName")
    fun resolveDisplayName(): String = "Student: $name"
}

private const val ITERS = 100_000
private const val NODES = 100

private fun fmtNs(ns: Long): String = "%,8d ns".format(ns)
private fun fmtNsOp(ns: Long): String = "%6.1f ns/op".format(ns.toDouble() / ITERS)

private fun bucket(name: String, body: () -> Unit) {
    // Warmup
    repeat(5_000) { body() }
    val t0 = System.nanoTime()
    repeat(ITERS) { body() }
    val elapsed = System.nanoTime() - t0
    val perOp = elapsed.toDouble() / ITERS
    val totalFor100 = perOp * NODES
    println(
        "  %-40s total=%s  %s  →  ×100 = %6.1f µs".format(
            name, fmtNs(elapsed), fmtNsOp(elapsed), totalFor100,
        )
    )
}

suspend fun main() {
    println("=== Bucket profiler: 1 resolve() of 100 SyncStudents ≈ 850 µs ===")
    println("Iterations per bucket: $ITERS")
    println("-".repeat(110))

    // Sanity: time a full resolve() of 100 nodes.
    val students = (0 until NODES).map { SyncStudent(it, "Student $it") }
    val resolver = Resolver()
    var t0 = System.nanoTime()
    repeat(1_000) { runBlocking { resolver.resolve(students) } }
    val fullElapsed = System.nanoTime() - t0
    println(
        "  %-40s %s  →  %5.1f µs/call".format(
            "full resolve(100 students)",
            fmtNs(fullElapsed),
            fullElapsed.toDouble() / 1_000 / 1_000,
        )
    )
    println("-".repeat(110))

    // Bucket 1: AdapterRegistry HashMap lookup
    val kls = ProfileSyncStudent::class
    bucket("AdapterRegistry.get(kls)") {
        AdapterRegistry.get(kls)
    }

    // Bucket 2: Node allocation (cheap proxy)
    bucket("ProfileSyncStudent alloc") {
        ProfileSyncStudent(0, "")
    }

    // Bucket 3: InvokeCtx allocation
    val adapter = AdapterRegistry.get(kls)!!
    val student = ProfileSyncStudent(0, "X")
    val emptyCollectors: Array<ICollector<Any?>> = arrayOfNulls<ICollector<Any?>>(0) as Array<ICollector<Any?>>
    val emptyCtx = InvokeCtx(null, null, null, AncestorContext.EMPTY, emptyCollectors)
    bucket("InvokeCtx alloc") {
        InvokeCtx(null, null, null, AncestorContext.EMPTY, emptyCollectors)
    }

    // Bucket 4: adapter.invokeResolveMethod (direct call to resolveDisplayName)
    bucket("adapter.invokeResolveMethod") {
        runBlocking { adapter.invokeResolveMethod(student, 0, emptyCtx) }
    }

    // Bucket 5: adapter.writeField (direct assignment via when)
    bucket("adapter.writeField") {
        adapter.writeField(student, "displayName", "X")
    }

    // Bucket 6: adapter.collectObjectFields (returns emptyList for SyncStudent)
    bucket("adapter.collectObjectFields") {
        adapter.collectObjectFields(student)
    }

    // Bucket 7: coroutineScope + 1 async + awaitAll (1 task)
    bucket("coroutineScope{ 1 async }.awaitAll()") {
        runBlocking { coroutineScope { async { } }.let { } }
    }

    // Bucket 8: coroutineScope + 100 async + awaitAll (what runPhaseA actually does)
    bucket("coroutineScope{ 100 async }.awaitAll()") {
        runBlocking {
            coroutineScope {
                (0 until NODES).map { async { } }.awaitAll()
            }
        }
    }

    // Bucket 9: full executeResolve equivalent (without BFS bookkeeping)
    bucket("adapter.invokeResolveMethod + writeField") {
        runBlocking {
            val v = adapter.invokeResolveMethod(student, 0, emptyCtx)
            adapter.writeField(student, "displayName", v)
        }
    }

    // Bucket 10: coroutineScope{ 100 async with real work }.awaitAll()
    //   This is closer to what runPhaseA actually does (each async has work).
    bucket("coroutineScope{ 100 async{ adapter call } }.awaitAll()") {
        runBlocking {
            coroutineScope {
                (0 until NODES).map {
                    async {
                        val v = adapter.invokeResolveMethod(student, 0, emptyCtx)
                        adapter.writeField(student, "displayName", v)
                    }
                }.awaitAll()
            }
        }
    }

    // Bucket 11: just coroutineScope{} overhead with no async work
    bucket("coroutineScope{ } (empty, 1 call)") {
        runBlocking { coroutineScope { } }
    }

    // Bucket 12: a no-op runBlocking { } (cost of bridge)
    bucket("runBlocking{ } (empty)") {
        runBlocking { }
    }
}
