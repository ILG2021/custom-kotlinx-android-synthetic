package com.github.kevin58cmd.customkotlinxandroidsynthetic.actions

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ButterKnifeMigration
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class MigrateResourceSwitchAction : AnAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let { !com.intellij.openapi.project.DumbService.isDumb(it) } == true
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        object : Task.Backgroundable(project, "扫描全项目 R.id switch", true) {
            val results = mutableListOf<Entry>()
            override fun run(indicator: ProgressIndicator) {
                val files = ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    MigrateButterKnifeAction.collectMigrationFiles(project, null) { indicator.checkCanceled() }
                }
                files.filter { it.extension == "java" }.forEach { file ->
                    indicator.checkCanceled()
                    indicator.text2 = file.path
                    ReadAction.run<RuntimeException> {
                        val psi = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile
                        if (psi != null) com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ResourceSwitchMigration.plan(psi)?.let {
                            results += Entry(file, it)
                        }
                    }
                }
            }
            override fun onSuccess() {
                if (project.isDisposed) return
                if (results.isEmpty()) {
                    Messages.showInfoMessage(project, "没有找到可识别的非恒定 R.id switch 或可清理的已生成代码块。请先完成 Gradle Sync；无法解析的引用不会修改。", "R.id switch 转换")
                    return
                }
                val entries = results.sortedBy { it.file.path }
                if (!Preview(project, entries).showAndGet()) return
                apply(project, entries.filter { it.result.after != null })
            }
            override fun onThrowable(error: Throwable) {
                if (!project.isDisposed) Messages.showErrorDialog(project, error.message ?: error.javaClass.simpleName, "R.id switch 扫描失败（未修改文件）")
            }
        }.queue()
    }

    private fun apply(project: Project, entries: List<Entry>) {
        if (entries.isEmpty()) return
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(entries.map { it.file }).hasReadonlyFiles()) return
        val manager = FileDocumentManager.getInstance()
        val documents = entries.map { manager.getDocument(it.file) }
        if (entries.indices.any { !entries[it].file.isValid || documents[it]?.text != entries[it].result.before }) {
            Messages.showWarningDialog(project, "扫描后文件已修改或不可用。请重新运行迁移，避免覆盖新改动。", "R.id switch 转换")
            return
        }
        WriteCommandAction.writeCommandAction(project).withName("Convert resource switches to if / else").withGlobalUndo().run<RuntimeException> {
            entries.indices.forEach { documents[it]!!.setText(entries[it].result.after!!) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        Messages.showInfoMessage(project, "已转换 ${entries.size} 个文件，可通过 Undo 统一撤销。请编译验证；跳过的位置保持不变。", "R.id switch 转换完成")
    }

    private data class Entry(val file: VirtualFile, val result: ButterKnifeMigration.Result) {
        override fun toString(): String = "${if (result.after == null) "[跳过]" else "[转换]"} ${file.path}"
    }

    private class Preview(project: Project, entries: List<Entry>) : DialogWrapper(project) {
        private val panel = JPanel(BorderLayout(8, 8))
        init {
            title = "R.id switch 转换预览"
            setOKButtonText("应用 ${entries.count { it.result.after != null }} 个文件的改动")
            isOKActionEnabled = entries.any { it.result.after != null }
            val list = JList(entries.toTypedArray())
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            val details = JTextArea(3, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
            val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            list.addListSelectionListener {
                val entry = list.selectedValue ?: return@addListSelectionListener
                details.text = entry.result.reason ?: "此文件可转换。"
                val factory = DiffContentFactory.getInstance()
                if (entry.result.after == null) {
                    diff.setRequest(MessageDiffRequest(entry.file.path, "此文件已跳过，未做任何修改。\n\n原因：${entry.result.reason ?: "未找到可转换内容"}"))
                } else {
                    diff.setRequest(SimpleDiffRequest(entry.file.path, factory.create(project, entry.result.before), factory.create(project, entry.result.after), "迁移前", "迁移后"))
                }
            }
            val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(list), diff.component)
            split.resizeWeight = 0.25
            panel.add(JLabel("全项目非测试 Java 源码；请预览改动及跳过原因。"), BorderLayout.NORTH)
            panel.add(split, BorderLayout.CENTER)
            panel.add(JScrollPane(details), BorderLayout.SOUTH)
            panel.preferredSize = Dimension(1100, 750)
            init()
            list.selectedIndex = 0
        }
        override fun createCenterPanel(): JComponent = panel
    }
}
