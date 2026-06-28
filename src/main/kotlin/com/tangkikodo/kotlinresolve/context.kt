package com.tangkikodo.kotlinresolve

/**
 * Read-only snapshot of values exposed by ancestor nodes via [ExposeAs].
 *
 * Descendant resolve/post methods declare a parameter named `ancestorContext` and read
 * exposed values like `ancestorContext["sprintName"]`. A fresh snapshot is built each
 * level down as the Resolver walks the tree.
 */
class AncestorContext private constructor(
    private val backing: Map<String, Any?>,
) {
    operator fun get(key: String): Any? = backing[key]
    fun keys(): Set<String> = backing.keys
    fun asMap(): Map<String, Any?> = backing

    companion object {
        val EMPTY: AncestorContext = AncestorContext(emptyMap())

        /**
         * Build the snapshot a child should see: parent's snapshot plus [node]'s
         * [ExposeAs] properties (using their declared alias).
         */
        fun forChildOf(
            parent: AncestorContext,
            exposeMap: Map<String, String>,
            readField: (String) -> Any?,
        ): AncestorContext {
            if (exposeMap.isEmpty()) return parent
            val merged = LinkedHashMap(parent.backing)
            for ((field, alias) in exposeMap) {
                merged[alias] = readField(field)
            }
            return AncestorContext(merged)
        }
    }
}
