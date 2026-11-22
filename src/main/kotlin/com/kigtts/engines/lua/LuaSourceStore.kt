package com.kigtts.engines.lua

import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.PluginStorage
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

data class LuaManifest(val id: String, val name: String, val version: String, val permissions: Set<String>,
    val permissionPurposes: Map<String, String> = emptyMap(), val icon: String? = null)
data class LuaProject(val manifest: LuaManifest, val enabled: Boolean, val hash: String, val error: String = "")
data class LuaPending(val token: String, val manifest: LuaManifest, val hash: String, val directory: File)

/** Owns sources and data; staging never evaluates imported Lua. */
class LuaSourceStore(private val storage: PluginStorage) {
    companion object {
        const val SOURCE_LIMIT = 256 * 1024
        const val FILE_LIMIT = 128
        const val TOTAL_LIMIT = 16 * 1024 * 1024L
        val PERMISSIONS = setOf("app-control", "quick-text-read", "quick-text-write", "stickers-read", "stickers-write",
            "network", "overlay", "notifications", "resources", "background-work",
            "tts", "asr-batch", "asr-segmented", "asr-incremental", "voice-packs", "speech-service", "asr-service")
        private val ID = Regex("[a-z][a-z0-9.-]{0,63}")
    }
    private val records = linkedMapOf<String, LuaProject>()
    init {
        val index = storage.file("config/projects.json")
        if (index.isFile) runCatching {
            PluginJson.parseToJsonElement(index.readText()).jsonArray.forEach { element ->
                val record = element.jsonObject
                val manifest = manifest(record.getValue("manifest").jsonObject)
                records[manifest.id] = LuaProject(manifest, record["enabled"]?.jsonPrimitive?.boolean ?: false,
                    record.getValue("hash").jsonPrimitive.content, record["error"]?.jsonPrimitive?.content.orEmpty())
            }
        }.onFailure { records.clear() }
    }
    @Synchronized fun projects() = records.values.toList()
    @Synchronized fun project(id: String) = records[id] ?: throw LuaEditorIssue("issue.project_missing", "工具不存在")
    fun code(id: String) = storage.directory("projects/${safeId(id)}/code")
    fun data(id: String) = PluginStorage(storage.directory("projects/${safeId(id)}/data"))
    fun source(id: String): String = utf8(code(id).resolve("main.lua").readBytes())
    fun verify(project: LuaProject) { require(hash(code(project.manifest.id)) == project.hash) { throw LuaEditorIssue("issue.reimport", "源文件已变化，需要重新导入并确认") } }
    fun stage(source: File): LuaPending {
        require(source.canonicalFile.toPath().startsWith(storage.root.canonicalFile.toPath())) { throw LuaEditorIssue("issue.owner", "资源不属于 Lua 插件") }
        require(source.isDirectory || !source.extension.equals("kiglua", ignoreCase = true)) {
            throw LuaEditorIssue("issue.legacy_package", "请选择源码 ZIP 或已签名的插件包")
        }
        val token = UUID.randomUUID().toString()
        val target = storage.directory("staging/$token")
        try {
            if (source.isDirectory) copyPackage(source, target) else unzip(source, target)
            val parsed = readManifest(target.resolve("manifest.json"))
            val script = target.resolve("main.lua")
            require(script.isFile && script.length() in 1..SOURCE_LIMIT.toLong()) { throw LuaEditorIssue("issue.script_size", "main.lua 为空或超过 256 KiB") }
            utf8(script.readBytes()) // Reject bytecode/invalid UTF-8 without execution.
            return LuaPending(token, parsed, hash(target), target)
        } catch (failure: Throwable) { target.deleteRecursively(); throw failure }
    }
    fun pending(token: String): LuaPending {
        require(UUID.fromString(token).toString() == token)
        val dir = storage.file("staging/$token")
        require(dir.isDirectory) { throw LuaEditorIssue("issue.pending_missing", "待确认工具不存在") }
        return LuaPending(token, manifest(PluginJson.parseToJsonElement(utf8(dir.resolve("manifest.json").readBytes())).jsonObject), hash(dir), dir)
    }
    @Synchronized fun confirm(pending: LuaPending, enabled: Boolean = true): LuaProject {
        require(hash(pending.directory) == pending.hash) { throw LuaEditorIssue("issue.source_changed", "源文件已变化，请重新检查") }
        require(records.size < 32 || pending.manifest.id in records) { throw LuaEditorIssue("issue.project_limit", "最多可安装 32 个 Lua 工具") }
        val project = storage.directory("projects/${pending.manifest.id}")
        val code = project.resolve("code")
        val old = project.resolve("old-code")
        old.deleteRecursively()
        if (code.exists()) require(code.renameTo(old)) { throw LuaEditorIssue("issue.backup_failed", "不能备份旧版本") }
        try {
            require(pending.directory.renameTo(code)) { throw LuaEditorIssue("issue.save_failed", "不能保存工具") }
            val installed = LuaProject(pending.manifest, enabled, pending.hash)
            records[pending.manifest.id] = installed
            save()
            old.deleteRecursively()
            return installed
        } catch (failure: Throwable) {
            code.deleteRecursively(); if (old.exists()) old.renameTo(code)
            throw failure
        }
    }
    @Synchronized fun enabled(id: String, enabled: Boolean) { records[id] = project(id).copy(enabled = enabled, error = ""); save() }
    @Synchronized fun failed(id: String, message: String) { records[id] = project(id).copy(enabled = false, error = message.take(180)); save() }
    @Synchronized fun remove(id: String) {
        project(id); storage.file("projects/${safeId(id)}").deleteRecursively(); records.remove(id); save()
    }
    fun edit(id: String, source: String): LuaPending {
        val bytes = source.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..SOURCE_LIMIT) { throw LuaEditorIssue("issue.source_size", "源文件不能超过 256 KiB") }
        utf8(bytes)
        val draft = storage.directory("drafts/${UUID.randomUUID()}")
        copyPackage(code(id), draft)
        draft.resolve("main.lua").writeText(source, Charsets.UTF_8)
        return stage(draft).also { draft.deleteRecursively() }
    }
    fun export(id: String): File {
        val destination = storage.file("exports/${safeId(id)}-source.zip").also { it.parentFile.mkdirs() }
        ZipOutputStream(destination.outputStream()).use { out ->
            code(id).walkTopDown().filter { it != code(id) }.sortedBy { it.relativeTo(code(id)).invariantSeparatorsPath }.forEach { file ->
                out.putNextEntry(ZipEntry(file.relativeTo(code(id)).invariantSeparatorsPath + if (file.isDirectory) "/" else ""))
                if (file.isFile) file.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        return destination
    }
    private fun copyPackage(source: File, target: File) {
        val files = source.walkTopDown().filter { it != source }.toList()
        require(files.size <= FILE_LIMIT) { throw LuaEditorIssue("issue.file_count", "文件过多") }
        var total = 0L
        val paths = hashSetOf<String>()
        files.forEach { file ->
            require(file.canonicalFile.toPath().startsWith(source.canonicalFile.toPath())) { throw LuaEditorIssue("issue.external_link", "不允许外部链接") }
            val path = safePath(file.relativeTo(source).invariantSeparatorsPath)
            require(paths.add(path.lowercase())) { throw LuaEditorIssue("issue.duplicate_file", "文件名重复") }
            if (file.isDirectory) target.resolve(path).mkdirs() else {
                total += file.length(); require(total <= TOTAL_LIMIT && file.length() <= 8 * 1024 * 1024L) { throw LuaEditorIssue("issue.resource_size", "资源过大") }
                target.resolve(path).also { it.parentFile.mkdirs(); file.copyTo(it) }
            }
        }
    }
    private fun unzip(source: File, target: File) {
        require(source.isFile && source.length() <= TOTAL_LIMIT) { throw LuaEditorIssue("issue.package_missing", "源包不存在或过大") }
        ZipFile(source).use { archive ->
            val entries = archive.entries().asSequence().toList()
            require(entries.size <= FILE_LIMIT) { throw LuaEditorIssue("issue.file_count", "文件过多") }
            var total = 0L
            val paths = hashSetOf<String>()
            entries.forEach { entry ->
                val path = safePath(entry.name.trimEnd('/'))
                require(paths.add(path.lowercase())) { throw LuaEditorIssue("issue.duplicate_file", "文件名重复") }
                if (entry.isDirectory) target.resolve(path).mkdirs() else {
                    val file = target.resolve(path).also { it.parentFile.mkdirs() }
                    archive.getInputStream(entry).use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(8192); var bytes = 0L
                        while (true) { val read = input.read(buffer); if (read < 0) break
                            total += read; bytes += read
                            require(total <= TOTAL_LIMIT && bytes <= 8 * 1024 * 1024L) { throw LuaEditorIssue("issue.resource_size", "资源过大") }
                            output.write(buffer, 0, read)
                        }
                    } }
                }
            }
        }
    }
    private fun safePath(path: String): String {
        require(path.isNotBlank() && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') && !path.startsWith('/')) { throw LuaEditorIssue("issue.invalid_path", "无效资源路径") }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { throw LuaEditorIssue("issue.outside_path", "路径越界") }
        require(path == "manifest.json" || path == "main.lua" || path == "assets" || path.startsWith("assets/")) { throw LuaEditorIssue("issue.package_files", "只允许 manifest.json、main.lua 与 assets") }
        return path
    }
    private fun manifest(value: JsonObject): LuaManifest {
        val id = safeId(value.getValue("id").jsonPrimitive.content)
        val name = value.getValue("name").jsonPrimitive.content
        val version = value.getValue("version").jsonPrimitive.content
        require(name.length in 1..64 && version.length in 1..32) { throw LuaEditorIssue("issue.metadata", "名称或版本无效") }
        val permissions = value["permissions"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        require(PERMISSIONS.containsAll(permissions)) { throw LuaEditorIssue("issue.unsupported_permission", "申请了不支持的权限") }
        val purposes = value["permissionPurposes"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        require(purposes.size <= permissions.size && purposes.all { (key, text) -> key in permissions && text.length in 1..600 }) { throw LuaEditorIssue("issue.permission_purpose", "权限用途声明无效") }
        val icon = value["icon"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        require(icon == null || icon.matches(Regex("[a-z][a-z0-9_]{0,79}"))) { throw LuaEditorIssue("issue.metadata", "名称或图标无效") }
        return LuaManifest(id, name, version, permissions, purposes, icon)
    }
    internal fun readManifest(file: File): LuaManifest {
        require(file.isFile && file.length() in 1..(16L * 1024)) { throw LuaEditorIssue("issue.manifest", "缺少有效 manifest.json") }
        return manifest(PluginJson.parseToJsonElement(utf8(file.readBytes())).jsonObject)
    }

    @Synchronized fun updatePresentation(id: String, name: String? = null, icon: String? = null): LuaProject {
        val current = project(id)
        verify(current)
        val file = code(id).resolve("manifest.json")
        val raw = utf8(file.readBytes())
        val changes = PluginJson.parseToJsonElement(raw).jsonObject.toMutableMap()
        name?.trim()?.let { require(it.isNotBlank()); changes["name"] = JsonPrimitive(it) }
        icon?.trim()?.let { if (it.isEmpty()) changes.remove("icon") else changes["icon"] = JsonPrimitive(it) }
        val updated = manifest(JsonObject(changes))
        try {
            file.writeText(JsonObject(changes).toString(), Charsets.UTF_8)
            val next = current.copy(manifest = updated, hash = hash(code(id)))
            records[id] = next
            save()
            return next
        } catch (failure: Exception) {
            file.writeText(raw, Charsets.UTF_8)
            records[id] = current
            throw failure
        }
    }
    private fun safeId(id: String) = id.also { require(ID.matches(id)) { throw LuaEditorIssue("issue.invalid_id", "无效工具 ID") } }
    private fun hash(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.walkTopDown().filter { it != directory }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
            digest.update((file.relativeTo(directory).invariantSeparatorsPath + if (file.isDirectory) "/" else "").toByteArray()); digest.update(0)
            if (file.isFile) file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun save() {
        val index = storage.file("config/projects.json").also { it.parentFile.mkdirs() }
        val temp = index.resolveSibling("projects.pending.json")
        temp.writeText(JsonArray(records.values.map { record -> buildJsonObject {
            put("manifest", buildJsonObject { put("id", record.manifest.id); put("name", record.manifest.name); put("version", record.manifest.version)
                put("permissions", JsonArray(record.manifest.permissions.sorted().map(::JsonPrimitive))); put("permissionPurposes", JsonObject(record.manifest.permissionPurposes.mapValues { JsonPrimitive(it.value) }))
                record.manifest.icon?.let { put("icon", it) } })
            put("enabled", record.enabled); put("hash", record.hash); put("error", record.error)
        } }).toString(), Charsets.UTF_8)
        java.nio.file.Files.move(temp.toPath(), index.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    private fun File.resolveSibling(name: String) = File(parentFile, name)
    private fun utf8(bytes: ByteArray): String {
        require(bytes.none { it == 0.toByte() } && bytes.firstOrNull() != 27.toByte()) { throw LuaEditorIssue("issue.utf8", "只接受 UTF-8 源文件") }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }
}
