package com.kigtts.engines.lua

import com.kigtts.plugin.api.PLUGIN_API_VERSION
import com.kigtts.plugin.api.PluginEnvironment
import com.kigtts.plugin.api.AppCommand
import com.kigtts.plugin.api.AppReply
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class LuaPackageWriter(private val environment: PluginEnvironment, private val sources: LuaSourceStore) {
    suspend fun write(project: LuaProject, development: Boolean = false): File {
        sources.verify(project)
        val source = sources.code(project.manifest.id)
        val immutable = requireNotNull(environment.assets.parentFile)
        val code = immutable.resolve("code").listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("classes([2-9]|[1-9][0-9]+)?\\.dex")) }.sortedBy { it.name }
        require(code.any { it.name == "classes.dex" })
        val payload = linkedMapOf<String, File>()
        code.forEach { payload["code/${it.name}"] = it }
        source.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(source).invariantSeparatorsPath }.forEach {
            payload["assets/lua-project/${it.relativeTo(source).invariantSeparatorsPath}"] = it
        }
        val licenses = environment.assets.resolve("licenses")
        if (licenses.isDirectory) licenses.walkTopDown().filter(File::isFile).forEach {
            payload["assets/licenses/${it.relativeTo(licenses).invariantSeparatorsPath}"] = it
        }
        val metadata = project.manifest
        val directory = environment.storage.directory(if (development) "debug-builds" else "exports")
        val checkpoint = environment.storage.file("config/export-${metadata.id}.txt")
        val latest = directory.listFiles().orEmpty().mapNotNull { file ->
            file.nameWithoutExtension.removePrefix("${metadata.id}-").toIntOrNull()
                ?.takeIf { file.name.startsWith("${metadata.id}-") && file.extension == "kigplugin" }
        }.maxOrNull() ?: 0
        val codeNumber = maxOf(latest, checkpoint.takeIf(File::isFile)?.readText()?.toIntOrNull() ?: 0) + 1
        val raw = buildJsonObject {
            put("id", metadata.id)
            put("name", metadata.name)
            put("version", metadata.version)
            put("versionCode", codeNumber)
            put("entryClass", LuaPackagedPlugin::class.java.name)
            put("apiVersion", PLUGIN_API_VERSION)
            put("minSdk", 26)
            put("runtime", "android-dex")
            put("license", "MIT")
            put("capabilities", JsonArray((metadata.permissions + setOf("ui", "resources")).sorted().map(::JsonPrimitive)))
            put("permissionPurposes", JsonObject(metadata.permissionPurposes.mapValues { JsonPrimitive(it.value) }))
            if (metadata.author.isNotBlank()) put("author", metadata.author)
            metadata.icon?.let { icon -> put("icon", buildJsonObject { put("symbol", icon) }) }
            metadata.iconPath?.let { path ->
                val signedPath = "assets/lua-project/$path"
                require(payload[signedPath]?.isFile == true)
                put("icon", buildJsonObject { put("path", signedPath) })
            }
            put("files", JsonObject(payload.mapValues { (_, file) -> JsonPrimitive(sha256(file)) }))
        }.toString().toByteArray(Charsets.UTF_8)
        val (publicKey, signature) = sign(metadata.id, raw)
        val output = directory.resolve("${metadata.id}-$codeNumber.kigplugin")
        val temp = File.createTempFile("pack-", ".part", directory)
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { archive ->
                fun entry(path: String, bytes: ByteArray) {
                    archive.putNextEntry(ZipEntry(path))
                    archive.write(bytes)
                    archive.closeEntry()
                }
                entry("plugin.json", raw)
                entry("signature/public.der", publicKey)
                entry("signature/manifest.sig", signature)
                payload.forEach { (path, file) ->
                    archive.putNextEntry(ZipEntry(path))
                    file.inputStream().use { it.copyTo(archive) }
                    archive.closeEntry()
                }
            }
            requireNotNull(checkpoint.parentFile).mkdirs()
            val revision = File.createTempFile("version-", ".part", checkpoint.parentFile)
            try {
                revision.writeText(codeNumber.toString(), Charsets.UTF_8)
                java.nio.file.Files.move(revision.toPath(), checkpoint.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { revision.delete() }
            check(temp.renameTo(output))
            if (development) directory.listFiles().orEmpty().filter { it != output &&
                it.extension == "kigplugin" && it.nameWithoutExtension.removePrefix("${metadata.id}-").toIntOrNull() != null
                && it.name.startsWith("${metadata.id}-") }.forEach(File::delete)
            return output
        } finally { temp.delete() }
    }

    private suspend fun sign(id: String, raw: ByteArray): Pair<ByteArray, ByteArray> {
        val coder = java.util.Base64.getEncoder()
        val reply = environment.appBroker?.request(AppCommand.ManagedCall(id, "editor-signing", buildJsonObject {
            put("manifest", coder.encodeToString(raw))
        }))
        if (reply is AppReply.Failure) throw IllegalStateException(reply.message)
        if (reply is AppReply.Data && reply.value["default"]?.jsonPrimitive?.booleanOrNull != true) {
            val decoder = java.util.Base64.getDecoder()
            return decoder.decode(reply.value.getValue("publicKey").jsonPrimitive.content) to
                decoder.decode(reply.value.getValue("signature").jsonPrimitive.content)
        }
        val signer = publisher()
        return signer.public.encoded to Signature.getInstance("SHA256withRSA").apply { initSign(signer.private); update(raw) }.sign()
    }

    private fun publisher(): KeyPair {
        val config = environment.storage.configuration
        val index = config.resolve("lua-publisher.json")
        val factory = KeyFactory.getInstance("RSA")
        val base64 = java.util.Base64.getEncoder()
        val decoder = java.util.Base64.getDecoder()
        if (index.isFile) {
            val previous = Json.parseToJsonElement(index.readText(Charsets.UTF_8)).jsonObject
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(decoder.decode(previous.getValue("public").jsonPrimitive.content))),
                factory.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(previous.getValue("private").jsonPrimitive.content))))
        }
        val signer = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val stage = File.createTempFile("publisher-", ".tmp", config)
        try {
            stage.writeText(buildJsonObject {
                put("public", base64.encodeToString(signer.public.encoded))
                put("private", base64.encodeToString(signer.private.encoded))
            }.toString(), Charsets.UTF_8)
            check(stage.renameTo(index))
        } finally { stage.delete() }
        return signer
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
