package com.kigtts.engines.lua

import com.kigtts.plugin.api.*

object LuaRuntimePages {
    fun manager(projects: List<LuaProject>) = PluginPage("manager", "插件编辑器", listOf(
        PageBlock.Group("Lua 工具", listOf(PageBlock.Actions(listOf(
            PageAction("choose", "导入工具", PluginIcon.ADD), PageAction("create", "新建工具", PluginIcon.EDIT))))),
        PageBlock.Group("构建扩展", listOf(PageBlock.Actions(listOf(PageAction("native-builder", "Java 与 Kotlin", PluginIcon.TEXT))))),
    ), ManagementList("lua-projects", projects.map { project ->
        ManagementItem(project.manifest.id, project.manifest.name, project.error.ifEmpty { project.manifest.version }, PluginIcon.TEXT,
            primaryAction = "project/${project.manifest.id}", toggle = ManagementToggle(project.enabled, "enable/${project.manifest.id}"))
    }, emptyText = "导入或创建自己的 Lua 工具"))
    fun project(project: LuaProject) = PluginPage("project/${project.manifest.id}", project.manifest.name, listOf(
        PageBlock.Group("工具", listOf(
            PageBlock.Toggle("启用", project.enabled, "enable/${project.manifest.id}", icon = PluginIcon.SETTINGS),
            PageBlock.Actions(listOf(PageAction("files/${project.manifest.id}", "项目文件", PluginIcon.MENU),
                PageAction("test/${project.manifest.id}", "测试", PluginIcon.TEXT),
                PageAction("export/${project.manifest.id}", "导出", PluginIcon.EXPORT),
                PageAction("delete/${project.manifest.id}", "删除", PluginIcon.CLOSE))),
        )),
        PageBlock.Group("权限", listOf(PageBlock.Text(permissions(project.manifest)))),
    ) + if (project.error.isNotEmpty()) listOf(PageBlock.Notice(project.error)) else emptyList())
    fun review(pending: LuaPending, previous: LuaProject?) = PluginPage("review/${pending.token}", if (previous == null) "安装工具" else "更新工具", listOf(
        PageBlock.Group(pending.manifest.name, listOf(PageBlock.Text("版本 ${pending.manifest.version}"), PageBlock.Text(permissions(pending.manifest)),
            PageBlock.Notice("确认后会运行此工具。请检查来源和代码；更新会保留工具数据。"))),
        PageBlock.Group("源代码", listOf(PageBlock.Text(pending.directory.resolve("main.lua").readText(Charsets.UTF_8).take(12000)),
            PageBlock.Actions(listOf(PageAction("review-files/${pending.token}", "查看与编辑项目文件", PluginIcon.EDIT),
                PageAction("review-export/${pending.token}", "导出完整源代码", PluginIcon.EXPORT))))),
        PageBlock.Actions(listOf(PageAction("confirm/${pending.token}", if (previous == null) "安装并启用" else "保存并启用", PluginIcon.ADD))),
    ))
    fun delete(project: LuaProject) = PluginPage("delete/${project.manifest.id}", "删除工具", listOf(
        PageBlock.Notice("删除“${project.manifest.name}”及其全部工具数据？"),
        PageBlock.Actions(listOf(PageAction("delete-confirm/${project.manifest.id}", "确认删除", PluginIcon.CLOSE))),
    ))
    fun files(draft: LuaDraft, files: LuaDraftFiles) = PluginPage("draft/${draft.token}", "项目文件", listOf(
        PageBlock.Files("文件", files.items(draft), draft.directory, "file-open/${draft.token}", "file-operation/${draft.token}",
            "file-import/${draft.token}", "file-new/${draft.token}", "file-parent/${draft.token}"),
        PageBlock.Actions(listOf(PageAction("draft-review/${draft.token}", "检查并保存", PluginIcon.ADD),
            PageAction("draft-close/${draft.token}", "放弃编辑", PluginIcon.CLOSE))),
    ))
    fun editor(draft: LuaDraft, files: LuaDraftFiles) = PluginPage("edit/${draft.token}", draft.editing.substringAfterLast('/'), listOf(
        PageBlock.SourceEditor("lua-${draft.token}-${draft.editing.hashCode()}", files.file(draft, draft.editing).readText(Charsets.UTF_8).take(64000),
            "file-write/${draft.token}", language = if (draft.editing.endsWith(".lua")) "lua" else draft.editing.substringAfterLast('.', "text")),
        PageBlock.Actions(listOf(PageAction("validate/${draft.token}", "检查语法", PluginIcon.TEXT),
            PageAction("draft-review/${draft.token}", "检查并保存", PluginIcon.ADD), PageAction("draft-back/${draft.token}", "项目文件", PluginIcon.MENU))),
    ))
    private fun permissions(manifest: LuaManifest): String = manifest.permissions.joinToString("、") {
        when (it) { "app-control" -> "操作软件"; "quick-text-read" -> "读取快捷文本"; "quick-text-write" -> "管理快捷文本"; else -> it }
    }.ifEmpty { "无需额外权限" }
}
