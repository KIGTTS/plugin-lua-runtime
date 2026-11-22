package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.File
import java.util.Base64

internal class LuaSpeech(
    private val data: PluginStorage,
    private val permissions: Set<String>,
    private val invoke: suspend (String, JsonElement?) -> JsonElement,
) {
    @Volatile private var synthesis: TtsInfo? = null
    @Volatile private var recognition: AsrInfo? = null

    suspend fun refresh() {
        val capabilities = (invoke("engines", null) as? JsonObject).orEmpty()
        synthesis = capabilities["tts"]?.let { PluginJson.decodeFromJsonElement<TtsInfo>(it) }?.also {
            require("tts" in permissions)
            require(it.role != TtsRole.SERVICE || "speech-service" in permissions)
            require(!(it.managesVoices || it.importsVoicePacks) || "voice-packs" in permissions)
        }
        recognition = capabilities["asr"]?.let { PluginJson.decodeFromJsonElement<AsrInfo>(it) }?.also {
            require(it.role != AsrRole.SERVICE || "asr-service" in permissions)
            require(!it.batch || "asr-batch" in permissions)
            require(when (it.streaming) {
                StreamingKind.NONE -> true
                StreamingKind.SEGMENTED -> "asr-segmented" in permissions
                StreamingKind.INCREMENTAL -> "asr-incremental" in permissions
            })
        }
    }

    val tts: TtsEngine? get() = synthesis?.let { ttsAdapter }
    val asr: AsrEngine? get() = recognition?.let { asrAdapter }
    val voices: VoiceManager? get() = synthesis?.takeIf { it.managesVoices || it.importsVoicePacks }?.let {
        object : VoiceManager {
            override suspend fun command(command: VoiceCommand): PluginOutcome {
                val result = invoke("voice_command", PluginJson.encodeToJsonElement(command))
                return PluginJson.decodeFromJsonElement(result)
            }
        }
    }

    private val ttsAdapter = object : TtsEngine {
        override val info get() = requireNotNull(synthesis)
        override fun synthesize(request: TtsRequest): Flow<SynthesisEvent> = flow {
            val output = File.createTempFile("tts-", ".pcm", data.temporary)
            try {
                val target = output.relativeTo(data.root).invariantSeparatorsPath
                emit(SynthesisEvent.Started)
                val result = invoke("synthesize", buildJsonObject {
                    put("request", PluginJson.encodeToJsonElement(request))
                    put("output", target)
                }).jsonObject
                val format = PluginJson.decodeFromJsonElement<AudioFormat>(result.getValue("format"))
                require(output.length() in 1..MAX_PCM && output.length() % (2 * format.channels) == 0L)
                var remaining = output.length()
                DataInputStream(output.inputStream().buffered()).use { input ->
                    while (remaining > 0) {
                        val bytes = ByteArray(minOf(remaining, CHUNK.toLong()).toInt())
                        input.readFully(bytes)
                        remaining -= bytes.size
                        emit(SynthesisEvent.Audio(PcmAudio(bytes, format.sampleRate, format.channels)))
                    }
                }
                emit(SynthesisEvent.Finished(result["notice"]?.jsonPrimitive?.contentOrNull))
            } finally { output.delete() }
        }.flowOn(Dispatchers.IO)

        override suspend fun control(command: TtsControl) {
            invoke("tts_control", PluginJson.encodeToJsonElement(command))
        }
    }

    private val asrAdapter = object : ControlledAsrEngine {
        override val info get() = requireNotNull(recognition)
        override suspend fun control(command: AsrControl): Boolean =
            (invoke("asr_control", PluginJson.encodeToJsonElement(command)) as? JsonPrimitive)?.booleanOrNull ?: false

        override suspend fun recognize(audio: PcmAudio, config: RecognitionConfig): RecognitionResult =
            withContext(Dispatchers.IO) {
                require(info.batch && audio.bytes.size <= MAX_PCM)
                val input = File.createTempFile("asr-", ".pcm", data.temporary)
                try {
                    input.writeBytes(audio.bytes)
                    val result = invoke("recognize", buildJsonObject {
                        put("input", input.relativeTo(data.root).invariantSeparatorsPath)
                        put("format", PluginJson.encodeToJsonElement(AudioFormat(audio.sampleRate, audio.channels)))
                        put("config", PluginJson.encodeToJsonElement(config))
                    })
                    PluginJson.decodeFromJsonElement(result)
                } finally { input.delete() }
            }

        override fun recognizeStream(audio: Flow<PcmAudio>, config: RecognitionConfig): Flow<RecognitionEvent> = flow {
            require(info.streaming != StreamingKind.NONE)
            var state: JsonElement = JsonNull
            suspend fun frame(chunk: ByteArray?, format: AudioFormat) {
                val result = invoke("recognize_stream", buildJsonObject {
                    put("format", PluginJson.encodeToJsonElement(format))
                    put("config", PluginJson.encodeToJsonElement(config))
                    put("state", state)
                    put("eof", chunk == null)
                    if (chunk != null) put("pcm", Base64.getEncoder().encodeToString(chunk))
                }) as? JsonObject ?: return
                state = result["state"] ?: JsonNull
                require(state.toString().toByteArray().size <= LuaJson.LIMIT / 2)
                (result["events"] as? JsonArray).orEmpty().forEach {
                    emit(PluginJson.decodeFromJsonElement<RecognitionEvent>(it))
                }
            }
            var format = info.inputFormat
            emit(RecognitionEvent.Started)
            audio.collect { pcm ->
                format = AudioFormat(pcm.sampleRate, pcm.channels)
                for (offset in pcm.bytes.indices step CHUNK) {
                    frame(pcm.bytes.copyOfRange(offset, minOf(pcm.bytes.size, offset + CHUNK)), format)
                }
            }
            frame(null, format)
        }.flowOn(Dispatchers.IO)
    }

    companion object {
        private const val CHUNK = 64 * 1024
        internal const val MAX_PCM = 96L * 1024 * 1024
    }
}
