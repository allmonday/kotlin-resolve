package demo

import com.tangkikodo.kotlinresolve.Collector
import com.tangkikodo.kotlinresolve.CollectorParam
import com.tangkikodo.kotlinresolve.DataLoader
import com.tangkikodo.kotlinresolve.ExposeAs
import com.tangkikodo.kotlinresolve.LoaderDep
import com.tangkikodo.kotlinresolve.Post
import com.tangkikodo.kotlinresolve.Resolve
import com.tangkikodo.kotlinresolve.Resolver
import com.tangkikodo.kotlinresolve.SendTo
import com.tangkikodo.kotlinresolve.buildList
import com.tangkikodo.kotlinresolve.buildObject
import kotlinx.coroutines.runBlocking

/**
 * Standalone consumer of com.tangkikodo:kotlin-resolve, fetched from local Maven.
 * Replicates the README example: Sprint → Task → Owner with derived counts
 * and contributor aggregation.
 */

data class UserView(val id: Int, val name: String)

data class TaskView(
    val id: Int,
    val title: String,
    val ownerId: Int,
    @SendTo("contributors") var owner: UserView? = null,
) {
    @Resolve("owner")
    suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, UserView>) =
        loader.load(ownerId)
}

data class SprintView(
    val id: Int,
    @ExposeAs("sprintName") val name: String,
    var tasks: List<TaskView> = emptyList(),
    var taskCount: Int = 0,
    var contributorNames: List<String> = emptyList(),
) {
    @Resolve("tasks")
    suspend fun resolveTasks(@LoaderDep("task") loader: DataLoader<Int, List<TaskView>>) =
        loader.load(id)

    @Post("taskCount")
    fun postTaskCount(): Int = tasks.size

    @Post("contributorNames")
    fun postContributorNames(
        @CollectorParam("contributors") collector: Collector<UserView>,
    ): List<String> = collector.values().map { it.name }.distinct().sorted()
}

// Fake data layer (replace with DB/HTTP calls in real usage).

private val users = listOf(
    UserView(1, "Alice"),
    UserView(2, "Bob"),
    UserView(3, "Carol"),
)

private val tasksBySprint: Map<Int, List<TaskView>> = mapOf(
    1 to listOf(
        TaskView(101, "Implement login", 1),
        TaskView(102, "Write tests", 2),
        TaskView(103, "Add docs", 1),
    ),
    2 to listOf(
        TaskView(201, "Ship release", 3),
        TaskView(202, "Plan next sprint", 1),
    ),
)

private var userBatchInvocations = 0
private var taskBatchInvocations = 0

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
            "task" to {
                DataLoader(
                    batchFn = { keys ->
                        taskBatchInvocations++
                        println("[task loader] batch size=${keys.size} keys=$keys")
                        buildList(tasksBySprint.values.flatten(), keys) { t ->
                            tasksBySprint.entries.first { (_, list) -> t in list }.key
                        }
                    },
                    scope = this,
                )
            },
        ),
    )

    val sprints = listOf(SprintView(1, "Sprint 1"), SprintView(2, "Sprint 2"))
    val resolved = resolver.resolve(sprints)

    println()
    println("=== Resolved output ===")
    for (sprint in resolved) {
        println("Sprint #${sprint.id} (${sprint.name})")
        println("  taskCount=${sprint.taskCount}")
        println("  contributorNames=${sprint.contributorNames}")
        for (task in sprint.tasks) {
            println("    - #${task.id} '${task.title}' owner=${task.owner?.name}")
        }
    }
    println()
    println("=== N+1 safety ===")
    println("user loader batch invocations: $userBatchInvocations (expect 1)")
    println("task loader batch invocations: $taskBatchInvocations (expect 1)")
}
