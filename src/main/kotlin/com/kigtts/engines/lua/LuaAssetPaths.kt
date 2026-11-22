package com.kigtts.engines.lua

import com.kigtts.plugin.api.PluginStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class LuaAssetPaths(private val storage: PluginStorage) {
    // The host's browser and media renderer resolve PluginResource under the plugin's storage.
    // Mount one immutable snapshot per package generation so HTML and its assets stay together.
    @Synchronized fun mount(sourceDirectory: File): PluginStorage {
        val generation = sourceDirectory.parentFile?.parentFile?.name.orEmpty()
        require(generation.matches(Regex("[A-Za-z0-9_.-]+")) && generation !in setOf(".", ".."))
        val source = sourceDirectory.resolve("assets")
        require(source.isDirectory)
        val directory = storage.directory("lua-assets")
        val destination = storage.file("lua-assets/$generation")
        if (!destination.isDirectory) {
            val temporary = Files.createTempDirectory(directory.toPath(), "assets-").toFile()
            try {
                check(source.copyRecursively(temporary, overwrite = true))
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.deleteRecursively() }
        }
        return PluginStorage(destination)
    }
}
