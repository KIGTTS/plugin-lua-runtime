package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.File

internal class LuaInspectionFailure(val phase: String, cause: Throwable) : Exception(cause.message, cause)

/** Run the same readonly native UI contract before replacing an installed project's code. */
internal suspend fun inspectLuaProject(executor: LuaExecutor, project: LuaProject, source: File, context: PluginContext): JsonElement {
    return try { inspectLuaContent(executor, project, source, context) }
    catch (failure: LuaInspectionFailure) {
        if (!failure.message.orEmpty().contains("PERMISSION_REQUIRED")) throw failure
        buildJsonObject { put("ok", true); put("phase", failure.phase); put("permissionPending", true)
            put("message", "语法通过；涉及权限的页面或自检需在用户授权后继续核对") }
    }
}
private suspend fun inspectLuaContent(executor: LuaExecutor, project: LuaProject, source: File, context: PluginContext): JsonElement {
    suspend fun <T> check(phase: String, block: suspend () -> T): T = try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { throw LuaInspectionFailure(phase, failure) }
        catch (failure: LuaBudgetExceeded) { throw LuaInspectionFailure(phase, failure) }
    check("syntax") { executor.validate(source.resolve("main.lua").readText(Charsets.UTF_8)) }
    val contributions = check("contributions") { LuaUiSchema.contributions(project.manifest.id,
        executor.call(project, "contributions", context, sourceDirectory = source)) }
    val pages = check("pages") { LuaUiSchema.pages(project.manifest.id, executor.call(project, "pages", context, sourceDirectory = source)) }
    check("entry-pages") {
        require((contributions.settingsEntries.map { it.pageId } + contributions.drawerEntries.map { it.pageId }).all { id -> pages.any { it.id == id } }) {
            "页面入口没有对应页面；pages必须直接返回数组"
        }
        PluginSnapshot(PluginDescriptor("runtime.lua", "Lua", "检查"), contributions, pages)
    }
    val selfTest = check("test") {
        executor.call(project, "test", context, sourceDirectory = source).also { result ->
            require((result as? JsonPrimitive)?.booleanOrNull != false &&
                (result as? JsonObject)?.get("ok")?.jsonPrimitive?.booleanOrNull != false) { "test(ctx)返回失败：${result.toString().take(1000)}" }
        }
    }
    return buildJsonObject { put("ok", true); put("phase", "complete"); put("pages", pages.size); put("test", selfTest) }
}

internal fun luaDiagnostic(error: Throwable, phase: String, project: String? = null): JsonObject = buildJsonObject {
    val actualPhase = (error as? LuaInspectionFailure)?.phase ?: phase
    val unknown = actualPhase in setOf("execute", "refresh")
    put("ok", false); put("code", if (unknown) "LUA_EXECUTION_FAILED" else "LUA_VALIDATION_FAILED")
    put("phase", actualPhase)
    project?.let { put("project", it) }
    val detail = (error.message ?: error.javaClass.simpleName).take(4000)
    put("error", detail)
    Regex("(?:main\\.lua|assets/lua/[^:]+):([0-9]+)").find(detail)?.groupValues?.get(1)?.toIntOrNull()?.let { put("line", it) }
    put("retryable", !unknown); put("outcomeUnknown", unknown)
}

internal fun luaResultDialog(value: JsonElement): PluginOutcome.Dialog {
    val raw = value.toString()
    val result = if (raw.length <= 16000) raw else buildJsonObject {
        put("ok", (value as? JsonObject)?.get("ok") ?: JsonPrimitive(true))
        put("truncated", true); put("resultPreview", raw.take(6000))
    }.toString()
    return PluginOutcome.Dialog("Lua 结果", result)
}
