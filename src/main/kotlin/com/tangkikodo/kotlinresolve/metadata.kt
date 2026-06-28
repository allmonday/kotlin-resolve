package com.tangkikodo.kotlinresolve

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

/**
 * Scanned metadata for one KotlinResolve model class.
 *
 * All reflection artifacts are pre-fetched and cached here so the BFS hot path
 * never touches `kotlin.reflect` lookups — only direct `Method.invoke` and
 * `KMutableProperty1.set`.
 */
data class ResolveMeta(
    val resolveMethods: List<ResolveMethodMeta>,
    val postMethods: List<PostMethodMeta>,
    val objectFields: List<ObjectFieldMeta>,
    val exposeMap: Map<String, String>,
    val collectMap: Map<String, List<String>>,
    /** Field name → writable property reference. Empty if no resolvable fields. */
    val writeProperties: Map<String, KMutableProperty1<Any, Any?>>,
    /** Field name → readable property reference. Used by ExposeAs / SendTo. */
    val readProperties: Map<String, KProperty1<Any, *>>,
) {
    val hasConfig: Boolean
        get() = resolveMethods.isNotEmpty() ||
            postMethods.isNotEmpty() ||
            exposeMap.isNotEmpty() ||
            collectMap.isNotEmpty()
}

data class ObjectFieldMeta(
    val name: String,
    val getter: KProperty1<Any, *>,
)

data class ResolveMethodMeta(
    val field: String,
    val writeProperty: KMutableProperty1<Any, Any?>,
    val method: Method,
    val isSuspend: Boolean,
    val plan: ArgPlan,
    /** Null if the method takes no @LoaderDep parameter. */
    val loaderName: String?,
)

data class PostMethodMeta(
    val field: String,
    val writeProperty: KMutableProperty1<Any, Any?>,
    val method: Method,
    val isSuspend: Boolean,
    val plan: ArgPlan,
    val collectors: List<CollectorParamMeta>,
    val loaderName: String?,
)

data class CollectorParamMeta(
    val alias: String,
    val flat: Boolean,
)

/**
 * Precomputed "fill this array slot from this source" plan. Built once per method
 * at scan time, executed per invocation. Replaces the old per-call LinkedHashMap +
 * valueParameters iteration.
 */
class ArgPlan private constructor(
    private val slots: Array<Slot>,
) {
    private sealed class Slot {
        object Loader : Slot()
        object Parent : Slot()
        object Context : Slot()
        object AncestorContext : Slot()
        class Collector(val index: Int) : Slot()
    }

    fun build(
        loader: Any?,
        parent: Any?,
        context: Any?,
        ancestorContext: Any?,
        collectors: Array<ICollector<Any?>>,
    ): Array<Any?> {
        val args = arrayOfNulls<Any?>(slots.size)
        for (i in slots.indices) {
            args[i] = when (val s = slots[i]) {
                Slot.Loader -> loader
                Slot.Parent -> parent
                Slot.Context -> context
                Slot.AncestorContext -> ancestorContext
                is Slot.Collector -> collectors[s.index]
            }
        }
        return args
    }

    companion object {
        /**
         * Build a plan that places parameters in declared order. Slot count = number of
         * value parameters. The receiver is supplied separately to Method.invoke.
         */
        fun from(
            fn: KFunction<*>,
            loaderParam: KParameter?,
            parentParam: KParameter?,
            contextParam: KParameter?,
            ancestorContextParam: KParameter?,
            collectorParams: List<Pair<KParameter, Int>>,
        ): ArgPlan {
            val valueParams = fn.valueParameters
            val slots = Array(valueParams.size) { i ->
                val p = valueParams[i]
                when {
                    p === loaderParam -> Slot.Loader
                    p === parentParam -> Slot.Parent
                    p === contextParam -> Slot.Context
                    p === ancestorContextParam -> Slot.AncestorContext
                    else -> {
                        val cidx = collectorParams.firstOrNull { it.first === p }?.second
                        if (cidx != null) Slot.Collector(cidx)
                        else error("Cannot supply value for parameter '${p.name}' of ${fn.name}")
                    }
                }
            }
            return ArgPlan(slots)
        }
    }
}

private val metadataCache = ConcurrentHashMap<KClass<*>, ResolveMeta>()

fun scan(kls: KClass<*>): ResolveMeta =
    metadataCache.getOrPut(kls) { scanUncached(kls) }

