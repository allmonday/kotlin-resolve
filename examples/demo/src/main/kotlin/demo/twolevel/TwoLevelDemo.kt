package demo.twolevel

import com.tangkikodo.kotlinresolve.DataLoader
import com.tangkikodo.kotlinresolve.LoaderDep
import com.tangkikodo.kotlinresolve.Resolve
import com.tangkikodo.kotlinresolve.Resolver
import com.tangkikodo.kotlinresolve.buildObject
import kotlinx.coroutines.runBlocking

/**
 * Two-level resolve chain: Task → User → Team.
 *
 * Two @Resolve edges compose into a BFS traversal:
 *   level 1 batches all owner loads   (1 user query)
 *   level 2 batches all team loads    (1 team query)
 * Run with:  ./gradlew :examples:demo:run -PmainClass=demo.TwoLevelDemoKt --args=""
 */

data class TeamView(val id: Int, val name: String)

data class UserView(
    val id: Int,
    val name: String,
    val teamId: Int,
    var team: TeamView? = null,        // level-2 resolve
) {
    @Resolve("team")
    suspend fun resolveTeam(@LoaderDep("team") loader: DataLoader<Int, TeamView>) =
        loader.load(teamId)
}

data class TaskView(
    val id: Int,
    val title: String,
    val ownerId: Int,
    var owner: UserView? = null,        // level-1 resolve
) {
    @Resolve("owner")
    suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, UserView>) =
        loader.load(ownerId)
}

// Fake data layer ----------------------------------------------------------

private val teams = listOf(
    TeamView(1, "Platform"),
    TeamView(2, "Growth"),
)

private val users = listOf(
    UserView(10, "Alice", 1),
    UserView(20, "Bob",   1),
    UserView(30, "Carol", 2),
)

private val tasks = listOf(
    TaskView(1, "Implement login",  10),
    TaskView(2, "Write tests",      10),
    TaskView(3, "Push to prod",     20),
    TaskView(4, "Write launch post", 30),
)

private var userBatchInvocations = 0
private var teamBatchInvocations = 0

fun main() = runBlocking {
    val resolver = Resolver(
        loaderFactory = mapOf(
            "user" to {
                DataLoader(
                    batchFn = { keys ->
                        userBatchInvocations++
                        println("[user loader] batch size=${keys.size} keys=$keys")
                        buildObject(users, keys) { it.id }
                    },
                    scope = this,
                )
            },
            "team" to {
                DataLoader(
                    batchFn = { keys ->
                        teamBatchInvocations++
                        println("[team loader] batch size=${keys.size} keys=$keys")
                        buildObject(teams, keys) { it.id }
                    },
                    scope = this,
                )
            },
        ),
    )

    val resolved = resolver.resolve(tasks)

    println()
    println("=== Resolved tree ===")
    for (task in resolved) {
        val owner = task.owner
        println("#${task.id} '${task.title}'")
        println("    └─ owner=${owner?.name}  team=${owner?.team?.name}")
    }
    println()
    println("=== N+1 safety ===")
    println("user loader batch invocations: $userBatchInvocations (expect 1)")
    println("team loader batch invocations: $teamBatchInvocations (expect 1)")
}
