package com.kigtts.engines.lua

import com.kigtts.plugin.api.AppCommand
import com.kigtts.plugin.api.AppReply
import com.kigtts.plugin.api.PluginEnvironment
import com.kigtts.plugin.api.PluginOutcome
import com.kigtts.plugin.api.PluginResource
import kotlinx.serialization.json.*

internal object NativeBuilderLink {
    private const val ID = "tools.native-builder"
    private const val PACKAGE = "tools.native-builder-0.8.5.kigplugin"
    private const val HASH = "a85c5012779722ec55627524bd680ae47743507c5071fdc5d0956451bf826331"
    private const val PUBLISHER = "b5927c76c21539ef99352707c215b3ce48f3fed937a6f995d39a3c677a052ab5"
    private const val PENDING_IMPORT = "config/native-project-import.txt"

    suspend fun open(environment: PluginEnvironment, action: String): PluginOutcome {
        val broker = requireNotNull(environment.appBroker) { throw LuaEditorIssue("issue.builder_unavailable", "构建扩展暂不可用") }
        if (isReady(broker) && broker.request(AppCommand.PluginAction(ID, action)) is AppReply.Data) return PluginOutcome.RefreshUi
        return download(environment, action)
    }

    suspend fun importProject(environment: PluginEnvironment, path: String): PluginOutcome {
        require(path.startsWith("imports/") && environment.storage.file(path).resolve("plugin-spec.json").isFile)
        val pending = environment.storage.file(PENDING_IMPORT)
        requireNotNull(pending.parentFile).mkdirs()
        pending.writeText(path, Charsets.UTF_8)
        return if (isReady(requireNotNull(environment.appBroker))) resumeImport(environment) else download(environment, "import")
    }

    suspend fun resumeImport(environment: PluginEnvironment): PluginOutcome {
        val pending = environment.storage.file(PENDING_IMPORT)
        val path = pending.readText(Charsets.UTF_8)
        require(path.startsWith("imports/") && environment.storage.file(path).resolve("plugin-spec.json").isFile)
        val result = requireNotNull(environment.appBroker).request(AppCommand.DeliverResource(ID, PluginResource(path), "import"))
        if (result !is AppReply.Data) throw LuaEditorIssue("issue.builder_unavailable", "构建扩展暂不可用")
        pending.delete()
        return PluginOutcome.RefreshUi
    }

    private suspend fun isReady(broker: com.kigtts.plugin.api.AppBroker): Boolean {
        val catalog = (broker.request(AppCommand.Describe) as? AppReply.Data)?.value?.get("plugins") as? JsonArray
        val installed = catalog?.mapNotNull { it as? JsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.content == ID }
        return (installed?.get("versionCode")?.jsonPrimitive?.intOrNull ?: 0) >= 6 &&
            installed?.get("enabled")?.jsonPrimitive?.booleanOrNull == true &&
            installed["publisherSha256"]?.jsonPrimitive?.contentOrNull == PUBLISHER
    }

    private suspend fun download(environment: PluginEnvironment, action: String): PluginOutcome {
        val path = "downloads/native-$action/$PACKAGE"
        environment.download("https://github.com/KIGTTS/plugin-native-builder/releases/download/v0.8.5/$PACKAGE", path, HASH)
        return PluginOutcome.InstallPackage(PluginResource(path))
    }
}
