package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LuaRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    private fun environment(app: AppBroker? = null, quick: QuickTextBroker? = null) = object : PluginEnvironment {
        override val storage = PluginStorage(temp.newFolder())
        override val assets = storage.directory("bundled")
        override val appBroker = app
        override val quickTextBroker = quick
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("Network is unavailable to Lua")
    }
    private fun packageDirectory(environment: PluginEnvironment, source: String, id: String = "example", permissions: List<String> = emptyList()): File {
        val directory = environment.storage.directory("imports/$id")
        directory.resolve("manifest.json").writeText(buildJsonObject { put("id", id); put("name", "示例工具"); put("version", "1.0"); put("permissions", JsonArray(permissions.map(::JsonPrimitive))) }.toString())
        directory.resolve("main.lua").writeText(source)
        return directory
    }
    private fun installed(environment: PluginEnvironment, source: String, permissions: List<String> = emptyList()): Pair<LuaSourceStore, LuaProject> {
        val store = LuaSourceStore(environment.storage)
        return store to store.confirm(store.stage(packageDirectory(environment, source, permissions = permissions)))
    }
    @Test fun importedCodeWaitsForHumanConfirmationAndRegistersScopedNativeUi() = runBlocking {
        val environment = environment()
        val directory = packageDirectory(environment, """
            return {
              contributions=function(ctx) return {actions={{id='hello',label='问好',slot='EDITOR_TOP_END',icon='INFO'}}} end,
              pages=function(ctx) return {{id='main',title='工具',blocks={{type='text',text='内容'}}}} end,
              execute=function(action,ctx) return {type='message',text='完成'} end
            }
        """.trimIndent())
        val plugin = LuaRuntimePlugin(); plugin.attach(environment)
        val result = plugin.execute("import", PluginContext("", "", inputs = mapOf("resourcePath" to directory.relativeTo(environment.storage.root).invariantSeparatorsPath))) as PluginOutcome.OpenPage
        assertTrue(plugin.contributions.actions.isEmpty())
        assertTrue(plugin.page(result.pageId, PluginContext("", ""))!!.blocks.isNotEmpty())
        val token = result.pageId.substringAfter('/')
        val denied = plugin.execute("confirm/$token", PluginContext("", "", inputs = mapOf("_callerPlugin" to "agent")))
        assertTrue(denied is PluginOutcome.Message); assertTrue(plugin.contributions.actions.isEmpty())
        plugin.execute("confirm/$token", PluginContext("", ""))
        assertEquals("module/example/hello", plugin.contributions.actions.single().id)
        assertEquals(PluginOutcome.Message("完成"), plugin.execute("module/example/hello", PluginContext("", "")))
    }
    @Test fun syntaxValidationDoesNotRunTopLevelLoop() {
        val environment = environment(); val store = LuaSourceStore(environment.storage)
        LuaExecutor(environment, store).validate("while true do end")
    }
    @Test fun infiniteLoopIsStoppedAndOtherToolsRemainUsable() = runBlocking {
        val environment = environment()
        val plugin = LuaRuntimePlugin(); plugin.attach(environment)
        val bad = packageDirectory(environment, "while true do end", "bad")
        val result = plugin.execute("import", PluginContext("", "", inputs = mapOf("resourcePath" to bad.relativeTo(environment.storage.root).invariantSeparatorsPath))) as PluginOutcome.OpenPage
        val start = System.nanoTime()
        plugin.execute("confirm/${result.pageId.substringAfter('/')}", PluginContext("", ""))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5000)
        assertFalse(LuaSourceStore(environment.storage).project("bad").enabled)
        val good = packageDirectory(environment, "return {execute=function(a,c) return {type='message',text='正常'} end}", "good")
        val goodPage = plugin.execute("import", PluginContext("", "", inputs = mapOf("resourcePath" to good.relativeTo(environment.storage.root).invariantSeparatorsPath))) as PluginOutcome.OpenPage
        plugin.execute("confirm/${goodPage.pageId.substringAfter('/')}", PluginContext("", ""))
        assertEquals(PluginOutcome.Message("正常"), plugin.execute("module/good/hello", PluginContext("", "")))
    }
    @Test fun unavailableLibrariesCannotEscapeIntoJavaOrFiles() = runBlocking {
        val environment = environment()
        val (store, project) = installed(environment, """
            return {test=function(c)
              assert(io==nil and os==nil and package==nil and require==nil and luajava==nil)
              assert(load==nil and loadfile==nil and dofile==nil and debug==nil and coroutine==nil)
              assert(getmetatable==nil and setmetatable==nil and pcall==nil)
              assert(string.rep==nil and string.format==nil and ('x').rep==nil)
              return 'true'
            end}
        """.trimIndent())
        assertEquals(JsonPrimitive(true), LuaExecutor(environment, store).call(project, "test", PluginContext("", "")))
    }
    @Test fun permissionsAreEnforcedBeforeCallingHost() = runBlocking {
        var count = 0
        val environment = environment(object : AppBroker { override suspend fun request(command: AppCommand): AppReply { count++; return AppReply.Data(JsonObject(emptyMap())) } })
        val (store, project) = installed(environment, "return {execute=function(a,c) return host.app({type='text',text='更新'}) end}")
        try { LuaExecutor(environment, store).call(project, "execute", PluginContext("", ""), "hello"); fail() } catch (_: Exception) {}
        assertEquals(0, count)
    }
    @Test fun grantedAppCommandsUseTypedBrokerAndPresentationIsReadOnly() = runBlocking {
        val commands = mutableListOf<AppCommand>()
        val environment = environment(object : AppBroker { override suspend fun request(command: AppCommand): AppReply { commands += command; return AppReply.Data(buildJsonObject { put("reply", PluginJson.encodeToJsonElement<AppReply>(AppReply.Data(buildJsonObject { put("ok", true) }))) }) } })
        val (store, project) = installed(environment, "return {execute=function(a,c) return host.app({type='text',text='123'}) end, pages=function(c) return host.app({type='text',text='禁止'}) end}", listOf("app-control"))
        LuaExecutor(environment, store).call(project, "execute", PluginContext("", ""), "hello")
        assertEquals(AppCommand.ManagedCall("example", "app", PluginJson.encodeToJsonElement<AppCommand>(AppCommand.SetText("123")).jsonObject), commands.single())
        try { LuaExecutor(environment, store).call(project, "pages", PluginContext("", "")); fail() } catch (_: Exception) {}
        assertEquals(1, commands.size)
    }
    @Test fun instructionAndMemoryGrowthBudgetsWork() = runBlocking {
        val environment = environment()
        for (source in listOf("while true do end", "local s='a'; while true do s=s..s end")) {
            val (store, project) = installed(environment, source)
            try { LuaExecutor(environment, store).call(project, "pages", PluginContext("", "")); fail() } catch (_: LuaBudgetExceeded) {}
        }
    }
    @Test fun cancellationEndsTheInterpreter() = runBlocking {
        val environment = environment(); val (store, project) = installed(environment, "while true do end")
        val job = launch(Dispatchers.Default) { LuaExecutor(environment, store, Int.MAX_VALUE, 60_000).call(project, "pages", PluginContext("", "")) }
        delay(50); withTimeout(2000) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
    }
    @Test fun dataSurvivesUpdateAndModifiedSourcesRequireNewReview() {
        val environment = environment(); val (store, project) = installed(environment, "return {}")
        store.data(project.manifest.id).file("state.txt").writeText("saved")
        val update = store.edit(project.manifest.id, "return {version='2'}")
        store.confirm(update)
        assertEquals("saved", store.data(project.manifest.id).file("state.txt").readText())
        store.code(project.manifest.id).resolve("main.lua").writeText("return {changed=true}")
        try { store.verify(store.project(project.manifest.id)); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun packagePathAndCapabilityValidationRejectsBadInput() {
        val environment = environment(); val store = LuaSourceStore(environment.storage)
        val zip = environment.storage.file("imports/evil.kiglua").also { it.parentFile.mkdirs() }
        ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("../main.lua")); it.write("return {}".toByteArray()); it.closeEntry() }
        try { store.stage(zip); fail() } catch (_: IllegalArgumentException) {}
        val bad = packageDirectory(environment, "return {}", "permissions", listOf("network"))
        try { store.stage(bad); fail() } catch (_: IllegalArgumentException) {}
        val bytecode = packageDirectory(environment, "\u001bLua", "binary")
        try { store.stage(bytecode); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun nativeUiNamespacesModuleActionsAndCapsTreeSize() {
        val value = PluginJson.parseToJsonElement("""{"actions":[{"id":"same","label":"项目","slot":"EDITOR_MENU","presentation":"MENU_ITEM"}]}""")
        assertNotEquals(LuaUiSchema.contributions("a", value).actions.single().id, LuaUiSchema.contributions("b", value).actions.single().id)
        try { LuaUiSchema.route("example", "../../confirm"); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun jsonPreservesNumericTextAndDetectsCycles() {
        assertEquals(JsonPrimitive("123"), LuaJson.fromLua(org.luaj.vm2.LuaValue.valueOf("123")))
        val table = org.luaj.vm2.LuaTable().also { it.set("self", it) }
        try { LuaJson.fromLua(table); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun onlyReviewedLocalModulesCanBeLoaded() = runBlocking {
        val environment = environment()
        val directory = packageDirectory(environment, "return {test=function(c) local m=host.module('util.math'); return {result=m.double(21)} end}")
        directory.resolve("assets/lua/util").mkdirs()
        directory.resolve("assets/lua/util/math.lua").writeText("return {double=function(n) return n*2 end}")
        val store = LuaSourceStore(environment.storage); val project = store.confirm(store.stage(directory))
        val result = LuaExecutor(environment, store).call(project, "test", PluginContext("", ""))
        assertEquals(42, result.jsonObject.getValue("result").jsonPrimitive.int)
        store.verify(project)
        val malicious = store.edit("example", "return {test=function(c) return host.module('../data/private') end}")
        val updated = store.confirm(malicious)
        try { LuaExecutor(environment, store).call(updated, "test", PluginContext("", "")); fail() } catch (_: Exception) {}
    }
    @Test fun changedPendingCodeCannotBeConfirmedUnderOldReview() = runBlocking {
        val environment = environment(); val plugin = LuaRuntimePlugin(); plugin.attach(environment)
        val directory = packageDirectory(environment, "return {}")
        val page = plugin.execute("import", PluginContext("", "", inputs = mapOf("resourcePath" to directory.relativeTo(environment.storage.root).invariantSeparatorsPath))) as PluginOutcome.OpenPage
        val token = page.pageId.substringAfter('/')
        environment.storage.file("staging/$token/main.lua").writeText("return {changed=true}")
        val denied = plugin.execute("confirm/$token", PluginContext("", ""))
        assertTrue(denied is PluginOutcome.Message)
        assertTrue(LuaSourceStore(environment.storage).projects().isEmpty())
    }
    @Test fun independentSourceEditorWritesDraftAndRequiresReviewBeforeUpdating() = runBlocking {
        val environment = environment(); val plugin = LuaRuntimePlugin(); plugin.attach(environment)
        val source = packageDirectory(environment, "return {}")
        val review = plugin.execute("import", PluginContext("", "", inputs = mapOf("resourcePath" to source.relativeTo(environment.storage.root).invariantSeparatorsPath))) as PluginOutcome.OpenPage
        plugin.execute("confirm/${review.pageId.substringAfter('/')}", PluginContext("", ""))
        val files = plugin.execute("files/example", PluginContext("", "")) as PluginOutcome.OpenPage
        val token = files.pageId.substringAfter('/')
        val edit = plugin.execute("file-open/$token", PluginContext("", "", inputs = mapOf("path" to "main.lua"))) as PluginOutcome.OpenPage
        val page = plugin.page(edit.pageId, PluginContext("", ""))!!
        assertTrue(page.blocks.first() is PageBlock.SourceEditor)
        plugin.execute("file-write/$token", PluginContext("", "", inputs = mapOf("value" to "return {changed=true}")))
        assertEquals("return {}", LuaSourceStore(environment.storage).source("example"))
        val changed = plugin.execute("draft-review/$token", PluginContext("", "")) as PluginOutcome.OpenPage
        plugin.execute("confirm/${changed.pageId.substringAfter('/')}", PluginContext("", ""))
        assertEquals("return {changed=true}", LuaSourceStore(environment.storage).source("example"))
    }
    @Test fun optionalExternalTemplateRepositoryContracts() = runBlocking {
        val root = System.getenv("KIGTTS_TEMPLATE_ROOT")?.let(::File) ?: return@runBlocking
        for ((name, action) in listOf("lua-hello" to "hello", "lua-modules" to "increment")) {
            val environment = environment()
            val imported = environment.storage.directory("imports/template")
            root.resolve("templates/$name").copyRecursively(imported)
            val store = LuaSourceStore(environment.storage); val project = store.confirm(store.stage(imported))
            val executor = LuaExecutor(environment, store)
            val contributions = LuaUiSchema.contributions(project.manifest.id, executor.call(project, "contributions", PluginContext("", "")))
            val pages = LuaUiSchema.pages(project.manifest.id, executor.call(project, "pages", PluginContext("", "")))
            PluginSnapshot(PluginDescriptor("runtime.lua", "Lua", "测试"), contributions, pages)
            LuaUiSchema.outcome(project.manifest.id, executor.call(project, "execute", PluginContext("", ""), action), project.manifest.permissions)
            if (name == "lua-modules") assertEquals("1", store.data(project.manifest.id).file("counter.txt").readText())
        }
        val environment = environment()
        val imported = environment.storage.directory("imports/calculator")
        root.resolve("examples/html-calculator").copyRecursively(imported)
        val store = LuaSourceStore(environment.storage); val project = store.confirm(store.stage(imported))
        val executor = LuaExecutor(environment, store)
        val contributions = LuaUiSchema.contributions(project.manifest.id, executor.call(project, "contributions", PluginContext("", "")))
        val pages = LuaUiSchema.pages(project.manifest.id, executor.call(project, "pages", PluginContext("", "")))
        PluginSnapshot(PluginDescriptor("runtime.lua", "Lua", "测试"), contributions, pages)
        assertTrue(executor.call(project, "test", PluginContext("", "")).jsonObject.getValue("ok").jsonPrimitive.boolean)
    }
}
