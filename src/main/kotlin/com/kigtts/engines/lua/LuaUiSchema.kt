package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*

/** Friendly JSON schema, mapped only to the host's native components and namespaced actions. */
object LuaUiSchema {
    private val localId = Regex("[A-Za-z0-9_.-]{1,48}")
    fun route(module: String, id: String): String { require(localId.matches(id)); return "module/$module/$id" }
    fun contributions(module: String, value: JsonElement): PluginContributions {
        val data = value.jsonObject
        val actions = array(data["actions"]).map { element ->
            val obj = element.jsonObject
            UiAction(route(module, obj.string("id")), obj.string("label"), enumValueOf(obj.string("slot")),
                obj.icon(), obj["presentation"]?.jsonPrimitive?.content?.let { enumValueOf<UiPresentation>(it) } ?: UiPresentation.BUTTON,
                obj["order"]?.jsonPrimitive?.int ?: 0, obj["buttonStyle"]?.jsonPrimitive?.content?.let { enumValueOf<UiButtonStyle>(it) } ?: UiButtonStyle.ICON, symbol = obj.symbol())
        }
        val entries = array(data["drawerEntries"]).map { element ->
            val obj = element.jsonObject
            DrawerEntry(route(module, obj.string("id")), obj.string("label"), route(module, obj.string("pageId")), obj.icon(), obj["settingsPage"]?.jsonPrimitive?.boolean ?: false)
        }
        val settings = array(data["settingsEntries"]).map { element ->
            val obj = element.jsonObject
            SettingsEntry(route(module, obj.string("id")), obj.string("label"), route(module, obj.string("pageId")),
                obj["icon"]?.jsonPrimitive?.content?.let { enumValueOf<PluginIcon>(it) } ?: PluginIcon.SETTINGS,
                obj["cardId"]?.jsonPrimitive?.content ?: "extensions", obj["cardTitle"]?.jsonPrimitive?.content ?: "功能与扩展",
                obj["summary"]?.jsonPrimitive?.content.orEmpty(), obj["order"]?.jsonPrimitive?.int ?: 0, obj.symbol())
        }
        require(actions.size <= 12 && entries.size <= 2 && settings.size <= 2) { "每个工具最多 12 个按钮、2 个抽屉入口和 2 个设置入口" }
        return PluginContributions(actions = actions, drawerEntries = entries, settingsEntries = settings)
    }
    fun pages(module: String, value: JsonElement): List<PluginPage> {
        val pages = array(value).map { element -> val obj = element.jsonObject
            PluginPage(route(module, obj.string("id")), obj.string("title"), blocks(module, array(obj["blocks"]), 0))
        }
        require(pages.size <= 2 && pages.map { it.id }.distinct().size == pages.size)
        return pages
    }
    fun outcome(module: String, value: JsonElement, permissions: Set<String>): PluginOutcome {
        if (value == JsonNull) return PluginOutcome.RefreshUi
        val obj = value.jsonObject
        return when (obj.string("type")) {
            "message" -> PluginOutcome.Message(obj.string("text").take(1000))
            "dialog" -> PluginOutcome.Dialog(obj.string("title").take(160), obj.string("text").take(16000))
            "open-page" -> PluginOutcome.OpenPage(route(module, obj.string("pageId")))
            "replace-text" -> { require("app-control" in permissions); PluginOutcome.ReplaceEditorText(obj.string("text").take(64000)) }
            "menu" -> PluginOutcome.Menu(obj.string("title").take(160), array(obj["items"]).map { action(module, it.jsonObject) }.also { require(it.size <= 12) })
            "refresh" -> PluginOutcome.RefreshUi
            else -> error("不支持的返回操作")
        }
    }
    private fun blocks(module: String, values: List<JsonElement>, depth: Int): List<PageBlock> {
        require(depth <= 4 && values.size <= 24)
        return values.map { element -> val obj = element.jsonObject
            when (obj.string("type")) {
                "text" -> PageBlock.Text(obj.string("text").take(16000), obj["heading"]?.jsonPrimitive?.boolean ?: false)
                "notice" -> PageBlock.Notice(obj.string("text").take(16000))
                "actions" -> PageBlock.Actions(array(obj["actions"]).map { action(module, it.jsonObject) }.also { require(it.size <= 12) })
                "group" -> PageBlock.Group(obj.string("title").take(160), blocks(module, array(obj["children"]), depth + 1))
                "input" -> PageBlock.Input(obj.string("title").take(160), obj["value"]?.jsonPrimitive?.content.orEmpty().take(64000),
                    route(module, obj.string("actionId")), obj["secret"]?.jsonPrimitive?.boolean ?: false, obj["multiline"]?.jsonPrimitive?.boolean ?: false, obj.icon(), symbol = obj.symbol())
                "toggle" -> PageBlock.Toggle(obj.string("title").take(160), obj["checked"]?.jsonPrimitive?.boolean ?: false,
                    route(module, obj.string("actionId")), obj["summary"]?.jsonPrimitive?.content.orEmpty().take(1000), obj.icon(), symbol = obj.symbol())
                "choice" -> PageBlock.Choice(obj.string("title").take(160), array(obj["options"]).map { action(module, it.jsonObject) }.also { require(it.size in 1..12) },
                    route(module, obj.string("selected")), obj.icon(), symbol = obj.symbol())
                else -> error("不支持的页面组件")
            }
        }
    }
    private fun action(module: String, obj: JsonObject) = PageAction(route(module, obj.string("id")), obj.string("label").take(160), obj.icon(), symbol = obj.symbol())
    private fun JsonObject.symbol() = this["symbol"]?.jsonPrimitive?.content?.also { require(it.matches(Regex("[a-z0-9_]{1,80}"))) }
    private fun JsonObject.icon() = this["icon"]?.jsonPrimitive?.content?.let { enumValueOf<PluginIcon>(it) } ?: PluginIcon.INFO
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun array(value: JsonElement?): List<JsonElement> = when (value) { null, JsonNull -> emptyList(); is JsonArray -> value; is JsonObject -> { require(value.isEmpty()); emptyList() }; else -> error("需要数组") }
}
