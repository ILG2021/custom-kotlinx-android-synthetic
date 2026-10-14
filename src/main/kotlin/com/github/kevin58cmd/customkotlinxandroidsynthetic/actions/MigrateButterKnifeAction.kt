package com.github.kevin58cmd.customkotlinxandroidsynthetic.actions

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ButterKnifeMigration
import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.MixedButterKnifeMigration
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.GeneratedSourcesFilter
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

class MigrateButterKnifeAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.let { !DumbService.isDumb(it) } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "请等待项目索引和 Gradle Sync 完成后再迁移。", "ButterKnife 迁移")
            return
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        object : Task.Backgroundable(project, "扫描 ButterKnife Java/Kotlin 用法", true) {
            val results = mutableListOf<Entry>()
            override fun run(indicator: ProgressIndicator) {
                val files = ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    collectMigrationFiles(project, null) { indicator.checkCanceled() }
                }
                val candidates = files.mapNotNull { file ->
                    indicator.checkCanceled()
                    indicator.text2 = file.path
                    ReadAction.compute<PsiFile?, RuntimeException> {
                        if (project.isDisposed || !file.isValid) null else {
                            val psi = PsiManager.getInstance(project).findFile(file)?.takeIf { it is PsiJavaFile || it is KtFile }
                            psi?.takeIf { it.text.contains("butterknife") || it.text.contains("@Bind") || it.text.contains("@On") }
                        }
                    }
                }
                MixedButterKnifeMigration.planBatch(candidates).forEach { (file, result) -> results += Entry(file.virtualFile, result) }
            }
            override fun onSuccess() {
                if (project.isDisposed) return
                if (results.isEmpty()) {
                    Messages.showInfoMessage(project, "整个项目的非测试 Java/Kotlin 源码中没有找到 ButterKnife 用法。请先完成 Gradle Sync。", "ButterKnife 迁移")
                    return
                }
                val entries = results.sortedBy { it.file.path }
                if (!Preview(project, entries).showAndGet()) return
                apply(project, entries.filter { it.result.after != null })
            }
            override fun onThrowable(error: Throwable) {
                if (!project.isDisposed) Messages.showErrorDialog(project, error.message ?: error.javaClass.simpleName, "ButterKnife 扫描失败（未修改文件）")
            }
        }.queue()
    }

    companion object {
        internal fun collectMigrationFiles(project: Project, target: VirtualFile?, checkCanceled: () -> Unit = {}): List<VirtualFile> {
            val index = ProjectFileIndex.getInstance(project)
            val found = mutableListOf<VirtualFile>()
            fun visit(file: VirtualFile): Boolean {
                checkCanceled()
                if (file.isValid && !file.isDirectory && file.extension in setOf("java", "kt") &&
                    index.isInSourceContent(file) && !index.isInTestSourceContent(file) && !index.isExcluded(file) &&
                    !GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project)) found += file
                return true
            }
            if (target == null) index.iterateContent { visit(it) } else if (target.isDirectory) index.iterateContentUnderDirectory(target) { visit(it) } else visit(target)
            return found
        }
    }
    private fun apply(project: Project, entries: List<Entry>) {
        if (entries.isEmpty()) return
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(entries.map { it.file }).hasReadonlyFiles()) return
        val manager = FileDocumentManager.getInstance()
        val documents = entries.map { manager.getDocument(it.file) }
        if (entries.indices.any { !entries[it].file.isValid || documents[it]?.text != entries[it].result.before }) {
            Messages.showWarningDialog(project, "扫描后文件已修改或不可用。请重新运行迁移，避免覆盖新改动。", "ButterKnife 迁移")
            return
        }
        WriteCommandAction.writeCommandAction(project).withName("Migrate ButterKnife to findViewById").withGlobalUndo().run<RuntimeException> {
            entries.indices.forEach { documents[it]!!.setText(entries[it].result.after!!) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        Messages.showInfoMessage(project, "已转换 ${entries.size} 个文件，可通过 Undo 撤销。\n请编译并检查界面行为；跳过的文件保持不变。\n生成代码不依赖 ButterKnife。确认整个项目无残留后，可移除 Gradle 中的 ButterKnife 依赖。", "ButterKnife 迁移完成")
    }

    private data class Entry(val file: VirtualFile, val result: ButterKnifeMigration.Result) {
        override fun toString(): String = "${if (result.after == null) "[跳过]" else "[转换]"} ${file.path}"
    }

    private class Preview(project: Project, entries: List<Entry>) : DialogWrapper(project) {
        private val panel = JPanel(BorderLayout(8, 8))
        init {
            title = "全项目 ButterKnife 迁移预览"
            setOKButtonText("应用 ${entries.count { it.result.after != null }} 个文件的改动")
            isOKActionEnabled = entries.any { it.result.after != null }
            val list = JList(entries.toTypedArray())
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            val details = JTextArea(3, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
            val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            list.addListSelectionListener {
                val entry = list.selectedValue ?: return@addListSelectionListener
                details.text = entry.result.reason ?: "此文件可转换。保留原来的绑定时机和根 View；Gradle 配置不会自动修改。"
                val factory = DiffContentFactory.getInstance()
                if (entry.result.after == null) {
                    diff.setRequest(MessageDiffRequest(entry.file.path, "此文件已跳过，未做任何修改。\n\n原因：${entry.result.reason ?: "未找到可转换内容"}"))
                } else {
                    diff.setRequest(SimpleDiffRequest(entry.file.path, factory.create(project, entry.result.before), factory.create(project, entry.result.after), "迁移前", "迁移后"))
                }
            }
            val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(list), diff.component)
            split.resizeWeight = 0.25
            panel.add(JLabel("扫描整个项目的非测试 Java/Kotlin 源码；跳过原因见下方。应用后可统一撤销。"), BorderLayout.NORTH)
            panel.add(split, BorderLayout.CENTER)
            panel.add(JScrollPane(details), BorderLayout.SOUTH)
            panel.preferredSize = Dimension(1100, 750)
            init()
            list.selectedIndex = 0
        }
        override fun createCenterPanel(): JComponent = panel
    }
}
