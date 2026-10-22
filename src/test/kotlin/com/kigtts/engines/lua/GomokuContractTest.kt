package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class GomokuContractTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun repairedGameLoadsAndPersistsHumanAndComputerMovesWithinTheNormalBudget() = runBlocking {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val source = requireNotNull(javaClass.getResource("/gomoku.lua")).readText()
        val directory = env.storage.directory("imports/game")
        directory.resolve("manifest.json").writeText("""{"id":"tools.cloud-agent.gomoku","name":"五子棋","version":"1.0.1","permissions":[]}""")
        directory.resolve("main.lua").writeText(source)
        val store = LuaSourceStore(env.storage); val project = store.confirm(store.stage(directory))
        val executor = LuaExecutor(env, store); val context = PluginContext("", "")
        val contributions = LuaUiSchema.contributions(project.manifest.id, executor.call(project, "contributions", context))
        assertEquals("五子棋", contributions.settingsEntries.single().label)
        val pages = LuaUiSchema.pages(project.manifest.id, executor.call(project, "pages", context))
        assertEquals(9, (pages.single().blocks[3] as PageBlock.Actions).actions.size)
        executor.call(project, "execute", context, "row_5")
        executor.call(project, "execute", context, "col_5")
        val board = store.data(project.manifest.id).file("state").readText().substringBefore(';')
        assertEquals(1, board.count { it == '1' }); assertEquals(1, board.count { it == '2' })
        assertTrue(executor.call(project, "test", context).jsonObject.getValue("ok").jsonPrimitive.boolean)
    }
}
