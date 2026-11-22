package com.kigtts.engines.lua

import com.kigtts.plugin.api.AppCommand
import com.kigtts.plugin.api.AppReply
import com.kigtts.plugin.api.PluginEnvironment
import com.kigtts.plugin.api.PluginOutcome
import com.kigtts.plugin.api.PluginResource
import kotlinx.serialization.json.*

internal object NativeBuilderLink {
    private const val ID = "tools.native-builder"
    private const val PACKAGE = "tools.native-builder-0.8.3.kigplugin"
    private const val HASH = "5bd08ed36e477ffc9ad3ec4a1c29cbab6b9ef2b5aad9f574a50407b17fb90c95"

    suspend fun open(environment: PluginEnvironment, action: String): PluginOutcome {
        val broker = requireNotNull(environment.appBroker) { throw LuaEditorIssue("issue.builder_unavailable", "构建扩展暂不可用") }
        val catalog = (broker.request(AppCommand.Describe) as? AppReply.Data)?.value?.get("plugins") as? JsonArray
        val installed = catalog?.mapNotNull { it as? JsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.content == ID }
        val version = installed?.get("versionCode")?.jsonPrimitive?.intOrNull ?: 0
        if (version >= 4 && broker.request(AppCommand.PluginAction(ID, action)) is AppReply.Data) return PluginOutcome.RefreshUi
        val path = "downloads/native-$action/$PACKAGE"
        environment.download("https://github.com/KIGTTS/plugin-native-builder/releases/download/v0.8.3/$PACKAGE", path, HASH)
        return PluginOutcome.InstallPackage(PluginResource(path))
    }
}
