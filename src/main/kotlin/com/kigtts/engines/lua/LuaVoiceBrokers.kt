package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File

internal suspend fun luaSpeechBroker(broker: SpeechBroker, data: PluginStorage, request: JsonObject): JsonElement =
    when (request.getValue("operation").jsonPrimitive.content) {
        "providers" -> PluginJson.encodeToJsonElement(ListSerializer(TtsProvider.serializer()), broker.providers())
        "voice" -> PluginJson.encodeToJsonElement<PluginOutcome>(
            broker.command(request.providerId(), PluginJson.decodeFromJsonElement(request.getValue("command"))))
        "control" -> {
            broker.control(request.providerId(), PluginJson.decodeFromJsonElement(request.getValue("command")))
            JsonPrimitive(true)
        }
        "synthesize" -> {
            val target = data.file(request.getValue("output").jsonPrimitive.content)
            val parent = requireNotNull(target.parentFile).apply { mkdirs() }
            val stage = File.createTempFile("speech-", ".part", parent)
            try {
                var format: AudioFormat? = null
                var notice: String? = null
                stage.outputStream().buffered().use { output ->
                    var size = 0L
                    broker.synthesize(request.providerId(), PluginJson.decodeFromJsonElement(request.getValue("request"))).collect { event ->
                        when (event) {
                            is SynthesisEvent.Audio -> {
                                val chunk = AudioFormat(event.audio.sampleRate, event.audio.channels)
                                require(format == null || format == chunk)
                                format = chunk
                                size += event.audio.bytes.size
                                require(size <= LuaSpeech.MAX_PCM)
                                output.write(event.audio.bytes)
                            }
                            is SynthesisEvent.Finished -> notice = event.notice
                            SynthesisEvent.Started -> Unit
                        }
                    }
                }
                java.nio.file.Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                buildJsonObject {
                    put("format", PluginJson.encodeToJsonElement(requireNotNull(format)))
                    notice?.let { put("notice", it) }
                }
            } finally { stage.delete() }
        }
        else -> error("Unknown speech operation")
    }

internal suspend fun luaRecognitionBroker(broker: RecognitionBroker, data: PluginStorage, request: JsonObject): JsonElement =
    when (request.getValue("operation").jsonPrimitive.content) {
        "providers" -> PluginJson.encodeToJsonElement(ListSerializer(AsrProvider.serializer()), broker.providers())
        "control" -> JsonPrimitive(broker.control(request.providerId(), PluginJson.decodeFromJsonElement(request.getValue("command"))))
        "recognize" -> {
            val input = data.file(request.getValue("input").jsonPrimitive.content)
            require(input.isFile && input.length() <= LuaSpeech.MAX_PCM)
            val format = PluginJson.decodeFromJsonElement<AudioFormat>(request.getValue("format"))
            val config = request["config"]?.let { PluginJson.decodeFromJsonElement<RecognitionConfig>(it) } ?: RecognitionConfig()
            PluginJson.encodeToJsonElement(broker.recognize(request.providerId(),
                PcmAudio(input.readBytes(), format.sampleRate, format.channels), config))
        }
        else -> error("Unknown recognition operation")
    }

private fun JsonObject.providerId() = getValue("pluginId").jsonPrimitive.content
