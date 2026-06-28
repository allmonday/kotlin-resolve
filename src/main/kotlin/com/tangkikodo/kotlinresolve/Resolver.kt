package com.tangkikodo.kotlinresolve

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KProperty1

/**
 * Sentinel returned by suspend functions compiled to JVM when they actually suspended
 * instead of returning a value synchronously. Kotlin marks the property `internal`,
 * but the JVM exposes the synthetic getter `getCOROUTINE_SUSPENDED()` as a public
 * static method, so we invoke it reflectively.
 *
 * Only used on the reflection fallback path; KSP-generated adapters call suspend
 * methods directly and need no marker.
 */
private val COROUTINE_SUSPENDED_MARKER: Any? by lazy {
    val cls = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt")
    val getter = cls.getDeclaredMethod("getCOROUTINE_SUSPENDED")
    getter.isAccessible = true
    getter.invoke(null)
}

/**
 * Walks a tree of KotlinResolve model objects and executes:
 *  - **Phase A (top-down):** [Resolve] methods run level-by-level; concurrent calls within
 *    a level are coalesced by [DataLoader].
 *  - **Phase B (bottom-up):** [Post] methods run after children are fully resolved, with
 *    [Collector] parameters receiving aggregated descendant values.
 *
 * When the `kotlin-resolve-ksp` processor is applied to the user's compilation, every
 * node is dispatched through a generated [KotlinResolveAdapter] (zero reflection).
 * Otherwise the Resolver builds a runtime metadata cache via `kotlin-reflect` and
 * invokes methods via `Method.invoke` — slower, but correct.
 *
 * One Resolver instance per [resolve] call is the recommended pattern. Concurrent
 * [resolve] invocations on the same instance are NOT supported — per-call state lives
 * on `this`. Mirrors `pydantic_resolve.resolver.Resolver` lifecycle.
 */
