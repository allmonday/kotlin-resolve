package com.tangkikodo.kotlinresolve.bench

import com.tangkikodo.kotlinresolve.Resolve
import com.tangkikodo.kotlinresolve.Resolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.system.measureNanoTime

/**
 * High-concurrency benchmark: N independent requests, each running one resolve().
 * Mirrors concurrency_bench.py.
 *
 * Runs twice:
 *  - Dispatchers.Default (multi-core, the normal Kotlin server setup)
 *  - Dispatchers.Unconfined (single-threaded, to match Python's asyncio model)
 */

class ConcurrencyAsyncStudent(val id: Int, val name: String) {
    var courses: List<String> = emptyList()

    @Resolve("courses")
    suspend fun resolveCourses(): List<String> {
        delay(1)
        return listOf("Math", "Science", "History")
    }
}

private fun makeStudents(): List<ConcurrencyAsyncStudent> =
    (0 until 100).map { ConcurrencyAsyncStudent(it, "S$it") }

private suspend fun singleRequest(): Long {
    val students = makeStudents()
    val t0 = System.nanoTime()
    Resolver().resolve(students)
    return (System.nanoTime() - t0) / 1_000_000
}

private suspend fun runConcurrent(n: Int, context: CoroutineContext): Pair<Double, List<Long>> {
    val latencies = arrayOfNulls<Long>(n)
    val elapsedNs = measureNanoTime {
        val scope = CoroutineScope(context)
        scope.async {
            (0 until n).map { idx ->
                async { latencies[idx] = singleRequest() }
            }.awaitAll()
        }.await()
    }
    return elapsedNs / 1_000_000.0 to latencies.map { it!! }
}

private fun report(n: Int, totalMs: Double, latencies: List<Long>) {
    val sorted = latencies.sorted()
    val p50 = sorted[sorted.size / 2]
    val p95 = sorted[min(sorted.size - 1, (sorted.size * 0.95).toInt())]
    val p99 = sorted[min(sorted.size - 1, (sorted.size * 0.99).toInt())]
    val mean = latencies.average()
    val throughput = n / (totalMs / 1000.0)
    println(
        "  N=${"%5d".format(n)}  total=${"%8.1f".format(totalMs)}ms  " +
            "throughput=${"%7.1f".format(throughput)} req/s  " +
            "latency mean=${"%6.1f".format(mean)}ms  p50=${"%6.1f".format(p50.toDouble())}  " +
            "p95=${"%6.1f".format(p95.toDouble())}  p99=${"%6.1f".format(p99.toDouble())}"
    )
}

fun main() = runBlocking {
    println("Kotlin coroutines concurrency benchmark")
    println("Workload: 100 AsyncStudents × 1ms IO each, per request")
    println("CPUs: ${Runtime.getRuntime().availableProcessors()}")
    println("-".repeat(100))

    val sizes = listOf(1, 10, 100, 500, 1000)

    println("\n[Dispatchers.Default — multi-core]")
    for (n in sizes) {
        if (n > 1) runConcurrent(min(10, n), Dispatchers.Default)
        val (totalMs, lats) = runConcurrent(n, Dispatchers.Default)
        report(n, totalMs, lats)
    }

    println("\n[Dispatchers.Unconfined — single-thread, matches Python asyncio]")
    for (n in sizes) {
        if (n > 1) runConcurrent(min(10, n), Dispatchers.Unconfined)
        val (totalMs, lats) = runConcurrent(n, Dispatchers.Unconfined)
        report(n, totalMs, lats)
    }
}
