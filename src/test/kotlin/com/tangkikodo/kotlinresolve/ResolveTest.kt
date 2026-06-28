package com.tangkikodo.kotlinresolve

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResolveTest {

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

    @Test
    fun `resolve populates single relationship field`() = runBlocking {
        val users = listOf(UserView(10, "Alice"), UserView(20, "Bob"))
        val tasks = listOf(
            TaskView(1, "T1", 10),
            TaskView(2, "T2", 10),
            TaskView(3, "T3", 20),
        )

        var batchCount = 0
        val resolver = Resolver(
            loaderFactory = mapOf(
                "user" to {
                    DataLoader(
                        batchFn = { keys ->
                            batchCount++
                            buildObject(users, keys) { it.id }
                        },
                        scope = this,
                    )
                }
            )
        )
        val resolved = resolver.resolve(tasks)

        assertEquals(UserView(10, "Alice"), resolved[0].owner)
        assertEquals(UserView(10, "Alice"), resolved[1].owner)
        assertEquals(UserView(20, "Bob"), resolved[2].owner)
        assertEquals(1, batchCount, "all owners in the same level → one batch")
    }

    data class SprintView(
        val id: Int,
        val name: String,
        var tasks: List<TaskView> = emptyList(),
    ) {
        @Resolve("tasks")
        suspend fun resolveTasks(@LoaderDep("task") loader: DataLoader<Int, List<TaskView>>) =
            loader.load(id)
    }

    @Test
    fun `nested resolve across two levels`() = runBlocking {
        val users = listOf(UserView(10, "Alice"), UserView(20, "Bob"))
        val tasksBySprint: Map<Int, List<TaskView>> = mapOf(
            1 to listOf(TaskView(101, "T1a", 10), TaskView(102, "T1b", 20)),
            2 to listOf(TaskView(201, "T2a", 10)),
        )

        var userBatchCount = 0
        var taskBatchCount = 0

        val resolver = Resolver(
            loaderFactory = mapOf(
                "user" to {
                    DataLoader(
                        batchFn = { keys ->
                            userBatchCount++
                            buildObject(users, keys) { it.id }
                        },
                        scope = this,
                    )
                },
                "task" to {
                    DataLoader(
                        batchFn = { keys ->
                            taskBatchCount++
                            buildList(tasksBySprint.values.flatten(), keys) { task ->
                                tasksBySprint.entries.first { (_, list) -> task in list }.key
                            }
                        },
                        scope = this,
                    )
                },
            )
        )

        val sprints = listOf(SprintView(1, "S1"), SprintView(2, "S2"))
        val out = resolver.resolve(sprints)

        assertEquals(1, taskBatchCount, "sprint tasks → one batch")
        assertEquals(1, userBatchCount, "task owners → one batch")

        assertEquals(2, out[0].tasks.size)
        assertEquals("Alice", out[0].tasks[0].owner?.name)
        assertEquals("Bob", out[0].tasks[1].owner?.name)
        assertEquals(1, out[1].tasks.size)
        assertEquals("Alice", out[1].tasks[0].owner?.name)
    }
}
