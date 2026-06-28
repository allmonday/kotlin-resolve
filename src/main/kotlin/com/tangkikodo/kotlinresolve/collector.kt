package com.tangkikodo.kotlinresolve

/**
 * Aggregates values from descendants into a buffer queryable by an ancestor `@Post`
 * method. Mirrors `pydantic_resolve.utils.collector.ICollector`.
 *
 * Use [Collector] for the default list-buffering behavior. Subclass to implement
 * sets, sums, etc.
 */
interface ICollector<T> {
    val alias: String
    fun add(value: T)
    fun values(): List<T>
}

/**
 * Default collector: buffers added values in order, returns them via [values].
 *
 * Set [flat] = true if descendants send lists that should be flattened into one list
 * rather than nested.
 */
class Collector<T>(
    override val alias: String,
    private val flat: Boolean = false,
) : ICollector<T> {
    private val buffer: MutableList<T> = mutableListOf()

    @Suppress("UNCHECKED_CAST")
    override fun add(value: T) {
        if (flat && value is List<*>) {
            buffer.addAll(value as List<T>)
        } else {
            buffer.add(value)
        }
    }

    override fun values(): List<T> = buffer.toList()
}
