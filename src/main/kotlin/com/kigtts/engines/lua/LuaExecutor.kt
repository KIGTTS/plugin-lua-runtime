package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext

class LuaBudgetExceeded(message: String) : Error(message)

/** Fresh globals per operation. The only object exposed to Lua is a data/function table. */
class LuaExecutor(private val environment: PluginEnvironment, private val store: LuaSourceStore,
    private val instructionLimit: Int = 250_000, private val durationMillis: Long = 2_000,
    private val standalone: Boolean = false) {
    fun validate(source: String) { compile(source) }
    suspend fun call(project: LuaProject, method: String, context: PluginContext, action: String? = null,
        sourceDirectory: File = store.code(project.manifest.id), input: JsonElement? = null): JsonElement = withContext(Dispatchers.Default) {
        val coroutine = currentCoroutineContext()
        val deadline = System.nanoTime() + durationMillis * 1_000_000
        val globals = Globals()
        globals.load(BaseLib()); globals.load(PackageLib()); globals.load(TableLib())
        globals.load(StringLib()); globals.load(JseMathLib()); globals.load(Bit32Lib())
        val budget = BudgetDebug(instructionLimit, deadline, coroutine)
        globals.load(budget)
        val removed = listOf("io", "os", "debug", "package", "require", "luajava", "coroutine", "load", "loadfile", "dofile",
            "collectgarbage", "pcall", "xpcall", "getmetatable", "setmetatable", "rawset", "print")
        removed.forEach { globals.set(it, LuaValue.NIL) }
        globals.set("unpack", LuaValue.NIL)
        globals.get("table").set("unpack", LuaValue.NIL)
        // These native functions run outside the bytecode loop; preflight their bounded allocations.
        val concat = globals.get("table").get("concat")
        globals.get("table").set("concat", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val table = args.checktable(1); val first = args.optint(3, 1); val last = args.optint(4, table.length())
                require(last - first in -1..4096)
                val separator = args.optjstring(2, "")
                var bytes = separator.toByteArray().size.toLong() * (last - first).coerceAtLeast(0)
                for (index in first..last) { bytes += table.get(index).checkstring().length(); require(bytes <= LuaJson.LIMIT) }
                return concat.invoke(args)
            }
        })
        val sort = globals.get("table").get("sort")
        globals.get("table").set("sort", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs { require(args.checktable(1).length() <= 4096); return sort.invoke(args) }
        })
        // The shared string metatable points at the first StringLib. Keep all instances identically restricted.
        listOf("dump", "rep", "format", "gsub", "gmatch", "match").forEach { globals.get("string").set(it, LuaValue.NIL) }
        val find = globals.get("string").get("find")
        globals.get("string").set("find", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = find.invoke(LuaValue.varargsOf(arrayOf(args.arg1(), args.arg(2), args.arg(3), LuaValue.TRUE)))
        })
        val writable = method == "execute" || standalone && method in setOf("synthesize", "tts_control", "voice_command",
            "recognize", "recognize_stream", "asr_control", "close")
        globals.set("host", host(project, writable, coroutine, budget, globals, sourceDirectory, context.locale))
        val entry = sourceDirectory.resolve("main.lua")
        val prototype = if (standalone) synchronized(this@LuaExecutor) {
            standalonePrototype ?: compile(entry.readText(Charsets.UTF_8)).also { standalonePrototype = it }
        } else compile(entry.readText(Charsets.UTF_8))
        try {
            val exports = LuaClosure(prototype, globals).call().checktable()
            val function = exports.get(method)
            if (function.isnil()) return@withContext when (method) { "pages" -> JsonArray(emptyList()); "contributions" -> JsonObject(emptyMap()); else -> JsonNull }
            require(function.isfunction()) { "$method 必须是函数" }
            val ctx = LuaJson.toLua(PluginJson.encodeToJsonElement(PluginContext.serializer(), context))
            val value = when {
                action != null -> function.call(LuaValue.valueOf(action), ctx)
                input != null -> function.call(LuaJson.toLua(input), ctx)
                else -> function.call(ctx)
            }
            coroutine.ensureActive(); LuaJson.result(value)
        } catch (failure: Exception) { coroutine.ensureActive(); throw failure }
    }
    private var standalonePrototype: Prototype? = null
    private val assetPaths by lazy { LuaAssetPaths(environment.storage) }
    private fun compile(source: String, name: String = "main.lua"): Prototype {
        val bytes = source.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..LuaSourceStore.SOURCE_LIMIT && bytes.first() != 27.toByte() && bytes.none { it == 0.toByte() }) { "只允许 UTF-8 源代码" }
        return LuaC.instance.compile(ByteArrayInputStream(bytes), name)
    }
    private fun host(project: LuaProject, writable: Boolean, coroutine: CoroutineContext, budget: BudgetDebug, globals: Globals, sourceDirectory: File, locale: String): LuaTable {
        val permissions = project.manifest.permissions
        val data = store.data(project.manifest.id)
        val assets = sourceDirectory.resolve("assets")
        val modules = mutableMapOf<String, LuaValue>()
        val loading = mutableSetOf<String>()
        val mountedAssets by lazy { assetPaths.mount(sourceDirectory) }
        fun availableAsset(path: String): File {
            require(standalone && scoped(assets, path).exists())
            return mountedAssets.file(path)
        }
        fun gate(permission: String) { require(permission in permissions) { "未授予 $permission" }; budget.check() }
        fun function(body: (Varargs) -> LuaValue) = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs { coroutine.ensureActive(); budget.check(); return body(args) }
        }
        fun rewriteResources(json: JsonElement): JsonElement = when (json) {
            is JsonObject -> JsonObject(json.mapValues { (key, value) ->
                if ((key == "source" || key == "root") && value is JsonObject && value["path"] != null) {
                    require(value["pluginId"] == null || value["pluginId"] == JsonNull) { "不允许访问其它插件资源" }
                    val path = value.getValue("path").jsonPrimitive.content
                    require(path.startsWith("data/") || path.startsWith("assets/")) { "资源须位于本工具的 data/ 或 assets/" }
                    val local = path.substringAfter('/')
                    val file = if (path.startsWith("data/")) data.file(local)
                        else if (standalone) availableAsset(local) else scoped(assets, local)
                    buildJsonObject { put("path", file.relativeTo(environment.storage.root).invariantSeparatorsPath) }
                } else rewriteResources(value)
            })
            is JsonArray -> JsonArray(json.map(::rewriteResources))
            else -> json
        }
        val strings by lazy { PluginStrings.fromAssets(assets) }
        return LuaTable().also { host ->
            host.set("t", function { args ->
                val key = args.checkjstring(1)
                require(key.length in 1..160)
                val values = linkedMapOf<String, String>()
                if (!args.isnoneornil(2)) {
                    val table = args.checktable(2)
                    var index = LuaValue.NIL
                    while (true) {
                        val item = table.next(index)
                        index = item.arg1()
                        if (index.isnil()) break
                        require(values.size < 32)
                        val name = index.checkjstring()
                        val value = item.arg(2).checkjstring()
                        require(name.length in 1..80 && value.length <= 1000)
                        values[name] = value
                    }
                }
                LuaValue.valueOf(strings.text(key, locale, values, args.optjstring(3, key)).take(16000))
            })
            host.set("module", function { args ->
                val name = args.checkjstring(1)
                require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){0,7}")) && name.length <= 80) { "无效模块名" }
                modules[name] ?: run {
                    require(loading.add(name)) { "Lua 模块循环引用" }
                    try {
                        val file = scoped(assets, "lua/${name.replace('.', '/')}.lua")
                        require(file.isFile && file.length() <= LuaSourceStore.SOURCE_LIMIT)
                        LuaClosure(compile(file.readText(Charsets.UTF_8), "assets/lua/$name.lua"), globals).call().also { modules[name] = it }
                    } finally { loading.remove(name) }
                }
            })
            host.set("app", function { args ->
                val command = PluginJson.decodeFromJsonElement(AppCommand.serializer(), LuaJson.result(args.arg1()))
                gate(when {
                    standalone && command is AppCommand.Overlay -> "overlay"
                    standalone && command is AppCommand.Notifications -> "notifications"
                    else -> "app-control"
                })
                require(standalone || command is AppCommand.Describe || command is AppCommand.State || command is AppCommand.Navigate ||
                    command is AppCommand.SetText || command is AppCommand.SetAutomaticRead || command is AppCommand.Speak ||
                    command is AppCommand.StopSpeech || command is AppCommand.Setting) { "Lua 工具不能调用插件管理操作" }
                require(writable || command is AppCommand.Describe || command is AppCommand.State) { "页面刷新只能读取数据" }
                val reply = budget.io { runBlocking(coroutine + Dispatchers.IO) { withTimeout(125_000) { managed(project, "app", PluginJson.encodeToJsonElement(AppCommand.serializer(), command).jsonObject) } } }
                LuaJson.toLua(reply)
            })
            host.set("quick", function { args ->
                val request = rewriteResources(LuaJson.result(args.arg1())).jsonObject.toMutableMap()
                val type = request["type"]?.jsonPrimitive?.content.orEmpty()
                val reading = type in setOf("describe", "query", "files", "transaction")
                gate(if (reading) "quick-text-read" else "quick-text-write")
                require(writable || reading) { "页面刷新只能读取数据" }
                if (type in setOf("export", "get-file")) {
                    val destination = request["destination"]?.jsonPrimitive?.content ?: "exports/quick-text.kigcards"
                    request["destination"] = JsonPrimitive(data.file(destination).relativeTo(environment.storage.root).invariantSeparatorsPath)
                }
                val command = PluginJson.decodeFromJsonElement(QuickTextCommand.serializer(), JsonObject(request))
                val reply = budget.io { runBlocking(coroutine + Dispatchers.IO) { withTimeout(125_000) { managed(project, "quick", PluginJson.encodeToJsonElement(QuickTextCommand.serializer(), command).jsonObject) } } }
                LuaJson.toLua(reply)
            })
            host.set("stickers", function { args ->
                val request=rewriteResources(LuaJson.result(args.arg1())).jsonObject.toMutableMap()
                val type=request["type"]?.jsonPrimitive?.content.orEmpty()
                val pureRead=type in setOf("describe","query","query-frames","query-albums","query-album-cards","transaction")
                val reading=pureRead||type in setOf("read-image","export-card","publish-quick-media")
                gate(if(reading&&"stickers-read" in permissions) "stickers-read" else "stickers-write")
                require(writable||pureRead) { "页面刷新只能读取数据" }
                if(type=="import-media")gate(if("quick-text-read" in permissions) "quick-text-read" else "quick-text-write")
                if(type=="publish-quick-media")gate("quick-text-write")
                if(type in setOf("read-image","export-card")) {
                    val destination=request["destination"]?.jsonPrimitive?.content ?: "exports/image.png"
                    require(destination.startsWith("exports/")||destination.startsWith("tmp/")) { "图片副本须保存到本工具 exports/ 或 tmp/" }
                    request["destination"]=JsonPrimitive(data.file(destination).relativeTo(environment.storage.root).invariantSeparatorsPath)
                }
                val command=PluginJson.decodeFromJsonElement(StickerCommand.serializer(),JsonObject(request))
                val reply=budget.io {runBlocking(coroutine+Dispatchers.IO) {withTimeout(125_000) {managed(project, "stickers", PluginJson.encodeToJsonElement(StickerCommand.serializer(), command).jsonObject)}}}
                LuaJson.toLua(reply)
            })
            host.set("data", LuaTable().also { functions ->
                functions.set("read", function { args -> val file = data.file(args.checkjstring(1)); require(file.length() <= 64 * 1024); LuaValue.valueOf(if (file.isFile) file.readText(Charsets.UTF_8) else "") })
                functions.set("write", function { args ->
                    require(writable) { "页面刷新不能写文件" }
                    val value = args.checkjstring(2); require(value.toByteArray().size <= 64 * 1024)
                    val file = data.file(args.checkjstring(1))
                    val files = data.root.walkTopDown().filter { it.isFile }.toList()
                    require((file.isFile || files.size < 128) && files.sumOf { it.length() } - file.length() + value.toByteArray().size <= 4 * 1024 * 1024)
                    requireNotNull(file.parentFile).mkdirs(); file.writeText(value, Charsets.UTF_8); LuaValue.TRUE
                })
            })
            host.set("asset", LuaTable().also { functions ->
                functions.set("read", function { args -> val file = scoped(assets, args.checkjstring(1)); require(file.isFile && file.length() <= 256 * 1024); LuaValue.valueOf(file.readText(Charsets.UTF_8)) })
                if (standalone) functions.set("path", function { args ->
                    LuaValue.valueOf(availableAsset(args.checkjstring(1)).relativeTo(environment.storage.root).invariantSeparatorsPath)
                })
            })
            if (standalone) host.set("path", function { args ->
                LuaValue.valueOf(data.file(args.checkjstring(1)).relativeTo(environment.storage.root).invariantSeparatorsPath)
            })
            if (standalone) host.set("download", function { args ->
                gate("network")
                require(writable) { "页面刷新不能下载文件" }
                val target = data.file(args.checkjstring(2)).relativeTo(environment.storage.root).invariantSeparatorsPath
                budget.io {
                    runBlocking(coroutine + Dispatchers.IO) {
                        withTimeout(125_000) {
                            environment.download(args.checkjstring(1), target, args.optjstring(3, "").ifBlank { null })
                        }
                    }
                }
                LuaValue.valueOf(target)
            })
            if (standalone) host.set("http", function { args ->
                gate("network")
                require(writable)
                val input = LuaJson.result(args.arg1()).jsonObject
                val result = budget.io {
                    runBlocking(coroutine + Dispatchers.IO) {
                        withTimeout(125_000) {
                            environment.requirePermission("network")
                            luaHttp(input, data)
                        }
                    }
                }
                LuaJson.toLua(result)
            })
            if (standalone) {
                fun voiceBroker(permission: String, run: suspend (JsonObject) -> JsonElement) = function { args ->
                    gate(permission)
                    val command = LuaJson.result(args.arg1()).jsonObject
                    require(writable || command["operation"]?.jsonPrimitive?.content == "providers")
                    val result = budget.io {
                        runBlocking(coroutine + Dispatchers.IO) {
                            withTimeout(125_000) {
                                environment.requirePermission(permission)
                                run(command)
                            }
                        }
                    }
                    require(result.toString().toByteArray().size <= LuaJson.LIMIT)
                    LuaJson.toLua(result)
                }
                host.set("speech", voiceBroker("speech-service") { command ->
                    luaSpeechBroker(requireNotNull(environment.speechBroker), data, command)
                })
                host.set("recognition", voiceBroker("asr-service") { command ->
                    luaRecognitionBroker(requireNotNull(environment.recognitionBroker), data, command)
                })
            }
            if (standalone) host.set("binary", LuaTable().also { binary ->
                binary.set("read", function { args ->
                    val file = data.file(args.checkjstring(1))
                    val offset = args.optlong(2, 0)
                    val count = args.optint(3, 65536)
                    require(offset in 0L..file.length() && count in 1..65536)
                    val bytes = ByteArray(minOf(count.toLong(), file.length() - offset).toInt())
                    RandomAccessFile(file, "r").use { stream -> stream.seek(offset); stream.readFully(bytes) }
                    LuaValue.valueOf(java.util.Base64.getEncoder().encodeToString(bytes))
                })
                binary.set("write", function { args ->
                    require(writable)
                    val file = data.file(args.checkjstring(1))
                    val bytes = java.util.Base64.getDecoder().decode(args.checkjstring(2).also { require(it.length <= 90000) })
                    val append = args.optboolean(3, false)
                    require(bytes.size <= 65536 && (if (append) file.length() else 0L) + bytes.size <= LuaSpeech.MAX_PCM)
                    requireNotNull(file.parentFile).mkdirs()
                    java.io.FileOutputStream(file, append).use { it.write(bytes) }
                    LuaValue.TRUE
                })
            })
        }
    }
    private suspend fun managed(project: LuaProject, channel: String, command: JsonObject): JsonElement {
        if (standalone) return when (channel) {
            "app" -> PluginJson.encodeToJsonElement(AppReply.serializer(),
                requireNotNull(environment.appBroker).request(PluginJson.decodeFromJsonElement(AppCommand.serializer(), command)))
            "quick" -> PluginJson.encodeToJsonElement(QuickTextReply.serializer(),
                requireNotNull(environment.quickTextBroker).request(PluginJson.decodeFromJsonElement(QuickTextCommand.serializer(), command)))
            "stickers" -> PluginJson.encodeToJsonElement(StickerReply.serializer(),
                requireNotNull(environment.stickerBroker).request(PluginJson.decodeFromJsonElement(StickerCommand.serializer(), command)))
            else -> error("Unsupported broker")
        }
        val reply = environment.appBroker?.request(AppCommand.ManagedCall(project.manifest.id, channel, command))
            ?: error("PERMISSION_REQUIRED: 宿主未提供项目权限代理")
        if (reply is AppReply.Failure) error((if (reply.code == "PERMISSION_DENIED") "PERMISSION_REQUIRED: " else "") + reply.message)
        return (reply as AppReply.Data).value.getValue("reply")
    }
    private fun scoped(root: File, path: String): File {
        require(root.isDirectory && path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000'))
        require(path.split('/').none { it in setOf("", ".", "..") })
        return root.resolve(path).canonicalFile.also { require(it.toPath().startsWith(root.canonicalFile.toPath())) }
    }
    private class BudgetDebug(private val limit: Int, private val deadline: Long, private val coroutine: CoroutineContext) : DebugLib() {
        private var instructions = 0
        private var blockedNanos = 0L
        private var calls = 0
        private val frames = ArrayDeque<Array<LuaValue>>()
        fun <T> io(block: () -> T): T {
            require(++calls <= 16) { "一次操作的宿主调用过多" }
            val start = System.nanoTime()
            return try { block() } finally { blockedNanos += System.nanoTime() - start }
        }
        fun check() {
            coroutine.ensureActive()
            if (instructions >= limit || System.nanoTime() - blockedNanos >= deadline) throw LuaBudgetExceeded("工具运行超出预算")
        }
        override fun onCall(c: LuaClosure, varargs: Varargs, stack: Array<LuaValue>) {
            check(); if (frames.size >= 64) throw LuaBudgetExceeded("工具调用层级过深")
            frames.addLast(stack); super.onCall(c, varargs, stack)
        }
        override fun onInstruction(pc: Int, v: Varargs, top: Int) {
            instructions++; check()
            // Catch exponential concatenation before the next allocation; per instruction, not only per hook interval.
            frames.lastOrNull()?.forEach { raw ->
                val value: LuaValue? = raw // LuaJ stack registers are null until assigned.
                if (value?.type() == LuaValue.TSTRING && value.checkstring().length() > LuaJson.LIMIT) throw LuaBudgetExceeded("工具字符串过大")
            }
            super.onInstruction(pc, v, top)
        }
        override fun onReturn() { if (frames.isNotEmpty()) frames.removeLast(); super.onReturn() }
    }
}
