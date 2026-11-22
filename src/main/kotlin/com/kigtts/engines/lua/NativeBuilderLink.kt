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

    suspend fun open(environment: PluginEnvironment, action: String): PluginOutcome {
        val broker = requireNotNull(environment.appBroker) { throw LuaEditorIssue("issue.builder_unavailable", "构建扩展暂不可用") }
        val catalog = (broker.request(AppCommand.Describe) as? AppReply.Data)?.value?.get("plugins") as? JsonArray
        val installed = catalog?.mapNotNull { it as? JsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.content == ID }
        val version = installed?.get("versionCode")?.jsonPrimitive?.intOrNull ?: 0
        if (version >= 6 && broker.request(AppCommand.PluginAction(ID, action)) is AppReply.Data) return PluginOutcome.RefreshUi
        val path = "downloads/native-$action/$PACKAGE"
        environment.download("https://github.com/KIGTTS/plugin-native-builder/releases/download/v0.8.5/$PACKAGE", path, HASH)
        return PluginOutcome.InstallPackage(PluginResource(path))
    }
}
