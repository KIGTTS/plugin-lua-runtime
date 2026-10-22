package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LuaSettingsEntriesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun identicalLocalSettingsIdsAreScopedWhileIntentionalSharedCardIsRetained() {
        val value = PluginJson.parseToJsonElement("""{"settingsEntries":[{"id":"preferences","label":"工具设置","pageId":"main","icon":"EDIT","cardId":"my-tools","cardTitle":"我的工具","summary":"选项","order":12}]}""")
        val a = LuaUiSchema.contributions("a", value).settingsEntries.single()
        val b = LuaUiSchema.contributions("b", value).settingsEntries.single()
        assertEquals("module/a/preferences", a.id); assertEquals("module/a/main", a.pageId)
        assertNotEquals(a.id, b.id); assertNotEquals(a.pageId, b.pageId)
        assertEquals("my-tools", a.cardId); assertEquals(a.cardId, b.cardId)
        assertEquals("我的工具", a.cardTitle); assertEquals("选项", a.summary); assertEquals(12, a.order); assertEquals(PluginIcon.EDIT, a.icon)
    }
    @Test fun enabledModulesActuallyRegisterNativeSettingsBesideEditorAndResolveTheirPages() = runBlocking {
        val environment = object : PluginEnvironment {
            override val storage = PluginStorage(temp.newFolder())
            override val assets = storage.directory("bundled")
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val store = LuaSourceStore(environment.storage)
        for (id in listOf("a", "b")) {
            val source = environment.storage.directory("imports/$id")
            source.resolve("manifest.json").writeText("""{"id":"$id","name":"$id","version":"1.0"}""")
            source.resolve("main.lua").writeText("""
                return {
                  contributions=function(ctx) return {settingsEntries={{id='preferences',label='设置',pageId='main',cardId='my-tools',cardTitle='工具'}}} end,
                  pages=function(ctx) return {{id='main',title='选项',blocks={{type='toggle',title='开关',checked=false,actionId='toggle'}}}} end
                }
            """.trimIndent())
            store.confirm(store.stage(source))
        }
        val plugin = LuaRuntimePlugin(); plugin.attach(environment)
        assertEquals(3, plugin.contributions.settingsEntries.size)
        assertTrue(plugin.contributions.settingsEntries.any { it.id == "editor" })
        for (id in listOf("a", "b")) assertEquals("module/$id/main", plugin.page("module/$id/main", PluginContext("", ""))!!.id)
        plugin.execute("enable/a", PluginContext("", ""))
        assertFalse(plugin.contributions.settingsEntries.any { it.id.startsWith("module/a/") })
        assertTrue(plugin.contributions.settingsEntries.any { it.id == "module/b/preferences" })
    }
    @Test fun invalidSettingsCardIsRejectedAndMissingMetadataUsesNativeDefaults() {
        val value = PluginJson.parseToJsonElement("""{"settingsEntries":[{"id":"s","label":"设置","pageId":"main","cardId":"../bad"}]}""")
        try { PluginSnapshot(PluginDescriptor("runtime.lua", "Lua", "test"), LuaUiSchema.contributions("bad", value), emptyList()); fail() }
        catch (_: IllegalArgumentException) { }
        val default = LuaUiSchema.contributions("example", PluginJson.parseToJsonElement("""{"settingsEntries":[{"id":"s","label":"设置","pageId":"main"}]}""")).settingsEntries.single()
        assertEquals("extensions", default.cardId); assertEquals(PluginIcon.SETTINGS, default.icon)
    }
    @Test fun settingsEntryWithoutActualPageOnlyDisablesThatModule() = runBlocking {
        val environment = object : PluginEnvironment {
            override val storage = PluginStorage(temp.newFolder())
            override val assets = storage.directory("bundled")
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val source = environment.storage.directory("imports/bad")
        source.resolve("manifest.json").writeText("""{"id":"bad","name":"bad","version":"1.0"}""")
        source.resolve("main.lua").writeText("return {contributions=function(c) return {settingsEntries={{id='s',label='设置',pageId='missing'}}} end}")
        val store = LuaSourceStore(environment.storage); store.confirm(store.stage(source))
        val plugin = LuaRuntimePlugin(); plugin.attach(environment)
        assertFalse(LuaSourceStore(environment.storage).project("bad").enabled)
        assertEquals(listOf("editor"), plugin.contributions.settingsEntries.map { it.id })
    }
}