class Resolver(
    private val loaderFactory: Map<String, suspend CoroutineScope.() -> DataLoader<*, *>> = emptyMap(),
    private val context: Map<String, Any?>? = null,
) {
    suspend fun <T : Any> resolve(root: T): T = coroutineScope {
        val resolveCallScope = this
        val loaderInstances = HashMap<String, DataLoader<*, *>>()

        suspend fun getLoader(name: String): DataLoader<*, *> {
            loaderInstances[name]?.let { return it }
            val factory = loaderFactory[name]
                ?: error("No loader registered for name '$name'. Known: ${loaderFactory.keys}")
            val instance = factory(resolveCallScope)
            loaderInstances[name] = instance
            return instance
        }

        val rootItems: List<Any> = if (root is List<*>) root.filterNotNull() else listOf(root)
        if (rootItems.isEmpty()) return@coroutineScope root

        val levels = mutableListOf<List<Node>>()
        levels += rootItems.map { item -> makeNode(item, parent = null, ancestorContext = AncestorContext.EMPTY) }

        runPhaseA(levels, ::getLoader)
        runPhaseB(levels, ::getLoader)

        root
    }

    // ---- Node construction: prefer KSP adapter, fall back to reflective scan ---

    private fun makeNode(
        instance: Any,
        parent: Node?,
        ancestorContext: AncestorContext,
    ): Node {
        val kls = instance::class
        val adapter = AdapterRegistry.get(kls)
        val meta = adapter?.meta ?: scan(kls)
        return Node(instance, parent, ancestorContext, meta, adapter)
    }

    // ---- Phase A: top-down resolve ---------------------------------------

    private suspend fun runPhaseA(
        levels: MutableList<List<Node>>,
        getLoader: suspend (String) -> DataLoader<*, *>,
    ) {
        while (true) {
            val current = levels.last()

            val jobs = ArrayList<PhaseAJob>(current.size * 2)
            var needsConcurrent = false
            for (node in current) {
                val rms = node.meta.resolveMethods
                for (idx in rms.indices) {
                    val rm = rms[idx]
                    // Suspend methods may do their own async work (delay, HTTP, etc.) —
                    // they MUST run concurrently or they'll serialize at await time.
                    // Loader-using methods need concurrency for DataLoader batching.
                    if (rm.isSuspend || rm.loaderName != null) {
                        needsConcurrent = true
                    }
                    val loader: DataLoader<*, *>? =
                        if (rm.loaderName != null) getLoader(rm.loaderName) else null
                    jobs += PhaseAJob(node, idx, rm, loader)
                }
            }

            // Fast path: when no job is suspend or loader-using, run sequentially and
            // skip coroutineScope{async} scheduling entirely.
            if (needsConcurrent) {
                coroutineScope {
                    jobs.map { job -> async { executeResolve(job) } }.awaitAll()
                }
            } else {
                for (job in jobs) executeResolve(job)
            }

            val nextLevel = ArrayList<Node>(current.size * 2)
            for (node in current) {
                val meta = node.meta
                // Inline AncestorContext.forChildOf to avoid lambda allocation when
                // exposeMap is empty (the common case for leaf-level classes).
                val childContext = if (meta.exposeMap.isEmpty()) {
                    node.ancestorContext
                } else {
                    AncestorContext.forChildOf(
                        parent = node.ancestorContext,
                        exposeMap = meta.exposeMap,
                        readField = node::readField,
                    )
                }
                if (node.adapter != null) {
                    @Suppress("UNCHECKED_CAST")
                    val adapter = node.adapter as KotlinResolveAdapter<Any>
                    for ((_, value) in adapter.collectObjectFields(node.instance)) {
                        wrapChildren(value, node, childContext, nextLevel, ::makeNode)
                    }
                } else {
                    for (of in meta.objectFields) {
                        @Suppress("UNCHECKED_CAST")
                        val value = (of.getter as KProperty1<Any, *>).get(node.instance)
                        wrapChildren(value, node, childContext, nextLevel, ::makeNode)
                    }
                }
            }
            if (nextLevel.isEmpty()) break
            levels += nextLevel
        }
    }

    private suspend fun executeResolve(job: PhaseAJob) {
        val node = job.node
        val loader = job.loader
        val value: Any? = if (node.adapter != null) {
            @Suppress("UNCHECKED_CAST")
            val adapter = node.adapter as KotlinResolveAdapter<Any>
            val ctx = InvokeCtx(
                loader = loader,
                parent = node.parent?.instance,
                context = context,
                ancestorContext = node.ancestorContext,
                collectors = EMPTY_COLLECTORS,
            )
            adapter.invokeResolveMethod(node.instance, job.methodIndex, ctx)
        } else {
            val rm = job.rm!!
            invokePlanned(
                method = rm.method,
                isSuspend = rm.isSuspend,
                plan = rm.plan,
                receiver = node.instance,
                loader = loader,
                parent = node.parent?.instance,
                ancestorContext = node.ancestorContext,
                collectors = EMPTY_COLLECTORS,
            )
        } ?: return

        if (node.adapter != null) {
            @Suppress("UNCHECKED_CAST")
            (node.adapter as KotlinResolveAdapter<Any>).writeField(
                node.instance, node.meta.resolveMethods[job.methodIndex].field, value,
            )
        } else {
            job.rm!!.writeProperty.set(node.instance, value)
        }
    }

    // ---- Phase B: bottom-up post + collector -----------------------------

    private suspend fun runPhaseB(
        levels: MutableList<List<Node>>,
        getLoader: suspend (String) -> DataLoader<*, *>,
    ) {
        // Fast path: if no node in the tree declares @Post or @SendTo, Phase B has nothing
        // to do. Saves ~36µs/100 nodes of coroutine scheduling for resolve-only trees.
        var anyPost = false
        for (level in levels) {
            for (node in level) {
                if (node.meta.postMethods.isNotEmpty() || node.meta.collectMap.isNotEmpty()) {
                    anyPost = true
                    break
                }
            }
            if (anyPost) break
        }
        if (!anyPost) return

        for (level in levels) {
            for (node in level) {
                val posts = node.meta.postMethods
                if (posts.isEmpty() || posts.all { it.collectors.isEmpty() }) continue
                val buffers = ArrayList<CollectorBuffer>(4)
                for (pm in posts) {
                    for (c in pm.collectors) {
                        @Suppress("UNCHECKED_CAST")
                        buffers += CollectorBuffer(
                            alias = c.alias,
                            collector = Collector<Any?>(c.alias, c.flat) as ICollector<Any?>,
                        )
                    }
                }
                node.collectorBuffers = buffers
            }
        }

        // Detect whether any @Post method needs a loader; if not, skip coroutineScope{}.
        var needsConcurrent = false
        outer@ for (level in levels) {
            for (node in level) {
                for (pm in node.meta.postMethods) {
                    if (pm.loaderName != null) {
                        needsConcurrent = true
                        break@outer
                    }
                }
            }
        }

        for (depth in levels.lastIndex downTo 0) {
            val current = levels[depth]
            if (needsConcurrent) {
                coroutineScope {
                    current.map { node -> async { executePosts(node, getLoader) } }.awaitAll()
                }
            } else {
                for (node in current) executePosts(node, getLoader)
            }
            for (node in current) pushSendTo(node)
        }
    }

    private suspend fun executePosts(
        node: Node,
        getLoader: suspend (String) -> DataLoader<*, *>,
    ) {
        val meta = node.meta
        val buffers = node.collectorBuffers
        for (idx in meta.postMethods.indices) {
            val pm = meta.postMethods[idx]
            val loader: DataLoader<*, *>? =
                if (pm.loaderName != null) getLoader(pm.loaderName) else null
            val collectorArray: Array<ICollector<Any?>> =
                if (pm.collectors.isEmpty()) EMPTY_COLLECTORS
                else Array(pm.collectors.size) { i ->
                    val wanted = pm.collectors[i]
                    val found = buffers?.firstOrNull { it.alias == wanted.alias }
                        ?: error("missing collector buffer for ${wanted.alias}")
                    found.collector
                }

            val value: Any? = if (node.adapter != null) {
                @Suppress("UNCHECKED_CAST")
                val adapter = node.adapter as KotlinResolveAdapter<Any>
                val ctx = InvokeCtx(
                    loader = loader,
                    parent = node.parent?.instance,
                    context = context,
                    ancestorContext = node.ancestorContext,
                    collectors = collectorArray,
                )
                adapter.invokePostMethod(node.instance, idx, ctx)
            } else {
                invokePlanned(
                    method = pm.method,
                    isSuspend = pm.isSuspend,
                    plan = pm.plan,
                    receiver = node.instance,
                    loader = loader,
                    parent = node.parent?.instance,
                    ancestorContext = node.ancestorContext,
                    collectors = collectorArray,
                )
            } ?: continue

            if (node.adapter != null) {
                @Suppress("UNCHECKED_CAST")
                (node.adapter as KotlinResolveAdapter<Any>).writeField(node.instance, pm.field, value)
            } else {
                pm.writeProperty.set(node.instance, value)
            }
        }
    }

    private fun pushSendTo(node: Node) {
        val collectMap = node.meta.collectMap
        if (collectMap.isEmpty()) return
        for ((fieldName, aliases) in collectMap) {
            val fieldValue = node.readField(fieldName) ?: continue
            for (alias in aliases) {
                pushValueToAncestor(node.parent, alias, fieldValue)
            }
        }
    }

    private fun pushValueToAncestor(startParent: Node?, alias: String, value: Any?) {
        var cursor = startParent
        while (cursor != null) {
            val buffers = cursor.collectorBuffers
            if (buffers != null) {
                val match = buffers.firstOrNull { it.alias == alias }
                if (match != null) {
                    match.collector.add(value)
                    return
                }
            }
            cursor = cursor.parent
        }
    }

    // ---- Reflection fallback (only hit when adapter is null) --------------

    @Suppress("UNCHECKED_CAST")
    private suspend fun invokePlanned(
        method: java.lang.reflect.Method,
        isSuspend: Boolean,
        plan: ArgPlan,
        receiver: Any,
        loader: DataLoader<*, *>?,
        parent: Any?,
        ancestorContext: AncestorContext,
        collectors: Array<ICollector<Any?>>,
    ): Any? {
        val args = plan.build(
            loader = loader,
            parent = parent,
            context = context,
            ancestorContext = ancestorContext,
            collectors = collectors,
        )
        return if (!isSuspend) {
            method.invoke(receiver, *args)
        } else {
            suspendCoroutine<Any?> { cont ->
                try {
                    val result = method.invoke(receiver, *args, cont)
                    if (result !== COROUTINE_SUSPENDED_MARKER) {
                        cont.resume(result)
                    }
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            }
        }
    }
}

// ---- Internals --------------------------------------------------------

private class Node(
    val instance: Any,
    val parent: Node?,
    val ancestorContext: AncestorContext,
    val meta: ResolveMeta,
    /** Null when no KSP-generated adapter exists for [instance]'s class. */
    val adapter: KotlinResolveAdapter<*>?,
) {
    var collectorBuffers: List<CollectorBuffer>? = null

    /** Unified field read: adapter if available, reflective meta otherwise. */
    fun readField(fieldName: String): Any? {
        if (adapter != null) {
            @Suppress("UNCHECKED_CAST")
            return (adapter as KotlinResolveAdapter<Any>).readField(instance, fieldName)
        }
        val getter = meta.readProperties[fieldName] ?: return null
        return (getter as KProperty1<Any, *>).get(instance)
    }
}

private class PhaseAJob(
    val node: Node,
    val methodIndex: Int,
    /** Only used on the reflection fallback path. */
    val rm: ResolveMethodMeta?,
    val loader: DataLoader<*, *>?,
)

private class CollectorBuffer(
    val alias: String,
    val collector: ICollector<Any?>,
)

private val EMPTY_COLLECTORS: Array<ICollector<Any?>> = arrayOfNulls<ICollector<Any?>>(0) as Array<ICollector<Any?>>

private fun wrapChildren(
    value: Any?,
    parent: Node,
    childContext: AncestorContext,
    out: MutableList<Node>,
    makeNode: (Any, Node?, AncestorContext) -> Node,
) {
    if (value == null) return
    when (value) {
        is List<*> -> {
            for (item in value) {
                if (item == null) continue
                if (!isResolvableInstance(item)) continue
                out += makeNode(item, parent, childContext)
            }
        }
        else -> {
            if (isResolvableInstance(value)) {
                out += makeNode(value, parent, childContext)
            }
        }
    }
}

private fun isResolvableInstance(instance: Any): Boolean {
    val pkg = instance::class.java.`package`?.name ?: return false
    return !pkg.startsWith("kotlin") && !pkg.startsWith("java")
}
