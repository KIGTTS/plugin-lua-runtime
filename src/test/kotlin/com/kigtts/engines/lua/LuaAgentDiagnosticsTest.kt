package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class LuaAgentDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun environment() = object : PluginEnvironment {
        override val storage = PluginStorage(temp.root); override val assets = temp.root
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
    }
    private fun bundle(env: PluginEnvironment, folder: String, source: String, version: String): String {
        val dir = env.storage.directory("imports/$folder")
        dir.resolve("manifest.json").writeText("""{"id":"test.game","name":"Game","version":"$version","permissions":[]}""")
        dir.resolve("main.lua").writeText(source); return "imports/$folder"
    }
    private fun result(outcome: PluginOutcome) = PluginJson.parseToJsonElement((outcome as PluginOutcome.Dialog).text).jsonObject
    private fun context(vararg values: Pair<String, String>) = PluginContext("", "", inputs = mapOf("_agentApproved" to "true", *values))
    @Test fun brokenUpdateReturnsLineAndPhaseWithoutReplacingTheWorkingCodeOrData() = runBlocking {
        val env = environment(); val plugin = LuaRuntimePlugin(); plugin.attach(env)
        val good = bundle(env, "good", "return {pages=function(c) return {} end}", "1.0")
        assertTrue(result(plugin.execute("agent-install", context("resourcePath" to good))).getValue("enabled").jsonPrimitive.boolean)
        val store = LuaSourceStore(env.storage); store.data("test.game").file("progress").writeText("preserved")
        val before = store.source("test.game")
        val bad = bundle(env, "bad", "return {pages=function(c)\n  pcall(host.data.read, 'state')\n  return {} end}", "2.0")
        val failure = result(plugin.execute("agent-install", context("resourcePath" to bad)))
        assertFalse(failure.getValue("installed").jsonPrimitive.boolean)
        assertEquals("pages", failure.getValue("phase").jsonPrimitive.content)
        assertEquals(2, failure.getValue("line").jsonPrimitive.int)
        assertTrue(failure.getValue("error").jsonPrimitive.content.contains("attempt to call nil"))
        val after = LuaSourceStore(env.storage)
        assertEquals(before, after.source("test.game")); assertTrue(after.project("test.game").enabled)
        assertEquals("preserved", after.data("test.game").file("progress").readText())
    }
    @Test fun invalidUiWrapperAndExecuteErrorsReachAgentAsStructuredDiagnostics() = runBlocking {
        val env = environment(); val plugin = LuaRuntimePlugin(); plugin.attach(env)
        val bad = bundle(env, "wrapper", "return {pages=function(c) return {pages={}} end}", "1")
        val rejected = result(plugin.execute("agent-install", context("resourcePath" to bad)))
        assertEquals("pages", rejected.getValue("phase").jsonPrimitive.content)
        assertTrue(LuaSourceStore(env.storage).projects().isEmpty())
        val good = bundle(env, "run", "return {execute=function(a,c) error('board failed') end}", "1")
        plugin.execute("agent-install", context("resourcePath" to good))
        val failure = result(plugin.execute("agent", context("operation" to "run", "project" to "test.game", "action" to "go")))
        assertEquals("execute", failure.getValue("phase").jsonPrimitive.content)
        assertTrue(failure.getValue("error").jsonPrimitive.content.contains("board failed"))
        assertTrue(failure.getValue("outcomeUnknown").jsonPrimitive.boolean)
        assertFalse(failure.getValue("retryable").jsonPrimitive.boolean)
    }
    @Test fun failedSelfTestsAreRejectedAndLongSourcesCanBeReadBackAsValidJsonPages() = runBlocking {
        val env = environment(); val plugin = LuaRuntimePlugin(); plugin.attach(env)
        val bad = bundle(env, "selftest", "return {test=function(c) return {ok=false} end}", "1")
        assertEquals("test", result(plugin.execute("agent-install", context("resourcePath" to bad))).getValue("phase").jsonPrimitive.content)
        val source = "--" + "comment".repeat(2000) + "\nreturn {}"
        val good = bundle(env, "long", source, "2")
        assertTrue(result(plugin.execute("agent-install", context("resourcePath" to good))).getValue("enabled").jsonPrimitive.boolean)
        val read = StringBuilder(); var offset = 0
        do {
            val page = result(plugin.execute("agent", context("operation" to "read", "project" to "test.game", "offset" to offset.toString())))
            read.append(page.getValue("source").jsonPrimitive.content)
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        assertEquals(source, read.toString())
    }
}
