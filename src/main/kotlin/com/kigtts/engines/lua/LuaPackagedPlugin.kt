package com.kigtts.engines.lua

import com.kigtts.plugin.api.KigPlugin
import com.kigtts.plugin.api.EngineError
import com.kigtts.plugin.api.EngineException
import com.kigtts.plugin.api.PluginContext
import com.kigtts.plugin.api.PluginContributions
import com.kigtts.plugin.api.PluginDescriptor
import com.kigtts.plugin.api.PluginEnvironment
import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.PluginLocalization
import com.kigtts.plugin.api.PluginOutcome
import com.kigtts.plugin.api.PluginPage
import com.kigtts.plugin.api.PluginSnapshot
import com.kigtts.plugin.api.requirePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Entry point of a signed standalone Lua package. It never depends on the editor process. */
class LuaPackagedPlugin(descriptorJson: String) : KigPlugin {
    override val descriptor = PluginJson.decodeFromString(PluginDescriptor.serializer(), descriptorJson)
    private lateinit var project: LuaProject
    private lateinit var source: File
    private lateinit var executor: LuaExecutor
    private lateinit var environment: PluginEnvironment
    private var speech: LuaSpeech? = null
    override val tts get() = speech?.tts
    override val asr get() = speech?.asr
    override val voiceManager get() = speech?.voices
    override var localization = PluginLocalization()
        private set
    private val gate = Mutex()
    private val revisions = MutableStateFlow(0L)
    override val uiChanges get() = revisions
    @Volatile private var snapshot: PluginSnapshot? = null
    @Volatile private var locale: String? = null

    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        val store = LuaSourceStore(environment.storage)
        source = environment.assets.resolve("lua-project")
        val metadata = store.readManifest(source.resolve("manifest.json"))
        require(metadata.id == descriptor.id && metadata.version == descriptor.version)
        project = LuaProject(metadata, enabled = true, hash = "")
        executor = LuaExecutor(environment, store, standalone = true)
        val locales = source.resolve("assets/i18n").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .map { it.nameWithoutExtension }.filter { it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,5}")) }
            .sorted()
        localization = PluginLocalization(if ("zh-CN" in locales) "zh-CN" else locales.firstOrNull() ?: "zh-CN",
            locales.ifEmpty { listOf("zh-CN") })
        speech = LuaSpeech(store.data(metadata.id), metadata.permissions) { method, input ->
            try {
                executor.call(project, method, PluginContext("", "", locale = locale ?: environment.locale),
                    sourceDirectory = source, input = input)
            } catch (limit: LuaBudgetExceeded) {
                throw EngineException(EngineError.TIMEOUT, limit.message.orEmpty(), limit)
            }
        }
        synchronize(PluginContext("", "", locale = environment.locale))
    }

    override suspend fun close() {
        if (::executor.isInitialized) executor.call(project, "close", PluginContext("", "", locale = locale ?: "zh-CN"), sourceDirectory = source)
    }

    override val contributions: PluginContributions
        get() = snapshot?.contributions ?: PluginContributions()

    override fun contributions(context: PluginContext): PluginContributions {
        if (context.locale != locale) runBlocking(Dispatchers.Default) { gate.withLock { synchronize(context) } }
        return contributions
    }

    override fun page(pageId: String, context: PluginContext): PluginPage? =
        snapshot?.pages?.firstOrNull { it.id == pageId }

    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = gate.withLock {
        val action = actionId.removePrefix("module/${descriptor.id}/")
        try {
            val outcome = executor.call(project, "execute", context, action, source)
            val native = wrappedSdkValue(outcome)
            val result = if (native == null) LuaUiSchema.outcome(descriptor.id, outcome, project.manifest.permissions)
                else PluginJson.decodeFromJsonElement(PluginOutcome.serializer(), native)
            when (result) {
                is PluginOutcome.ReplaceEditorText, is PluginOutcome.HostAction, is PluginOutcome.PresentContent,
                is PluginOutcome.SetSubtitleRenderer, is PluginOutcome.SetListeningPanel,
                is PluginOutcome.SelectTtsService, is PluginOutcome.SelectRecognitionService -> {
                    require("app-control" in project.manifest.permissions)
                    environment.requirePermission("app-control")
                }
                else -> Unit
            }
            synchronize(context)
            result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (limit: LuaBudgetExceeded) {
            PluginOutcome.Message(limit.message.orEmpty())
        }
    }

    private suspend fun synchronize(context: PluginContext) {
        speech?.refresh()
        val contributionData = executor.call(project, "contributions", context, sourceDirectory = source)
        val pageData = executor.call(project, "pages", context, sourceDirectory = source)
        val contributions = wrappedSdkValue(contributionData)?.let {
            PluginJson.decodeFromJsonElement(PluginContributions.serializer(), it)
        } ?: LuaUiSchema.contributions(descriptor.id, contributionData)
        val pages = wrappedSdkValue(pageData)?.let {
            val empty = it == JsonNull || it is JsonObject && it.isEmpty()
            PluginJson.decodeFromJsonElement(ListSerializer(PluginPage.serializer()), if (empty) JsonArray(emptyList()) else it)
        } ?: LuaUiSchema.pages(descriptor.id, pageData)
        val next = PluginSnapshot(descriptor, contributions, pages)
        if (snapshot != next) {
            snapshot = next
            revisions.value++
        }
        locale = context.locale
    }

    private fun wrappedSdkValue(value: JsonElement): JsonElement? =
        (value as? JsonObject)?.get("sdk")
}
