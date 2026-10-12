package com.github.kevin58cmd.customkotlinxandroidsynthetic.actions

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.SystemBackMigration
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** Preview-first migration for legacy back handling in the selected source file. */
class MigrateSystemBackAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = selectedFile(event)
        event.presentation.isVisible = true
        event.presentation.isEnabled = event.project != null && file != null
        event.presentation.description = if (event.presentation.isEnabled) {
            "Preview and migrate legacy system back handling in the selected file"
        } else {
            "Select one Java or Kotlin file to migrate legacy system back handling"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = selectedFile(event) ?: return
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "请等待项目索引和 Gradle Sync 完成后再迁移。", "系统返回迁移")
            return
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val result = ReadAction.compute<SystemBackMigration.Result?, RuntimeException> {
            if (!file.isValid) null else SystemBackMigration.plan(PsiManager.getInstance(project).findFile(file) ?: return@compute null)
        }
        if (result == null) {
            SystemBackMigrationReport.notify(project, "当前文件没有发现旧式系统返回键处理代码。")
            return
        }
        if (result.after == null) {
            val root = project.basePath ?: run {
                Messages.showErrorDialog(project, "无法确定报告保存目录。请打开本地项目后重试。", "报告保存失败")
                return
            }
            try {
                val report = SystemBackMigrationReport.write(Path.of(root), listOf(MigrateSystemBackProjectAction.Companion.Entry(file, result)))!!
                SystemBackMigrationReport.open(project, report)
                SystemBackMigrationReport.notify(project, "此文件需要人工处理，报告已保存：$report")
            } catch (error: java.io.IOException) {
                Messages.showErrorDialog(project, "报告保存失败：${error.message}", "系统返回迁移")
            }
            return
        }
        if (!Preview(project, file.name, result.before, result.after, result.reason).showAndGet()) return
        if (document.text != result.before || !file.isValid) {
            Messages.showWarningDialog(project, "预览后文件已变化。请重新运行迁移，避免覆盖新改动。", "系统返回迁移")
            return
        }
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(file)).hasReadonlyFiles()) return
        if (!file.isValid || document.text != result.before) {
            Messages.showWarningDialog(project, "文件已变化，请重新运行迁移。", "系统返回迁移")
            return
        }
        WriteCommandAction.writeCommandAction(project).withName("Migrate legacy system back handling").run<RuntimeException> {
            document.setText(result.after)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        SystemBackMigrationReport.notify(project, "已迁移当前文件，可通过 Undo 撤销。请编译并检查返回行为。")
    }

    private fun selectedFile(event: AnActionEvent): VirtualFile? {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (files != null && files.size != 1) return null
        return (files?.singleOrNull() ?: event.getData(CommonDataKeys.VIRTUAL_FILE))
            ?.takeIf { it.isValid && !it.isDirectory && it.extension in setOf("java", "kt") }
    }

    private class Preview(project: Project, name: String, before: String, after: String, reason: String) : DialogWrapper(project) {
        private val panel = JPanel(BorderLayout(8, 8))
        init {
            title = "系统返回键迁移预览"
            setOKButtonText("应用迁移")
            val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            diff.setRequest(SimpleDiffRequest(name, DiffContentFactory.getInstance().create(project, before), DiffContentFactory.getInstance().create(project, after), "迁移前", "迁移后"))
            panel.add(JLabel("$reason 无法自动处理的情况会另存人工处理报告。"), BorderLayout.NORTH)
            panel.add(diff.component, BorderLayout.CENTER)
            panel.preferredSize = Dimension(1000, 680)
            init()
        }
        override fun createCenterPanel(): JComponent = panel
    }
}
