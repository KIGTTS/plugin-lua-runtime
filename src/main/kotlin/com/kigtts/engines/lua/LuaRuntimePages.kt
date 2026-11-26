package com.kigtts.engines.lua

import com.kigtts.plugin.api.*

internal class LuaRuntimePages(private val text: (String, Map<String, String>) -> String) {
    private fun copy(key: String, vararg values: Pair<String, String>) = text(key, values.toMap())
    private fun projectHeader(project: LuaProject, opens: Boolean = false) = ManagementItem(
        project.manifest.id, project.manifest.name, if (project.error.isEmpty()) "Lua · v${project.manifest.version}" else copy("dialog.project_failed"),
        PluginIcon.TEXT, symbol = project.manifest.icon ?: "code",
        primaryAction = if (opens) "project/${project.manifest.id}" else null)

    fun manager(projects: List<LuaProject>) = PluginPage("manager", copy("title"), listOf(
        PageBlock.Group(copy("tools"), listOf(PageBlock.Actions(listOf(
            PageAction("choose", copy("import"), PluginIcon.ADD), PageAction("create", copy("new"), PluginIcon.EDIT))))),
    ), ManagementList("lua-projects", projects.map { projectHeader(it, opens = true) }, emptyText = copy("empty")))
    fun project(project: LuaProject) = PluginPage("project/${project.manifest.id}", project.manifest.name, listOf(
        PageBlock.Group(copy("tool"), listOf(
            PageBlock.Actions(listOf(PageAction("debug/${project.manifest.id}", copy("debugBuild"), PluginIcon.SETTINGS))),
            PageBlock.Actions(listOf(PageAction("rename/${project.manifest.id}", copy("dialog.name"), PluginIcon.EDIT),
                PageAction("icon/${project.manifest.id}", copy("dialog.icon"), PluginIcon.IMAGE))),
            PageBlock.Actions(listOf(PageAction("files/${project.manifest.id}", copy("files"), PluginIcon.MENU),
                PageAction("test/${project.manifest.id}", copy("test"), PluginIcon.TEXT),
                PageAction("export/${project.manifest.id}", copy("export"), PluginIcon.EXPORT),
                PageAction("delete/${project.manifest.id}", copy("delete"), PluginIcon.CLOSE))),
        )),
        PageBlock.Group(copy("permissions"), listOf(PageBlock.Text(permissions(project.manifest)))),
    ) + (if (project.error.isNotEmpty()) listOf(PageBlock.Notice(copy("dialog.project_failed"))) else emptyList()), header = projectHeader(project))
    fun review(pending: LuaPending, previous: LuaProject?) = PluginPage("review/${pending.token}", if (previous == null) copy("import") else copy("update"), listOf(
        PageBlock.Group(pending.manifest.name, listOf(PageBlock.Text(copy("version", "version" to pending.manifest.version)), PageBlock.Text(permissions(pending.manifest)),
            PageBlock.Notice(copy("review")))),
        PageBlock.Group(copy("source"), listOf(PageBlock.Text(pending.directory.resolve("main.lua").readText(Charsets.UTF_8).take(12000)),
            PageBlock.Actions(listOf(PageAction("review-files/${pending.token}", copy("edit"), PluginIcon.EDIT),
                PageAction("review-export/${pending.token}", copy("exportSource"), PluginIcon.EXPORT))))),
        PageBlock.Actions(listOf(PageAction("confirm/${pending.token}", copy("save"), PluginIcon.ADD))),
    ))
    fun delete(project: LuaProject) = PluginPage("delete/${project.manifest.id}", copy("deleteTitle"), listOf(
        PageBlock.Notice(copy("deleteBody", "name" to project.manifest.name)),
        PageBlock.Actions(listOf(PageAction("delete-confirm/${project.manifest.id}", copy("confirmDelete"), PluginIcon.CLOSE))),
    ))
    fun files(draft: LuaDraft, files: LuaDraftFiles) = PluginPage("draft/${draft.token}", copy("files"), listOf(
        PageBlock.Files(copy("file"), files.items(draft), draft.directory, "file-open/${draft.token}", "file-operation/${draft.token}",
            "file-import/${draft.token}", "file-new/${draft.token}", "file-parent/${draft.token}"),
        PageBlock.Actions(listOf(PageAction("draft-review/${draft.token}", copy("save"), PluginIcon.ADD),
            PageAction("draft-close/${draft.token}", copy("discard"), PluginIcon.CLOSE))),
    ))
    fun editor(draft: LuaDraft, files: LuaDraftFiles) = PluginPage("edit/${draft.token}", draft.editing.substringAfterLast('/'), listOf(
        PageBlock.SourceEditor("lua-${draft.token}-${draft.editing.hashCode()}", files.file(draft, draft.editing).readText(Charsets.UTF_8).take(64000),
            "file-write/${draft.token}", language = if (draft.editing.endsWith(".lua")) "lua" else draft.editing.substringAfterLast('.', "text")),
        PageBlock.Actions(listOf(PageAction("validate/${draft.token}", copy("syntax"), PluginIcon.TEXT),
            PageAction("draft-review/${draft.token}", copy("save"), PluginIcon.ADD), PageAction("draft-back/${draft.token}", copy("files"), PluginIcon.MENU))),
    ))
    private fun permissions(manifest: LuaManifest): String = manifest.permissions.joinToString(copy("list.separator")) {
        when (it) { "app-control" -> copy("control"); "quick-text-read" -> copy("read"); "quick-text-write" -> copy("write"); else -> it }
    }.ifEmpty { copy("noPermission") }
}
