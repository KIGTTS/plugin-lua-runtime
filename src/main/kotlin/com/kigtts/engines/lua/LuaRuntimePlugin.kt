package com.kigtts.engines.lua

import com.kigtts.plugin.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

class LuaRuntimePlugin : KigPlugin {
    override val descriptor = PluginDescriptor("runtime.lua", "插件编辑器", "编辑和调试插件项目", "0.9.10")
    override val localization = PluginLocalization("zh-CN", listOf("zh-CN", "en", "ko", "ja"))
    private var strings = PluginStrings(emptyMap())
    private fun copy(key: String) = strings.text(key, uiLocale)
    private fun copy(context: PluginContext, key: String, vararg args: Pair<String, String>) =
        strings.text(key, context.locale, args.toMap())
    private val pages get() = LuaRuntimePages { key, values -> strings.text(key, uiLocale, values) }
    private lateinit var environment: PluginEnvironment
    private lateinit var store: LuaSourceStore
    private lateinit var executor: LuaExecutor
    private lateinit var files: LuaDraftFiles
    private lateinit var packages: LuaPackageWriter
    private val mutex = Mutex()
    private val revisions = MutableStateFlow(0L)
    override val uiChanges get() = revisions
    private data class Ui(val contributions: PluginContributions, val pages: List<PluginPage>)
    @Volatile private var uiLocale = "zh-CN"
    private val ui = linkedMapOf<String, Ui>()
    private val transient = LinkedHashMap<String, PluginPage>(16, .75f, true)
    private val deletes = mutableMapOf<String, Pair<LuaDraft, List<String>>>()
    private val reviews = mutableMapOf<String, LuaPending>()
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment; store = LuaSourceStore(environment.storage); executor = LuaExecutor(environment, store); files = LuaDraftFiles(environment.storage, store)
        packages = LuaPackageWriter(environment, store)
        strings = PluginStrings.fromAssets(environment.assets)
        uiLocale = environment.locale
        for (project in store.projects().filter { it.enabled }) refresh(project, PluginContext("", "", locale = uiLocale))
    }
    override val contributions: PluginContributions get() = synchronized(ui) {
        PluginContributions(actions = ui.values.flatMap { it.contributions.actions },
            drawerEntries = listOf(DrawerEntry("manager", copy("title"), "manager", PluginIcon.EDIT)) + ui.values.flatMap { it.contributions.drawerEntries },
            automationActions = listOf("import", "agent-install", "agent"),
            managedPackages = store.projects().filter { it.enabled }.map { ManagedPackage(it.manifest.id, it.manifest.version, "lua", it.enabled, it.manifest.name, it.manifest.permissions, it.manifest.permissionPurposes, "project/${it.manifest.id}", "manage-enable/${it.manifest.id}", null, it.error) },
            settingsEntries = listOf(SettingsEntry("editor", copy("title"), "manager", PluginIcon.EDIT,
                cardId = "developer", cardTitle = copy("developer"), summary = copy("summary"))) + ui.values.flatMap { it.contributions.settingsEntries })
    }
    override fun contributions(context: PluginContext): PluginContributions {
        if (uiLocale != context.locale) runBlocking(Dispatchers.Default) {
            mutex.withLock {
                if (uiLocale != context.locale) {
                    for (project in store.projects().filter { it.enabled }) refresh(project, context)
                    uiLocale = context.locale
                }
            }
        }
        return contributions
    }
    override fun page(pageId: String, context: PluginContext): PluginPage? = when {
        pageId == "manager" -> pages.manager(store.projects())
        pageId.startsWith("module/") -> synchronized(ui) { ui.values.flatMap { it.pages }.firstOrNull { it.id == pageId } }
        else -> synchronized(transient) { transient[pageId] }
    }
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = mutex.withLock {
        try { perform(actionId, context).also { revisions.value++ } }
        catch (cancel: CancellationException) { throw cancel }
        catch (budget: LuaBudgetExceeded) { PluginOutcome.Message(copy(context, "timeout")) }
        catch (failure: Exception) { PluginOutcome.Message(when {
            failure is LuaEditorIssue -> strings.text(failure.key, context.locale, failure.arguments, failure.message.orEmpty())
            failure.message.orEmpty().contains("PERMISSION_REQUIRED") -> copy(context, "issue.permission")
            else -> copy(context, "failure")
        }) }
    }
    private suspend fun agent(context: PluginContext): PluginOutcome {
        val operation = context.inputs["operation"].orEmpty()
        if (operation == "list") return PluginOutcome.Dialog(copy(context, "dialog.projects"), buildJsonArray {
            store.projects().forEach { project -> add(buildJsonObject {
                put("id", project.manifest.id); put("name", project.manifest.name); put("enabled", project.enabled); put("error", project.error)
            }) }
        }.toString())
        val id = requireNotNull(context.inputs["project"])
        val project = store.project(id)
        val value: JsonElement = when (operation) {
            "read" -> buildJsonObject {
                val source = store.source(id)
                val offset = context.inputs["offset"]?.toIntOrNull()?.coerceIn(0, source.length) ?: 0
                val limit = context.inputs["limit"]?.toIntOrNull()?.coerceIn(1, 6000) ?: 6000
                val end = (offset + limit).coerceAtMost(source.length)
                put("source", source.substring(offset, end)); put("offset", offset); put("total", source.length)
                if (end < source.length) put("nextOffset", end)
                put("enabled", project.enabled); put("error", project.error)
                put("version", project.manifest.version); put("permissions", JsonArray(project.manifest.permissions.map(::JsonPrimitive)))
                put("permissionPurposes", JsonObject(project.manifest.permissionPurposes.mapValues { JsonPrimitive(it.value) }))
            }
            "test", "validate" -> try { inspectLuaProject(executor, project, store.code(id), context) }
                catch (failure: LuaInspectionFailure) { luaDiagnostic(failure, "test", id) }
            "enable" -> {
                require(context.inputs["_agentApproved"] == "true") { throw LuaEditorIssue("issue.approve_enable", "启用需要用户批准") }
                store.verify(project); store.enabled(id, true); refresh(store.project(id), context)
                buildJsonObject { put("enabled", store.project(id).enabled) }
            }
            "run" -> {
                require(context.inputs["_agentApproved"] == "true") { throw LuaEditorIssue("issue.approve_run", "运行需要用户批准") }
                require(project.enabled) { throw LuaEditorIssue("issue.enable", "请先启用项目") }
                try {
                    val result = executor.call(project, "execute", context.copy(inputs = mapOf("value" to context.inputs["input"].orEmpty())), requireNotNull(context.inputs["action"]))
                    LuaUiSchema.outcome(id, result, project.manifest.permissions)
                    refresh(project, context)
                    if (!store.project(id).enabled) luaDiagnostic(IllegalStateException(store.project(id).error), "refresh", id) else result
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { luaDiagnostic(failure, "execute", id) }
                catch (failure: LuaBudgetExceeded) { luaDiagnostic(failure, "execute", id) }
            }
            else -> throw LuaEditorIssue("issue.unknown_agent", "未知Lua调试操作")
        }
        return luaResultDialog(value, copy(context, "dialog.result"))
    }
    private suspend fun perform(actionId: String, context: PluginContext): PluginOutcome {
        if (actionId == "agent-install") {
            require(context.inputs["_agentApproved"] == "true") { throw LuaEditorIssue("issue.approve_install", "安装需要用户批准") }
            val pending = store.stage(environment.storage.file(requireNotNull(context.inputs["resourcePath"])))
            try { inspectLuaProject(executor, LuaProject(pending.manifest, false, pending.hash), pending.directory, context) }
            catch (failure: LuaInspectionFailure) {
                pending.directory.deleteRecursively()
                return PluginOutcome.Dialog(copy(context, "dialog.check_failed"), JsonObject(luaDiagnostic(failure, "validation", pending.manifest.id) +
                    ("installed" to JsonPrimitive(false))).toString())
            }
            val project = store.confirm(pending)
            refresh(project, context)
            val installed = store.project(project.manifest.id)
            return PluginOutcome.Dialog(copy(context, "dialog.install_result"), buildJsonObject {
                put("ok", installed.enabled); put("installed", true); put("enabled", installed.enabled)
                put("id", installed.manifest.id); put("error", installed.error)
            }.toString())
        }
        if (actionId == "agent") return agent(context)
        val (action, id) = actionId.substringBefore('/') to actionId.substringAfter('/', "")
        return when (action) {
            "manage-enable" -> {
                require(context.inputs["_callerPlugin"] == null)
                val project = store.project(id); val enabled = context.inputs["checked"]?.toBooleanStrictOrNull() ?: !project.enabled
                if (enabled) { store.verify(project); store.enabled(id, true); refresh(store.project(id), context) }
                else { store.enabled(id, false); synchronized(ui) { ui.remove(id) } }
                PluginOutcome.RefreshUi
            }
            "manage-remove" -> {
                require(context.inputs["_callerPlugin"] == null); store.remove(id); synchronized(ui) { ui.remove(id) }; PluginOutcome.RefreshUi
            }
            "permissions" -> {
                val project = store.project(id)
                for (permission in project.manifest.permissions) {
                    val reply = environment.appBroker?.request(AppCommand.Permission(permission, id))
                    if ((reply as? AppReply.Data)?.value?.get("allowed")?.jsonPrimitive?.booleanOrNull != true) return PluginOutcome.Message(copy(context, "issue.permission"))
                }
                refresh(project, context); PluginOutcome.RefreshUi
            }
            "choose" -> PluginOutcome.ImportResource(copy(context, "import"), "imports", "import", zip = true, mimeTypes = listOf("application/zip", "application/octet-stream"))
            "import" -> {
                val path = requireNotNull(context.inputs["resourcePath"])
                val source = environment.storage.file(path)
                if (source.resolve("plugin-spec.json").isFile) NativeBuilderLink.importProject(environment, path)
                else review(store.stage(source))
            }
            "resume-native-import" -> NativeBuilderLink.resumeImport(environment)
            "create" -> PluginOutcome.Menu(copy(context, "new"), listOf(
                PageAction("create-lua", "Lua", PluginIcon.TEXT),
                PageAction("create-native", copy(context, "native"), PluginIcon.TEXT)))
            "create-lua" -> PluginOutcome.TextInput(copy(context, "dialog.name"), "create-name")
            "native-builder", "create-native" -> NativeBuilderLink.open(environment, if (action == "create-native") "new" else "open")
            "create-name" -> create(context.inputs["value"].orEmpty())
            "confirm" -> {
                require(context.inputs["_callerPlugin"] == null) { throw LuaEditorIssue("issue.manual_confirm", "请在检查页面手动确认") }
                val pending = reviews[id] ?: throw LuaEditorIssue("issue.review_expired", "检查页面已更新，请重新打开")
                executor.validate(pending.directory.resolve("main.lua").readText(Charsets.UTF_8))
                val enabled = store.projects().firstOrNull { it.manifest.id == pending.manifest.id }?.enabled == true
                val project = store.confirm(pending, enabled); synchronized(transient) { transient.remove("review/$id") }
                reviews.remove(id)
                if (enabled) refresh(project, context)
                open(pages.project(store.project(project.manifest.id)))
            }
            "review-files" -> open(pages.files(files.inspect(reviews[id] ?: throw LuaEditorIssue("issue.review_expired", "检查页面已更新")), files))
            "review-export" -> (reviews[id] ?: throw LuaEditorIssue("issue.review_expired", "检查页面已更新")).let { pending ->
                PluginOutcome.ExportResource(copy(context, "exportSource"), PluginResource(pending.directory.resolve("main.lua").relativeTo(environment.storage.root).invariantSeparatorsPath), "main.lua", "text/plain")
            }
            "project" -> open(pages.project(store.project(id)))
            "rename" -> PluginOutcome.TextInput(copy(context, "dialog.name"), "save-name/$id",
                initialValue = store.project(id).manifest.name)
            "icon" -> PluginOutcome.TextInput(copy(context, "dialog.icon"), "save-icon/$id",
                initialValue = store.project(id).manifest.icon.orEmpty())
            "save-name", "save-icon" -> {
                require(context.inputs["_callerPlugin"] == null)
                val value = context.inputs["value"].orEmpty()
                val updated = if (action == "save-name") store.updatePresentation(id, name = value)
                    else store.updatePresentation(id, icon = value)
                if (updated.enabled) refresh(updated, context)
                open(pages.project(updated))
            }
            "enable" -> {
                val project = store.project(id)
                if (!project.enabled) {
                    require(context.inputs["_callerPlugin"] == null) { throw LuaEditorIssue("issue.manual_enable", "请在工具页面手动启用") }
                    store.verify(project); store.enabled(id, true); refresh(store.project(id), context)
                } else { store.enabled(id, false); synchronized(ui) { ui.remove(id) } }
                open(pages.project(store.project(id)))
            }
            "delete" -> open(pages.delete(store.project(id)))
            "delete-confirm" -> {
                require(context.inputs["_callerPlugin"] == null); store.remove(id); synchronized(ui) { ui.remove(id) }
                synchronized(transient) { transient.entries.removeAll { it.value.id == "project/$id" || it.value.id == "delete/$id" } }
                PluginOutcome.OpenPage("manager")
            }
            "debug" -> {
                require(context.inputs["_callerPlugin"] == null)
                val project = store.project(id)
                val file = packages.write(project, development = true)
                if (project.enabled) {
                    store.enabled(id, false)
                    synchronized(ui) { ui.remove(id) }
                }
                PluginOutcome.InstallPackage(PluginResource(file.relativeTo(environment.storage.root).invariantSeparatorsPath))
            }
            "export" -> packages.write(store.project(id)).let {
                PluginOutcome.ExportResource(copy(context, "export"), PluginResource(it.relativeTo(environment.storage.root).invariantSeparatorsPath),
                    it.name, "application/octet-stream")
            }
            "test" -> {
                val project = store.project(id); store.verify(project)
                val value = executor.call(project, "test", context)
                PluginOutcome.Dialog(copy(context, "dialog.test_result"), if (value == JsonNull) copy(context, "dialog.test_passed") else value.toString().take(16000))
            }
            "files" -> open(pages.files(files.create(id), files))
            "file-open" -> files.session(id).let { draft ->
                val path = requireNotNull(context.inputs["path"]); val file = if (path.isEmpty()) files.directory(draft, "") else files.file(draft, path)
                if (file.isDirectory) { draft.directory = path; open(pages.files(draft, files)) }
                else {
                    require(file.isFile && file.length() <= LuaSourceStore.SOURCE_LIMIT && file.readBytes().none { it == 0.toByte() }) { throw LuaEditorIssue("issue.small_text", "请选择较小的文本文件") }
                    val source = file.readText(Charsets.UTF_8); require(source.length <= 64000) { throw LuaEditorIssue("issue.large_text", "此文件较大，请导出后编辑") }
                    draft.editing = path; open(pages.editor(draft, files))
                }
            }
            "file-parent" -> files.session(id).let { draft ->
                val next = context.inputs["directory"] ?: draft.directory.substringBeforeLast('/', "")
                files.directory(draft, next); draft.directory = next; open(pages.files(draft, files))
            }
            "file-operation" -> operation(files.session(id), context)
            "file-delete" -> {
                val (draft, paths) = deletes.remove(id) ?: throw LuaEditorIssue("issue.confirm_expired", "确认已失效")
                require(context.inputs["_callerPlugin"] == null); files.delete(draft, paths); open(pages.files(draft, files))
            }
            "file-write" -> files.session(id).let { draft -> files.write(draft, context.inputs["value"].orEmpty()); cache(pages.editor(draft, files)); PluginOutcome.RefreshUi }
            "file-import" -> files.session(id).let { draft ->
                context.inputs["directory"]?.let { draft.directory = it }
                PluginOutcome.ImportResource(copy(context, "dialog.import_files"), "imports/files", "file-imported/$id", multiple = true)
            }
            "file-imported" -> files.session(id).let { draft ->
                val resources = context.inputs["resourcePaths"]?.let { PluginJson.decodeFromString<List<String>>(it) }
                    ?: listOf(requireNotNull(context.inputs["resourcePath"]))
                files.import(draft, resources.map(environment.storage::file)); open(pages.files(draft, files))
            }
            "file-new" -> files.session(id).let { draft ->
                context.inputs["directory"]?.let { draft.directory = it }
                PluginOutcome.Menu(copy(context, "dialog.new_file"), listOf(PageAction("file-folder/$id", copy(context, "dialog.folder"), PluginIcon.ADD), PageAction("file-text/$id", copy(context, "dialog.text_file"), PluginIcon.TEXT)))
            }
            "file-folder" -> PluginOutcome.TextInput(copy(context, "dialog.folder_name"), "file-folder-name/$id")
            "file-text" -> PluginOutcome.TextInput(copy(context, "dialog.file_name"), "file-text-name/$id")
            "file-folder-name", "file-text-name" -> files.session(id).let { draft -> files.create(draft, context.inputs["value"].orEmpty(), action == "file-folder-name"); open(pages.files(draft, files)) }
            "validate" -> files.session(id).let { draft ->
                require(draft.editing.endsWith(".lua")); executor.validate(files.file(draft, draft.editing).readText(Charsets.UTF_8)); PluginOutcome.Message(copy(context, "dialog.syntax_passed"))
            }
            "draft-review" -> files.session(id).let { draft -> review(files.review(draft)).also { files.close(draft); synchronized(transient) { transient.remove("draft/$id"); transient.remove("edit/$id") } } }
            "draft-close" -> files.session(id).let { draft -> files.close(draft); synchronized(transient) { transient.remove("draft/$id"); transient.remove("edit/$id") }
                draft.reviewOf?.let { token -> review(reviews[token] ?: throw LuaEditorIssue("issue.review_expired", "检查页面已更新")) } ?: open(pages.project(store.project(draft.projectId))) }
            "draft-back" -> open(pages.files(files.session(id), files))
            "module" -> {
                val module = id.substringBefore('/'); val command = id.substringAfter('/')
                val project = store.project(module); require(project.enabled) { throw LuaEditorIssue("issue.disabled", "工具已停用") }
                try {
                    store.verify(project)
                    val result = LuaUiSchema.outcome(module, executor.call(project, "execute", context, command), project.manifest.permissions)
                    if (result is PluginOutcome.ReplaceEditorText) {
                        val permission = environment.appBroker?.request(AppCommand.Permission("app-control", module))
                        require((permission as? AppReply.Data)?.value?.get("allowed")?.jsonPrimitive?.booleanOrNull == true) { "PERMISSION_REQUIRED: app-control" }
                    }
                    refresh(project, context); result
                } catch (failure: LuaBudgetExceeded) { fail(module, failure.message.orEmpty()); throw failure }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; if (!failure.message.orEmpty().contains("PERMISSION_REQUIRED")) fail(module, failure.message.orEmpty()); throw failure }
            }
            else -> PluginOutcome.Message(copy(context, "issue.action_missing"))
        }
    }
    private suspend fun refresh(project: LuaProject, context: PluginContext) {
        try {
            store.verify(project)
            val contributions = LuaUiSchema.contributions(project.manifest.id, executor.call(project, "contributions", context))
            val pages = LuaUiSchema.pages(project.manifest.id, executor.call(project, "pages", context))
            val existing = synchronized(ui) { ui.values.filter { it !== ui[project.manifest.id] }.sumOf { it.pages.size } }
            require(existing + pages.size <= 32) { throw LuaEditorIssue("issue.page_limit", "已到达工具页面数量上限") }
            val existingSettings = synchronized(ui) { ui.filterKeys { it != project.manifest.id }.values.sumOf { it.contributions.settingsEntries.size } }
            require(existingSettings + contributions.settingsEntries.size <= 60) { throw LuaEditorIssue("issue.settings_limit", "已到达设置入口数量上限") }
            val pageBytes = PluginJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(PluginPage.serializer()), pages).toByteArray().size
            val otherBytes = synchronized(ui) { ui.filterKeys { it != project.manifest.id }.values.sumOf {
                PluginJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(PluginPage.serializer()), it.pages).toByteArray().size
            } }
            require(pageBytes + otherBytes <= 120 * 1024) { throw LuaEditorIssue("issue.page_length", "工具页面内容过多，请精简文本") }
            val contributionBytes = PluginJson.encodeToString(PluginContributions.serializer(), contributions).toByteArray().size
            val otherContributions = synchronized(ui) { ui.filterKeys { it != project.manifest.id }.values.sumOf {
                PluginJson.encodeToString(PluginContributions.serializer(), it.contributions).toByteArray().size
            } }
            require(pageBytes + otherBytes + contributionBytes + otherContributions <= 180 * 1024) { throw LuaEditorIssue("issue.ui_length", "工具界面内容过多，请精简设置或按钮说明") }
            require(contributions.drawerEntries.all { entry -> pages.any { it.id == entry.pageId } }) { throw LuaEditorIssue("issue.missing_page", "页面入口没有对应页面") }
            require(contributions.settingsEntries.all { entry -> pages.any { it.id == entry.pageId } }) { throw LuaEditorIssue("issue.missing_settings", "设置入口没有对应页面") }
            PluginSnapshot(descriptor, contributions, pages)
            synchronized(ui) { ui[project.manifest.id] = Ui(contributions, pages) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: LuaBudgetExceeded) { fail(project.manifest.id, failure.message.orEmpty()) }
        catch (failure: Exception) {
            if (failure.message.orEmpty().contains("PERMISSION_REQUIRED")) {
                val module = project.manifest.id
                val page = PluginPage("module/$module/permissions", project.manifest.name, listOf(PageBlock.Notice(copy(context, "dialog.permissions")), PageBlock.Actions(listOf(PageAction("permissions/$module", copy(context, "dialog.continue"))))))
                val entry = SettingsEntry("module/$module/entry", project.manifest.name, page.id)
                synchronized(ui) { ui[module] = Ui(PluginContributions(settingsEntries = listOf(entry)), listOf(page)) }
            } else fail(project.manifest.id, failure.message.orEmpty())
        }
    }
    private fun fail(id: String, message: String) { store.failed(id, message); synchronized(ui) { ui.remove(id) } }
    private fun review(pending: LuaPending): PluginOutcome {
        executor.validate(pending.directory.resolve("main.lua").readText(Charsets.UTF_8))
        val previous = reviews.values.filter { it.manifest.id == pending.manifest.id && it.token != pending.token }
        previous.forEach { old -> reviews.remove(old.token); synchronized(transient) { transient.remove("review/${old.token}") }; old.directory.deleteRecursively() }
        require(reviews.size < 8 || pending.token in reviews) { throw LuaEditorIssue("issue.review_limit", "请先完成其它工具的检查") }
        reviews[pending.token] = pending
        return open(pages.review(pending, store.projects().firstOrNull { it.manifest.id == pending.manifest.id }))
    }
    private fun create(name: String): PluginOutcome {
        require(name.length in 1..64)
        val draft = environment.storage.directory("new/${UUID.randomUUID()}")
        val id = "tool-${UUID.randomUUID().toString().take(8)}"
        draft.resolve("manifest.json").writeText(buildJsonObject { put("id", id); put("name", name); put("version", "0.1.0"); put("permissions", JsonArray(emptyList())) }.toString(), Charsets.UTF_8)
        draft.resolve("main.lua").writeText("return {\n  contributions = function(ctx) return {} end,\n  pages = function(ctx) return {} end,\n  execute = function(action, ctx) return {type='message', text='完成'} end\n}\n", Charsets.UTF_8)
        return try { open(pages.project(store.confirm(store.stage(draft), enabled = false))) }
        finally { draft.deleteRecursively() }
    }
    private fun operation(draft: LuaDraft, context: PluginContext): PluginOutcome {
        val paths = PluginJson.decodeFromString<List<String>>(requireNotNull(context.inputs["paths"]))
        return when (context.inputs["op"]) {
            "move" -> { files.move(draft, paths, context.inputs["destination"].orEmpty()); open(pages.files(draft, files)) }
            "copy" -> { files.copy(draft, paths, context.inputs["destination"].orEmpty()); open(pages.files(draft, files)) }
            "rename" -> { require(paths.size == 1); files.rename(draft, paths.single(), context.inputs["destination"].orEmpty()); open(pages.files(draft, files)) }
            "delete" -> {
                require(paths.isNotEmpty()); val token = UUID.randomUUID().toString(); deletes[token] = draft to paths
                open(PluginPage("file-delete/$token", copy(context, "dialog.delete_files"), listOf(PageBlock.Notice(copy(context, "dialog.delete_count", "count" to paths.size.toString())),
                    PageBlock.Actions(listOf(PageAction("file-delete/$token", "确认删除", PluginIcon.CLOSE))))))
            }
            else -> throw LuaEditorIssue("issue.unknown_file_action", "未知文件操作")
        }
    }
    private fun open(page: PluginPage): PluginOutcome { cache(page); return PluginOutcome.OpenPage(page.id) }
    private fun cache(page: PluginPage) = synchronized(transient) {
        fun bytes(value: PluginPage) = PluginJson.encodeToString(PluginPage.serializer(), value).toByteArray().size
        require(bytes(page) <= 250 * 1024) { throw LuaEditorIssue("issue.preview_large", "页面内容过大，请导出后编辑") }
        if (page.blocks.any { it is PageBlock.SourceEditor }) transient.entries.removeAll { (id, value) -> id != page.id && value.blocks.any { it is PageBlock.SourceEditor } }
        transient[page.id] = page
        while (transient.size > 16 || transient.values.sumOf(::bytes) > 250 * 1024) transient.remove(transient.keys.first())
    }
}
