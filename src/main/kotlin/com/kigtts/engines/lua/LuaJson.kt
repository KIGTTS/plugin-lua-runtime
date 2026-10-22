package com.kigtts.engines.lua

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.json.*
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.util.IdentityHashMap

/** Data-only bridge. Userdata and executable functions never cross into the host. */
object LuaJson {
    const val LIMIT = 256 * 1024
    fun toLua(value: JsonElement): LuaValue = when (value) {
        JsonNull -> LuaValue.NIL
        is JsonObject -> LuaTable().also { table -> value.forEach { (key, item) -> table.set(key, toLua(item)) } }
        is JsonArray -> LuaTable().also { table -> value.forEachIndexed { index, item -> table.set(index + 1, toLua(item)) } }
        is JsonPrimitive -> when {
            value.isString -> LuaValue.valueOf(value.content)
            value.booleanOrNull != null -> LuaValue.valueOf(value.boolean)
            else -> LuaValue.valueOf(value.double)
        }
    }
    fun fromLua(value: LuaValue): JsonElement {
        val seen = IdentityHashMap<LuaValue, Boolean>()
        var nodes = 0
        fun convert(item: LuaValue, depth: Int): JsonElement {
            require(depth <= 24 && ++nodes <= 10000) { "返回的数据过多" }
            return when {
                item.isnil() -> JsonNull
                item.isboolean() -> JsonPrimitive(item.toboolean())
                item.type() == LuaValue.TNUMBER -> item.todouble().let {
                    require(it.isFinite())
                    if (it % 1.0 == 0.0 && it >= Long.MIN_VALUE && it < Long.MAX_VALUE.toDouble()) JsonPrimitive(it.toLong()) else JsonPrimitive(it)
                }
                item.type() == LuaValue.TSTRING -> item.checkjstring().let { require(it.toByteArray().size <= LIMIT); JsonPrimitive(it) }
                item.istable() -> {
                    require(seen.put(item, true) == null) { "不允许循环表" }
                    val table = item.checktable()
                    val keys = table.keys()
                    require(keys.size <= 4096)
                    val isArray = keys.isNotEmpty() && keys.all { it.isint() && it.toint() in 1..keys.size }
                    val result = if (isArray) JsonArray((1..keys.size).map { convert(table.rawget(it), depth + 1) })
                    else JsonObject(keys.associate { key -> require(key.type() == LuaValue.TSTRING); key.checkjstring() to convert(table.rawget(key), depth + 1) })
                    seen.remove(item); result
                }
                else -> error("只允许 JSON 数据")
            }
        }
        val result = convert(value, 0)
        require(result.toString().toByteArray().size <= LIMIT) { "返回的数据过大" }
        return result
    }
    fun result(value: LuaValue): JsonElement = if (value.type() == LuaValue.TSTRING) {
        val json = value.checkjstring(); require(json.toByteArray().size <= LIMIT); PluginJson.parseToJsonElement(json)
    } else fromLua(value)
}
