package com.kigtts.engines.lua

import com.kigtts.plugin.api.PluginStorage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal suspend fun luaHttp(input: JsonObject, data: PluginStorage): JsonObject {
    val address = URI(input.getValue("url").jsonPrimitive.content)
    require(address.scheme.equals("https", ignoreCase = true) && !address.host.isNullOrBlank()
        && address.userInfo == null && address.fragment == null)
    val method = input["method"]?.jsonPrimitive?.content ?: "GET"
    require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"))
    val connection = address.toURL().openConnection() as HttpsURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        val headers = input["headers"]?.jsonObject.orEmpty()
        require(headers.size <= 32)
        headers.forEach { (name, primitive) ->
            val value = primitive.jsonPrimitive.content
            require(name.matches(Regex("[a-zA-Z0-9-]{1,80}")) && value.length <= 4096 && value.none { it == '\r' || it == '\n' })
            require(name.lowercase() !in setOf("host", "content-length", "transfer-encoding", "connection"))
            connection.setRequestProperty(name, value)
        }
        val body = input["body"]?.jsonPrimitive?.contentOrNull?.toByteArray(Charsets.UTF_8)
        val file = input["bodyPath"]?.jsonPrimitive?.contentOrNull?.let(data::file)
        if (body != null || file != null) {
            require(method !in setOf("GET", "HEAD"))
            require(body == null || file == null)
            val size = file?.length() ?: requireNotNull(body).size.toLong()
            require(size <= LuaSpeech.MAX_PCM && (file == null || file.isFile))
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(size)
            connection.outputStream.use { output ->
                if (file != null) file.inputStream().use { it.copyTo(output) }
                else output.write(requireNotNull(body))
            }
        }
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        val context = currentCoroutineContext()
        val target = input["output"]?.jsonPrimitive?.contentOrNull?.let(data::file)
        val bodyBuffer = if (target == null) ByteArrayOutputStream() else null
        val stage = target?.let { fileTarget ->
            val parent = requireNotNull(fileTarget.parentFile).apply { mkdirs() }
            java.io.File.createTempFile("response-", ".part", parent)
        }
        try {
            val limit = if (stage == null) (LuaJson.LIMIT / 4).toLong() else LuaSpeech.MAX_PCM
            (stage?.outputStream() ?: requireNotNull(bodyBuffer)).use { output ->
                stream?.use {
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        context.ensureActive()
                        val count = it.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= limit)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (stage != null) java.nio.file.Files.move(stage.toPath(), requireNotNull(target).toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { stage?.delete() }
        return buildJsonObject {
            put("status", code)
            connection.contentType?.let { put("contentType", it.take(512)) }
            bodyBuffer?.let { put("body", it.toString("UTF-8")) }
        }
    } finally { connection.disconnect() }
}
