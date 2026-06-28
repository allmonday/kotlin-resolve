package com.tangkikodo.kotlinresolve

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExposeAsTest {

    data class UserView(val id: Int, val name: String)

    data class TaskView(
        val id: Int,
        val title: String,
        val ownerId: Int,
        var owner: UserView? = null,
        var fullTitle: String = "",
    ) {
        @Resolve("owner")
        suspend fun resolveOwner(@LoaderDep("user") loader: DataLoader<Int, UserView>) =
            loader.load(ownerId)

        @Post("fullTitle")
        fun postFullTitle(ancestorContext: AncestorContext): String =
            "${ancestorContext["sprintName"]} / $title"
    }

    data class SprintView(
        val id: Int,
        @ExposeAs("sprintName") val name: String,
        var tasks: List<TaskView> = emptyList(),
    ) {
        @Resolve("tasks")
        suspend fun resolveTasks(@LoaderDep("task") loader: DataLoader<Int, List<TaskView>>) =
            loader.load(id)
    }

    @Test
    fun `expose broadcasts value to descendant post context`() = runBlocking {
        val users = listOf(UserView(10, "Alice"))
        val tasksBySprint: Map<Int, List<TaskView>> = mapOf(
            1 to listOf(TaskView(101, "Implement login", 10), TaskView(102, "Write tests", 10)),
            2 to listOf(TaskView(201, "Ship it", 10)),
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

        val out = resolver.resolve(listOf(SprintView(1, "Sprint 1"), SprintView(2, "Sprint 2")))
        assertEquals("Sprint 1 / Implement login", out[0].tasks[0].fullTitle)
        assertEquals("Sprint 1 / Write tests", out[0].tasks[1].fullTitle)
        assertEquals("Sprint 2 / Ship it", out[1].tasks[0].fullTitle)
    }
}
