package com.kigtts.engines.lua

import com.kigtts.plugin.api.PluginFileItem
import com.kigtts.plugin.api.PluginStorage
import java.io.File
import java.util.UUID

data class LuaDraft(val token: String, val projectId: String, val root: File,
    var directory: String = "", var editing: String = "main.lua", var modified: Boolean = false,
    val reviewOf: String? = null)

/** All edits stay in a draft. Moving a file cannot change a running tool until it is reviewed. */
class LuaDraftFiles(private val storage: PluginStorage, private val store: LuaSourceStore) {
    private val sessions = linkedMapOf<String, LuaDraft>()
    @Synchronized fun create(id: String): LuaDraft {
        store.project(id)
        require(sessions.size < 8) { "请先保存或关闭其它编辑项目" }
        val token = UUID.randomUUID().toString()
        val root = storage.directory("drafts/$token")
        store.code(id).copyRecursively(root, overwrite = false)
        return LuaDraft(token, id, root).also { sessions[token] = it }
    }
    @Synchronized fun inspect(pending: LuaPending): LuaDraft {
        require(sessions.size < 8) { "请先保存或关闭其它编辑项目" }
        val token = UUID.randomUUID().toString()
        val root = storage.directory("drafts/$token")
        pending.directory.copyRecursively(root, overwrite = false)
        return LuaDraft(token, pending.manifest.id, root, reviewOf = pending.token).also { sessions[token] = it }
    }
    @Synchronized fun session(token: String) = sessions[token] ?: error("编辑会话已结束，请重新打开")
    fun file(draft: LuaDraft, path: String): File {
        require(path.isNotEmpty() && path.length <= 240 && !path.startsWith('/') && !path.contains('\\') && !path.contains(':'))
        require(path.split('/').none { it in setOf("", ".", "..") } && !path.contains('\u0000'))
        val file = draft.root.resolve(path).canonicalFile
        require(file.toPath().startsWith(draft.root.canonicalFile.toPath()))
        return file
    }
    fun directory(draft: LuaDraft, path: String): File = if (path.isEmpty()) draft.root else file(draft, path).also { require(it.isDirectory) }
    fun items(draft: LuaDraft): List<PluginFileItem> = directory(draft, draft.directory).listFiles().orEmpty()
        .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        .map { PluginFileItem(it.relativeTo(draft.root).invariantSeparatorsPath, it.name, it.isDirectory, if (it.isFile) it.length() else 0) }
    fun move(draft: LuaDraft, paths: List<String>, destination: String) {
        require(paths.isNotEmpty() && paths.distinct().size == paths.size)
        val sources = paths.map { protected(draft, it) }
        val folder = directory(draft, destination)
        require(destination == "assets" || destination.startsWith("assets/")) { "资源只能移入 assets" }
        require(sources.none { source -> sources.any { it != source && source.toPath().startsWith(it.toPath()) } }) { "不能同时选择目录及其中的文件" }
        val targets = sources.map { source -> folder.resolve(source.name).canonicalFile.also { target ->
            require(!target.exists() && !target.toPath().startsWith(source.toPath())) { "目标已存在或位于源目录内" }
        } }
        require(targets.map { it.name.lowercase() }.distinct().size == targets.size) { "目标文件名重复" }
        val moved = mutableListOf<Pair<File, File>>()
        try { sources.zip(targets).forEach { (from, to) -> java.nio.file.Files.move(from.toPath(), to.toPath()); moved += from to to } }
        catch (failure: Throwable) { moved.asReversed().forEach { (from, to) -> java.nio.file.Files.move(to.toPath(), from.toPath()) }; throw failure }
        draft.modified = true
    }
    fun copy(draft: LuaDraft, paths: List<String>, destination: String) {
        require(paths.isNotEmpty() && paths.distinct().size == paths.size)
        val sources = paths.map { file(draft, it).also { source -> require(source.exists()) } }
        val folder = directory(draft, destination)
        require(destination == "assets" || destination.startsWith("assets/"))
        val targets = sources.map { source -> folder.resolve(source.name).canonicalFile.also { target ->
            require(!target.exists() && !target.toPath().startsWith(source.toPath()))
        } }
        require(targets.map { it.name.lowercase() }.distinct().size == targets.size)
        val added = mutableListOf<File>()
        try {
            sources.zip(targets).forEach { (source, target) -> added += target; require(source.copyRecursively(target, overwrite = false)) }
            val all = draft.root.walkTopDown().filter { it != draft.root }.toList()
            require(all.size <= LuaSourceStore.FILE_LIMIT && all.filter { it.isFile }.sumOf { it.length() } <= LuaSourceStore.TOTAL_LIMIT)
        } catch (failure: Throwable) { added.forEach { it.deleteRecursively() }; throw failure }
        draft.modified = true
    }
    fun rename(draft: LuaDraft, path: String, destination: String) {
        val source = protected(draft, path)
        require(destination.isNotBlank() && !destination.contains('/')) { "名称不能包含目录" }
        val target = file(draft, (path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }) + destination)
        require(!target.exists()) { "名称已存在" }
        java.nio.file.Files.move(source.toPath(), target.toPath())
        draft.modified = true
    }
    fun delete(draft: LuaDraft, paths: List<String>) {
        require(paths.isNotEmpty() && paths.distinct().size == paths.size)
        val files = paths.map { protected(draft, it) }
        files.forEach { require(it.deleteRecursively()) { "删除失败" } }; draft.modified = true
    }
    fun create(draft: LuaDraft, name: String, folder: Boolean) {
        require(name.isNotBlank() && !name.contains('/') && name.length <= 100)
        val base = draft.directory.ifEmpty { "assets" }
        require(base == "assets" || base.startsWith("assets/"))
        val target = file(draft, "$base/$name")
        require(!target.exists()); target.parentFile.mkdirs()
        if (folder) require(target.mkdir()) else target.writeText("", Charsets.UTF_8)
        draft.modified = true
    }
    fun import(draft: LuaDraft, resources: List<File>) {
        require(resources.size in 1..LuaSourceStore.FILE_LIMIT)
        val base = draft.directory.ifEmpty { "assets" }
        require(base == "assets" || base.startsWith("assets/"))
        val added = mutableListOf<File>()
        try {
            resources.forEach { source ->
                require(source.canonicalFile.toPath().startsWith(storage.root.canonicalFile.toPath()) && source.isFile && source.length() <= 8 * 1024 * 1024L)
                val target = file(draft, "$base/${source.name}")
                require(!target.exists()) { "${source.name} 已存在" }
                target.parentFile.mkdirs(); source.copyTo(target); added += target
            }
            val files = draft.root.walkTopDown().filter { it.isFile }.toList()
            require(files.size <= LuaSourceStore.FILE_LIMIT && files.sumOf { it.length() } <= LuaSourceStore.TOTAL_LIMIT)
        } catch (failure: Throwable) { added.forEach { it.delete() }; throw failure }
        draft.modified = true
    }
    fun write(draft: LuaDraft, value: String) {
        require(value.toByteArray().size <= LuaSourceStore.SOURCE_LIMIT && value.none { it == '\u0000' })
        val target = file(draft, draft.editing)
        require(target.isFile); target.writeText(value, Charsets.UTF_8); draft.modified = true
    }
    fun review(draft: LuaDraft): LuaPending = store.stage(draft.root)
    @Synchronized fun close(draft: LuaDraft) { sessions.remove(draft.token); draft.root.deleteRecursively() }
    private fun protected(draft: LuaDraft, path: String): File {
        require(path.startsWith("assets/")) { "main.lua 和 manifest.json 不能删除、移动或重命名" }
        return file(draft, path).also { require(it.exists()) }
    }
}
