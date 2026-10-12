package com.github.kevin58cmd.customkotlinxandroidsynthetic.actions

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.SystemBackMigration
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.*

/** Scans every module's production sources, then previews safe changes and manual-review cases. */
class MigrateSystemBackProjectAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = true
        event.presentation.isEnabled = event.project?.let { !DumbService.isDumb(it) } == true
        event.presentation.description = "Scan all project Java/Kotlin source files; preview migrations and manual-review cases"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "请等待项目索引和 Gradle Sync 完成后再迁移。", "全项目系统返回迁移")
            return
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        object : Task.Backgroundable(project, "扫描全项目系统返回键处理", true) {
            private var entries = emptyList<Entry>()
            private var report: Path? = null

            override fun run(indicator: ProgressIndicator) {
                entries = scan(project, { indicator.checkCanceled() }, { indicator.text2 = it.path })
                indicator.checkCanceled()
                if (entries.any { it.result.after == null }) {
                    val root = project.basePath ?: error("无法确定报告保存目录。请打开本地项目后重新扫描。")
                    report = SystemBackMigrationReport.write(Path.of(root), entries)
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                if (entries.isEmpty()) {
                    SystemBackMigrationReport.notify(project, "全项目源码中没有发现尚需处理的旧式返回键代码。")
                    return
                }
                report?.let {
                    SystemBackMigrationReport.open(project, it)
                    SystemBackMigrationReport.notify(project, "人工处理报告已保存：$it")
                }
                val changes = entries.filter { it.result.after != null }
                if (changes.isEmpty()) return
                if (!Preview(project, changes).showAndGet()) return
                apply(project, changes)
            }

            override fun onThrowable(error: Throwable) {
                if (!project.isDisposed) Messages.showErrorDialog(project, error.message ?: error.javaClass.simpleName,
                    "全项目系统返回扫描失败（未修改文件）")
            }
        }.queue()
    }

    companion object {
        internal data class Entry(val file: VirtualFile, val result: SystemBackMigration.Result) {
            override fun toString() = "${if (result.after == null) "[需人工处理]" else "[可迁移]"} ${file.path}"
        }

        internal fun scan(project: Project, checkCanceled: () -> Unit = {}, onFile: (VirtualFile) -> Unit = {}): List<Entry> {
            val files = ReadAction.compute<List<VirtualFile>, RuntimeException> {
                MigrateButterKnifeAction.collectMigrationFiles(project, null, checkCanceled)
            }
            return files.mapNotNull { file ->
                checkCanceled()
                onFile(file)
                ReadAction.compute<Entry?, RuntimeException> {
                    if (!file.isValid || project.isDisposed) return@compute null
                    val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute null
                    SystemBackMigration.plan(psi)?.let { Entry(file, it) }
                }
            }.sortedBy { it.file.path }
        }

        internal fun applyChanges(project: Project, entries: List<Entry>): Boolean {
            val changes = entries.filter { it.result.after != null }
            if (project.isDisposed || changes.isEmpty()) return false
            val manager = FileDocumentManager.getInstance()
            val documents = changes.map { if (it.file.isValid) manager.getDocument(it.file) else null }
            fun unchanged() = changes.indices.all { changes[it].file.isValid && documents[it]?.text == changes[it].result.before }
            if (!unchanged()) return false
            var applied = false
            WriteCommandAction.writeCommandAction(project).withName("Migrate system back handling across project")
                .withGlobalUndo().run<RuntimeException> {
                    // Check again inside the write command before changing any file.
                    if (unchanged()) {
                        changes.indices.forEach { documents[it]!!.setText(changes[it].result.after!!) }
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        applied = true
                    }
                }
            return applied
        }
    }

    private fun apply(project: Project, entries: List<Entry>) {
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(entries.map { it.file }).hasReadonlyFiles()) return
        if (!applyChanges(project, entries)) {
            Messages.showWarningDialog(project, "扫描后文件已变化或不可用。请重新扫描，避免覆盖新改动。", "全项目系统返回迁移")
            return
        }
        SystemBackMigrationReport.notify(project, "已迁移 ${entries.size} 个文件，可通过 Undo 统一撤销。请编译并验证返回行为。")
    }

    private class Preview(project: Project, entries: List<Entry>) : DialogWrapper(project) {
        private val panel = JPanel(BorderLayout(8, 8))
        init {
            val count = entries.count { it.result.after != null }
            title = "全项目系统返回键迁移预览"
            setOKButtonText("应用 $count 个文件的改动")
            isOKActionEnabled = count > 0
            val list = JList(entries.toTypedArray()).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
            val details = JTextArea(3, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
            val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            list.addListSelectionListener {
                val entry = list.selectedValue ?: return@addListSelectionListener
                details.text = entry.result.reason
                val after = entry.result.after
                diff.setRequest(if (after == null) {
                    MessageDiffRequest(entry.file.path, "需要人工处理：\n${entry.result.reason}")
                } else {
                    val factory = DiffContentFactory.getInstance()
                    SimpleDiffRequest(entry.file.path, factory.create(project, entry.result.before),
                        factory.create(project, after), "迁移前", "迁移后")
                })
            }
            val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(list), diff.component).apply { resizeWeight = 0.25 }
            panel.add(JLabel("以下为 $count 个可自动迁移文件；需人工处理的情况（如有）已另存报告。"), BorderLayout.NORTH)
            panel.add(split, BorderLayout.CENTER)
            panel.add(JScrollPane(details), BorderLayout.SOUTH)
            panel.preferredSize = Dimension(1100, 750)
            init()
            list.selectedIndex = 0
        }
        override fun createCenterPanel(): JComponent = panel
    }
}
