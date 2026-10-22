package com.kigtts.engines.lua

import com.kigtts.plugin.api.PluginStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LuaDraftFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun setup(): Triple<LuaSourceStore, LuaDraftFiles, LuaDraft> {
        val storage = PluginStorage(temp.newFolder())
        val source = storage.directory("imports/tool")
        source.resolve("manifest.json").writeText("""{"id":"tool","name":"工具","version":"1.0"}""")
        source.resolve("main.lua").writeText("return {}")
        source.resolve("assets/a").mkdirs(); source.resolve("assets/b").mkdirs()
        source.resolve("assets/a/example.txt").writeText("内容")
        source.resolve("assets/a/second.txt").writeText("其它")
        val store = LuaSourceStore(storage); store.confirm(store.stage(source))
        val files = LuaDraftFiles(storage, store)
        return Triple(store, files, files.create("tool"))
    }
    @Test fun moveAndRenameKeepRunningProjectUnchangedUntilReviewed() {
        val (store, files, draft) = setup()
        files.move(draft, listOf("assets/a/example.txt", "assets/a/second.txt"), "assets/b")
        assertFalse(files.file(draft, "assets/a/example.txt").exists())
        assertTrue(store.code("tool").resolve("assets/a/example.txt").exists())
        files.rename(draft, "assets/b/example.txt", "renamed.txt")
        assertEquals("内容", files.file(draft, "assets/b/renamed.txt").readText())
        store.confirm(files.review(draft))
        assertTrue(store.code("tool").resolve("assets/b/renamed.txt").exists())
        assertFalse(store.code("tool").resolve("assets/a/example.txt").exists())
    }
    @Test fun batchMovePreflightAvoidsPartialChanges() {
        val (_, files, draft) = setup()
        files.file(draft, "assets/b/second.txt").writeText("占位")
        try { files.move(draft, listOf("assets/a/example.txt", "assets/a/second.txt"), "assets/b"); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(files.file(draft, "assets/a/example.txt").isFile)
        assertFalse(files.file(draft, "assets/b/example.txt").exists())
    }
    @Test fun cannotMoveDirectoryInsideItselfOrDeleteSourceManifest() {
        val (_, files, draft) = setup()
        files.file(draft, "assets/a/sub").mkdir()
        try { files.move(draft, listOf("assets/a"), "assets/a/sub"); fail() } catch (_: IllegalArgumentException) {}
        try { files.delete(draft, listOf("main.lua")); fail() } catch (_: IllegalArgumentException) {}
        try { files.rename(draft, "manifest.json", "removed.json"); fail() } catch (_: IllegalArgumentException) {}
        try { files.file(draft, "assets/../../outside"); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(files.file(draft, "main.lua").isFile)
    }
    @Test fun sourceEditsAreDraftOnlyAndAssetCreationAppearsInSortedNativeList() {
        val (store, files, draft) = setup()
        files.write(draft, "return {changed=true}")
        assertEquals("return {}", store.source("tool"))
        files.create(draft, "new", true); files.create(draft, "new.txt", false)
        draft.directory = "assets"
        val items = files.items(draft)
        assertEquals("a", items.first().name)
        assertTrue(items.first().directory)
        assertEquals("new.txt", items.last().name)
        files.close(draft)
        assertFalse(draft.root.exists())
    }
    @Test fun copyingPreservesSourceAndDoesNotOverwriteExistingFiles() {
        val (_, files, draft) = setup()
        files.copy(draft, listOf("assets/a/example.txt"), "assets/b")
        assertTrue(files.file(draft, "assets/a/example.txt").isFile)
        assertEquals("内容", files.file(draft, "assets/b/example.txt").readText())
        try { files.copy(draft, listOf("assets/a/example.txt"), "assets/b"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals("内容", files.file(draft, "assets/b/example.txt").readText())
    }
}
