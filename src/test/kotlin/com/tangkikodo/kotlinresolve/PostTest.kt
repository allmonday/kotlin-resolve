package com.tangkikodo.kotlinresolve

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PostTest {

    data class UserView(val id: Int, val name: String)

    data class TaskView(
        val id: Int,
        val title: String,
        val ownerId: Int,
        var owner: UserView? = null,
    ) {
        @Resolve("owner")
        suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, UserView>) =
            loader.load(ownerId)
    }

    data class SprintView(
        val id: Int,
        val name: String,
        var tasks: List<TaskView> = emptyList(),
        var taskCount: Int = 0,
    ) {
        @Resolve("tasks")
        suspend fun resolveTasks(@LoaderDep("task") loader: DataLoader<Int, List<TaskView>>) =
            loader.load(id)

        // Runs after tasks (and their nested owner) are fully resolved.
        @Post("taskCount")
        fun postTaskCount(): Int = tasks.size
    }

    @Test
    fun `post_ runs after nested resolves complete`() = runBlocking {
        val users = listOf(UserView(10, "Alice"), UserView(20, "Bob"))
        val tasksBySprint: Map<Int, List<TaskView>> = mapOf(
            1 to listOf(TaskView(101, "T1a", 10), TaskView(102, "T1b", 20), TaskView(103, "T1c", 10)),
            2 to listOf(TaskView(201, "T2a", 20)),
        )

        val resolver = Resolver(
            loaderFactory = mapOf(
                "user" to {
                    DataLoader({ keys -> buildObject(users, keys) { it.id } }, this)
                },
                "task" to {
                    DataLoader(
                        { keys -> buildList(tasksBySprint.values.flatten(), keys) { t ->
                            tasksBySprint.entries.first { (_, list) -> t in list }.key
                        } },
                        this,
                    )
                },
            )
        )

        val out = resolver.resolve(listOf(SprintView(1, "S1"), SprintView(2, "S2")))
        assertEquals(3, out[0].taskCount, "S1 has 3 tasks")
        assertEquals(1, out[1].taskCount, "S2 has 1 task")
    }

    // ---- Collector + SendTo (child → parent aggregation) ----------------

    data class SprintWithContributors(
        val id: Int,
        val name: String,
        var tasks: List<TaskWithOwner> = emptyList(),
        var contributors: List<UserView> = emptyList(),
    ) {
        @Resolve("tasks")
        suspend fun resolveTasks(@LoaderDep("task") loader: DataLoader<Int, List<TaskWithOwner>>) =
            loader.load(id)

        @Post("contributors")
        fun postContributors(
            @CollectorParam("contributors") collector: Collector<UserView>,
        ): List<UserView> = collector.values().distinctBy { it.id }
    }

    data class TaskWithOwner(
        val id: Int,
        val title: String,
        val ownerId: Int,
        @SendTo("contributors") var owner: UserView? = null,
    ) {
        @Resolve("owner")
        suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, UserView>) =
            loader.load(ownerId)
    }

    @Test
    fun `collector aggregates child values via SendTo`() = runBlocking {
        val users = listOf(UserView(10, "Alice"), UserView(20, "Bob"), UserView(30, "Carol"))
        val tasksBySprint: Map<Int, List<TaskWithOwner>> = mapOf(
            1 to listOf(
                TaskWithOwner(101, "T1a", 10),
                TaskWithOwner(102, "T1b", 20),
                TaskWithOwner(103, "T1c", 10),  // dup owner
            ),
        )

        val resolver = Resolver(
            loaderFactory = mapOf(
                "user" to { DataLoader({ keys -> buildObject(users, keys) { it.id } }, this) },
                "task" to {
                    DataLoader(
                        { keys -> buildList(tasksBySprint.values.flatten(), keys) { t ->
                            tasksBySprint.entries.first { (_, list) -> t in list }.key
                        } },
                        this,
                    )
                },
            )
        )

        val out = resolver.resolve(SprintWithContributors(1, "S1"))
        val contributorNames = out.contributors.map { it.name }.sorted()
        assertEquals(listOf("Alice", "Bob"), contributorNames, "dedup by id; Carol absent")
    }
}
