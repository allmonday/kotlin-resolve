package com.tangkikodo.kotlinresolve

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * End-to-end mirroring of pydantic-resolve README Steps 1–4:
 * Sprint → Tasks → Owner, with post_task_count and post_contributor_names.
 */
class EndToEndTest {

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
        var statusLine: String = "",
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

        @Post("statusLine")
        fun postStatusLine(ancestorContext: AncestorContext): String {
            // Root has no ancestor context values — defensive.
            val tag = ancestorContext["sprintName"] ?: name
            return "$tag: ${tasks.size} tasks"
        }
    }

    @Test
    fun `end to end sprint task owner`() = runBlocking {
        val users = listOf(
            UserView(1, "Alice"),
            UserView(2, "Bob"),
            UserView(3, "Carol"),
        )
        val tasksBySprint: Map<Int, List<TaskView>> = mapOf(
            1 to listOf(
                TaskView(101, "Implement login", 1),
                TaskView(102, "Write tests", 2),
                TaskView(103, "Add docs", 1),
            ),
            2 to listOf(TaskView(201, "Ship it", 3)),
        )

        var userBatchCount = 0
        var taskBatchCount = 0

        val resolver = Resolver(
            loaderFactory = mapOf(
                "user" to {
                    DataLoader(
                        { keys ->
                            userBatchCount++
                            buildObject(users, keys) { it.id }
                        },
                        this,
                    )
                },
                "task" to {
                    DataLoader(
                        { keys ->
                            taskBatchCount++
                            buildList(tasksBySprint.values.flatten(), keys) { t ->
                                tasksBySprint.entries.first { (_, list) -> t in list }.key
                            }
                        },
                        this,
                    )
                },
            )
        )

        val sprints = listOf(SprintView(1, "Sprint 1"), SprintView(2, "Sprint 2"))
        val out = resolver.resolve(sprints)

        // Resolve fields populated
        assertEquals(3, out[0].tasks.size)
        assertEquals(1, out[1].tasks.size)
        assertEquals("Alice", out[0].tasks[0].owner?.name)

        // Post-derived fields
        assertEquals(3, out[0].taskCount)
        assertEquals(1, out[1].taskCount)
        assertEquals(listOf("Alice", "Bob"), out[0].contributorNames)
        assertEquals(listOf("Carol"), out[1].contributorNames)

        // Ancestor context broadcast (root is the sprint itself; tag falls back to its own name)
        assertEquals("Sprint 1: 3 tasks", out[0].statusLine)
        assertEquals("Sprint 2: 1 tasks", out[1].statusLine)

        // N+1 safe: one user batch, one task batch across both sprints
        assertEquals(1, userBatchCount, "user loader: N+1 safe")
        assertEquals(1, taskBatchCount, "task loader: N+1 safe")
    }
}
