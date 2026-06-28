package com.tangkikodo.kotlinresolve

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * Batch loader. Equivalent of aiodataloader.DataLoader.
 *
 * All [load] calls issued from coroutines running concurrently within the same [scope]
 * (typically: a single level of the Resolver's BFS traversal) are coalesced into one
 * invocation of [batchFn]. The next level produces a fresh batch.
 *
 * Why this works on the JVM despite no "event loop tick": the Resolver runs each level
 * inside `coroutineScope { ... awaitAll() }`. Each `load(key)` enqueues the key and
 * launches a [dispatch] coroutine. That dispatch coroutine first `yield()`s — letting
 * all sibling `async { load(...) }` coroutines run to their own load() calls — and only
 * then snapshots the queue and invokes [batchFn]. The `awaitAll` is the natural barrier
 * that ensures all loads for the level are in flight before any child collection starts.
 */
class DataLoader<K, V>(
    private val batchFn: suspend (List<K>) -> List<V>,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val pending = linkedMapOf<K, CompletableDeferred<V>>()
    private var dispatchScheduled = false

    /**
     * Request [key]. Returns a value that will complete once the current batch flushes.
     * Calling with the same key more than once before a flush deduplicates — both
     * callers await the same deferred.
     */
    suspend fun load(key: K): V {
        val deferred = mutex.withLock {
            pending.getOrPut(key) { CompletableDeferred() }.also {
                if (!dispatchScheduled) {
                    dispatchScheduled = true
                    scope.launch { dispatch() }
                }
            }
        }
        return deferred.await()
    }

    private suspend fun dispatch() {
        // Give sibling coroutines a chance to enqueue their keys before we snapshot.
        yield()
        val snapshot: List<Pair<K, CompletableDeferred<V>>>
        mutex.withLock {
            snapshot = pending.entries.map { it.key to it.value }
            pending.clear()
            dispatchScheduled = false
        }
        if (snapshot.isEmpty()) return
        val keys = snapshot.map { it.first }
        val results = batchFn(keys)
        require(results.size == keys.size) {
            "batchFn returned ${results.size} results for ${keys.size} keys"
        }
        snapshot.zip(results).forEach { (kv, v) -> kv.second.complete(v) }
    }
}

/**
 * Group [items] into a list of lists keyed by [keys], using [getPk] to find each item's key.
 * The returned list aligns positionally with [keys] — use for one-to-many loaders.
 *
 * Mirrors pydantic_resolve.utils.dataloader.build_list.
 */
fun <T, K> buildList(
    items: List<T>,
    keys: List<K>,
    getPk: (T) -> K,
): List<List<T>> {
    val grouped = LinkedHashMap<K, MutableList<T>>(keys.size)
    for (key in keys) grouped[key] = mutableListOf()
    for (item in items) grouped[getPk(item)]?.add(item)
    return keys.map { grouped[it] ?: emptyList() }
}

/**
 * Index [items] by [keys] via [getPk]. Returns one item (or null) per key.
 *
 * Mirrors pydantic_resolve.utils.dataloader.build_object.
 */
fun <T, K> buildObject(
    items: List<T>,
    keys: List<K>,
    getPk: (T) -> K,
): List<T?> {
    val indexed = HashMap<K, T>(items.size)
    for (item in items) {
        val key = getPk(item)
        if (key !in indexed) indexed[key] = item
    }
    return keys.map { indexed[it] }
}
