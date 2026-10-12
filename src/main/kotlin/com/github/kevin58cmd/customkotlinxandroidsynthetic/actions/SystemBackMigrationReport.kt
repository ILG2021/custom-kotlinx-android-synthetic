package com.github.kevin58cmd.customkotlinxandroidsynthetic.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Persist manual-review cases before preview so canceling or an all-skipped scan loses no details. */
internal object SystemBackMigrationReport {
    fun render(entries: List<MigrateSystemBackProjectAction.Companion.Entry>): String = buildString {
        val skipped = entries.filter { it.result.after == null }
        appendLine("系统返回键迁移：人工处理报告")
        appendLine("扫描时间：${LocalDateTime.now()}")
        appendLine("可自动迁移：${entries.count { it.result.after != null }} 个文件；需人工处理：${skipped.size} 个文件。")
        appendLine("本报告记录扫描时的状态，不表示可迁移文件已经应用。需人工处理的源码不会由工具修改。")
        skipped.forEachIndexed { index, entry ->
            appendLine()
            appendLine("${index + 1}. ${entry.file.path}")
            appendLine("原因：${entry.result.reason}")
            val locations = entry.result.locations
            if (locations.isEmpty()) {
                appendLine("源码：")
                appendLine(entry.result.before)
            } else locations.forEach { location ->
                appendLine("位置：${entry.file.path}:${location.line}")
                appendLine("代码：")
                appendLine(location.code)
            }
            appendLine("建议：按上述原因检查返回回调、Activity 基类及依赖；保留条件判断和非返回按键逻辑，修改后编译并测试返回行为。")
        }
    }

    fun write(root: Path, entries: List<MigrateSystemBackProjectAction.Companion.Entry>): Path? {
        if (entries.none { it.result.after == null }) return null
        val directory = root.resolve(".synthetic-compat/reports")
        Files.createDirectories(directory)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val path = directory.resolve("system-back-$stamp-${UUID.randomUUID()}.txt")
        Files.writeString(path, render(entries), StandardCharsets.UTF_8)
        return path
    }

    fun open(project: Project, path: Path) {
        if (project.isDisposed) return
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
        }
    }

    fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Synthetic compatibility")
            .createNotification("系统返回键迁移", message, NotificationType.INFORMATION).notify(project)
    }
}
