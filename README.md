# kotlin-resolve

> A progressive data-assembly framework for Kotlin — port of the core `pydantic-resolve`
> engine. Define business entities as plain Kotlin classes, declare relationships with
> `@Resolve` / `@Post`, and let the framework assemble your response tree with
> N+1-safe batching.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blueviolet.svg)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-orange.svg)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## Why

In most Kotlin web projects, data-assembly logic ends up scattered across
Repository / Service / Route layers:

```kotlin
suspend fun getSprints(): List<SprintResponse> {
    val sprints = sprintRepo.findAll()
    val tasks = taskRepo.findBySprintIds(sprints.map { it.id })
    val users = userRepo.findByIds(tasks.map { it.ownerId }.distinct())
    // ... manual mapping, N+1 risk, no stable home for business logic
}
```

`kotlin-resolve` gives that logic a stable home: the model itself.

## Quick start

### Install

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.25"  // optional but recommended
}

dependencies {
    implementation("com.tangkikodo:kotlin-resolve:0.1.0-SNAPSHOT")
    ksp("com.tangkikodo:kotlin-resolve-ksp:0.1.0-SNAPSHOT")  // zero-reflection codegen
}
```

### The 30-second example

```kotlin
data class UserView(val id: Int, val name: String)

data class TaskView(
    val id: Int,
    val title: String,
    val ownerId: Int,
    var owner: UserView? = null,        // populated by resolveOwner
) {
    @Resolve("owner")
    suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, UserView>) =
        loader.load(ownerId)
}

val tasks = listOf(TaskView(1, "Implement login", 10), TaskView(2, "Write tests", 10))

val resolver = Resolver(
    loaderFactory = mapOf(
        "user" to { DataLoader({ keys -> userDao.findByIds(keys) }, this) }
    ),
)

val resolved = resolver.resolve(tasks)   // one DB query, no N+1
```

### Composing multiple levels

Each `@Resolve` edge becomes one BFS level — stack them and the framework
batches each level independently. Below, `Task → User → Team` produces exactly
two DB queries regardless of list size:

```kotlin
data class TeamView(val id: Int, val name: String)

data class UserView(
    val id: Int,
    val name: String,
    val teamId: Int,
    var team: TeamView? = null,        // level 2
) {
    @Resolve("team")
    suspend fun resolveTeam(@LoaderDep("team") loader: DataLoader<Int, TeamView>) =
        loader.load(teamId)
}

data class TaskView(
    val id: Int,
    val title: String,
    val ownerId: Int,
    var owner: UserView? = null,        // level 1
) {
    @Resolve("owner")
    suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, UserView>) =
        loader.load(ownerId)
}

val resolver = Resolver(
    loaderFactory = mapOf(
        "user" to { DataLoader({ keys -> userDao.findByIds(keys) }, this) },
        "team" to { DataLoader({ keys -> teamDao.findByIds(keys) }, this) },
    ),
)

val resolved = resolver.resolve(tasks)
// resolved[0].owner?.team?.name   ←   2 queries total, no N+1 at either level
```

Run it: `examples/demo/src/main/kotlin/demo/twolevel/TwoLevelDemo.kt`

### Annotated parameters

| Annotation | Where | Purpose |
|---|---|---|
| `@Resolve("field")` | method | Load external data into `field` |
| `@Post("field")` | method | Compute derived `field` after children resolve |
| `@LoaderDep("name")` | param | Inject a registered `DataLoader` |
| `@CollectorParam("alias")` | param | Inject a `Collector` for child → parent aggregation |
| `@ExposeAs("alias")` | property | Expose value to descendants via `ancestorContext[alias]` |
| `@SendTo("alias")` | property | Push value up to the matching ancestor collector |

## How it works

| What you need | What you write | What the framework does |
|---|---|---|
| Load related data | `@Resolve` + `DataLoader.load` | Batches lookups across the tree |
| Compute derived fields | `@Post` | Runs after descendants resolved |
| Aggregate from children | `@SendTo` + `@CollectorParam` | Walks descendants, collects values |
| Broadcast to descendants | `@ExposeAs` + `ancestorContext` | Builds immutable per-level snapshot |

Execution is two-phase BFS:
1. **Phase A (top-down)**: `@Resolve` methods run level-by-level; concurrent calls
   within a level coalesce through `DataLoader`.
2. **Phase B (bottom-up)**: `@Post` methods run after all children resolved;
   `Collector` aggregates descendant values.

## Performance

Versus the original `pydantic-resolve` (Python 3.12, same hardware, 10 rounds):

| Case | Python | kotlin-resolve | Speedup |
|---|---:|---:|---:|
| `basic_resolve_sync` (100 nodes) | 0.80 ms | **0.24 ms** | 3.3× |
| `basic_resolve_async` (100 × 1ms) | 2.40 ms | 1.70 ms | 1.4× |
| `dataloader` (1000 tasks, 10ms batch) | 22.0 ms | 14.6 ms | 1.5× |
| `expose` (1000 nodes, 3 levels) | 15.2 ms | **3.6 ms** | 4.2× |
| `deep_nesting` (363 nodes) | 10.0 ms | 6.5 ms | 1.5× |
| `large_dataset_post` (2000 items) | 13.2 ms | **0.93 ms** | 14× |

KSP code generation eliminates reflection on the hot path; runtime falls back to
`kotlin-reflect` automatically when the plugin is not applied.

## Repository layout

```
kotlin-resolve/
├── src/main/kotlin/.../kotlinresolve/   # core runtime (Resolver, DataLoader, ...)
├── src/test/kotlin/.../kotlinresolve/   # 11 unit tests (all green)
├── ksp-processor/                       # KSP plugin generating zero-reflection adapters
├── benchmarks/                          # 6-case comparison vs pydantic-resolve
└── examples/demo/                       # standalone consumer with fake data layer
```

## Status

**Alpha** — core data assembly works end-to-end; missing ER Diagram, AutoLoad,
GraphQL/MCP, and ORM inspectors (all tracked in the roadmap).

## License

MIT — see [LICENSE](LICENSE).
