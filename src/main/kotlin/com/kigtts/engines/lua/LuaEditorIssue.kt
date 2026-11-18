package com.kigtts.engines.lua

internal class LuaEditorIssue(
    val key: String,
    fallback: String,
    vararg values: Pair<String, String>,
) : IllegalArgumentException(fallback) {
    val arguments = values.toMap()
}
