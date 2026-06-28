package com.tangkikodo.kotlinresolve

/**
 * Mark a method as the resolver for [field]. The method may be `suspend` and may take
 * any of: a [DataLoader] (annotated [LoaderDep]), [Collector] (post only, [CollectorParam]),
 * `parent`, `context`, `ancestorContext`.
 *
 * Example:
 * ```
 * @Resolve("owner")
 * suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, UserView>) =
 *     loader.load(ownerId)
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
annotation class Resolve(val field: String)

/**
 * Mark a method to run after all descendant resolves complete. Return value is written
 * back to [field]. Good for counts, sums, formatting.
 */
@Target(AnnotationTarget.FUNCTION)
annotation class Post(val field: String)

/**
 * Identify which registered loader should be injected into a [DataLoader] parameter.
 * The [name] matches a key in `Resolver(loaderFactory = mapOf(name to ...))`.
 *
 * Mirrors Python's `def resolve_x(self, loader=Loader(user_loader))` where the function
 * reference itself was the identity. Kotlin annotations can't hold function references,
 * so we use an explicit string key.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class LoaderDep(val name: String)

/**
 * Mark a property whose value should be exposed to descendants under [alias].
 * Descendant resolve/post methods can read it via `ancestorContext[alias]`.
 */
@Target(AnnotationTarget.PROPERTY)
annotation class ExposeAs(val alias: String)

/**
 * Mark a property whose value should be sent to the [collector] on the nearest
 * ancestor that declares it. Use with [CollectorParam] on a `@Post` method.
 */
@Target(AnnotationTarget.PROPERTY)
annotation class SendTo(val collector: String)

/**
 * Inject a [Collector] into a `@Post` method parameter.
 * The collector aggregates values sent up by descendants via [SendTo].
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class CollectorParam(val alias: String, val flat: Boolean = false)