@Suppress("UNCHECKED_CAST")
private fun scanUncached(kls: KClass<*>): ResolveMeta {
    // Build property lookup tables once. Casts to Any-receiver property types are safe
    // because we only ever invoke them with instances of `kls`.
    val writeProperties = mutableMapOf<String, KMutableProperty1<Any, Any?>>()
    val readProperties = mutableMapOf<String, KProperty1<Any, *>>()
    for (prop in kls.memberProperties) {
        readProperties[prop.name] = prop as KProperty1<Any, *>
        if (prop is KMutableProperty1<*, *>) {
            writeProperties[prop.name] = prop as KMutableProperty1<Any, Any?>
        }
    }

    val resolveMethods = mutableListOf<ResolveMethodMeta>()
    val postMethods = mutableListOf<PostMethodMeta>()
    val objectFields = mutableListOf<ObjectFieldMeta>()
    val exposeMap = mutableMapOf<String, String>()
    val collectMap = mutableMapOf<String, MutableList<String>>()

    for (function in kls.functions) {
        if (!function.isCallableMethod()) continue
        function.findAnnotation<Resolve>()?.let { ann ->
            val loader = function.findDataLoaderParam()
            val plan = ArgPlan.from(
                fn = function,
                loaderParam = loader?.param,
                parentParam = function.findParam("parent"),
                contextParam = function.findParam("context"),
                ancestorContextParam = function.findParam("ancestorContext"),
                collectorParams = emptyList(),
            )
            function.isAccessible = true
            val method = function.javaMethod
                ?: error("@Resolve method ${function.name} has no Java Method")
            method.isAccessible = true
            val writeProp = writeProperties[ann.field]
                ?: error("@Resolve target '${ann.field}' on ${kls.simpleName} is missing or not var")
            resolveMethods += ResolveMethodMeta(
                field = ann.field,
                writeProperty = writeProp,
                method = method,
                isSuspend = function.isSuspend,
                plan = plan,
                loaderName = loader?.name,
            )
        }
        function.findAnnotation<Post>()?.let { ann ->
            val loader = function.findDataLoaderParam()
            val collectorParams = mutableListOf<Pair<KParameter, Int>>()
            val collectorMetas = mutableListOf<CollectorParamMeta>()
            for (p in function.parameters) {
                val cp = p.findAnnotation<CollectorParam>() ?: continue
                collectorParams += p to collectorMetas.size
                collectorMetas += CollectorParamMeta(alias = cp.alias, flat = cp.flat)
            }
            val plan = ArgPlan.from(
                fn = function,
                loaderParam = loader?.param,
                parentParam = function.findParam("parent"),
                contextParam = function.findParam("context"),
                ancestorContextParam = function.findParam("ancestorContext"),
                collectorParams = collectorParams,
            )
            function.isAccessible = true
            val method = function.javaMethod
                ?: error("@Post method ${function.name} has no Java Method")
            method.isAccessible = true
            val writeProp = writeProperties[ann.field]
                ?: error("@Post target '${ann.field}' on ${kls.simpleName} is missing or not var")
            postMethods += PostMethodMeta(
                field = ann.field,
                writeProperty = writeProp,
                method = method,
                isSuspend = function.isSuspend,
                plan = plan,
                collectors = collectorMetas,
                loaderName = loader?.name,
            )
        }
    }

    for (prop in kls.memberProperties) {
        prop.findAnnotation<ExposeAs>()?.let { exposeMap[prop.name] = it.alias }
        prop.findAnnotation<SendTo>()?.let {
            collectMap.getOrPut(prop.name) { mutableListOf() } += it.collector
        }
        if (isResolvableModel(prop)) {
            objectFields += ObjectFieldMeta(prop.name, prop as KProperty1<Any, *>)
        }
    }

    return ResolveMeta(
        resolveMethods = resolveMethods,
        postMethods = postMethods,
        objectFields = objectFields,
        exposeMap = exposeMap,
        collectMap = collectMap,
        writeProperties = writeProperties,
        readProperties = readProperties,
    )
}

private fun isResolvableModel(prop: KProperty1<*, *>): Boolean =
    coreUserTypes(prop.returnType).isNotEmpty()

private fun coreUserTypes(type: kotlin.reflect.KType): List<KClass<*>> {
    val result = mutableListOf<KClass<*>>()
    val queue = ArrayDeque<kotlin.reflect.KType>()
    queue.add(type)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        val erased = cur.jvmErasure
        val pkg = erased.java.`package`?.name ?: ""
        when {
            pkg.startsWith("kotlin") || pkg.startsWith("java") -> {
                for (arg in cur.arguments) {
                    val proj = arg.type ?: continue
                    queue.add(proj)
                }
            }
            else -> result += erased
        }
    }
    return result.distinct()
}

private fun KFunction<*>.isCallableMethod(): Boolean {
    val name = this.name
    if (name.startsWith("get") && name.length > 3 && name[3].isUpperCase()) return false
    if (name.startsWith("set") && name.length > 3 && name[3].isUpperCase()) return false
    if (name.startsWith("is") && name.length > 2 && name[2].isUpperCase()) return false
    return true
}

private data class LoaderParamInfo(val param: KParameter, val name: String)

private fun KFunction<*>.findDataLoaderParam(): LoaderParamInfo? =
    parameters.mapNotNull { p ->
        val erased = p.type.jvmErasure
        if (erased.java != DataLoader::class.java) return@mapNotNull null
        val name = p.findAnnotation<LoaderDep>()?.name
            ?: error("DataLoader param '${p.name}' on ${this.name} must be annotated @LoaderDep(\"...\")")
        LoaderParamInfo(p, name)
    }.firstOrNull()

private fun KFunction<*>.findParam(name: String): KParameter? =
    parameters.firstOrNull { it.name == name }
