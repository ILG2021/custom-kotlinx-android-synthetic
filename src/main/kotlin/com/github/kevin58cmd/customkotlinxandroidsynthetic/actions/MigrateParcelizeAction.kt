package com.github.kevin58cmd.customkotlinxandroidsynthetic.actions

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ParcelizeMigration
import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ParcelizeGradleMigration
import org.jetbrains.kotlin.psi.KtFile
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class MigrateParcelizeAction : AnAction() {

    private fun selectTarget(event: AnActionEvent): VirtualFile? {
        val project = event.project ?: return null
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("选择迁移目录（项目、模块或源码目录）")
        return FileChooser.chooseFile(descriptor, project, event.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE)?.let { if (it.isDirectory) it else it.parent })
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val folder = selectTarget(event) ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        object : Task.Backgroundable(project, "扫描旧版 Parcelize 引用", true) {
            val results = mutableListOf<Entry>()
            val buildSnapshots = linkedMapOf<VirtualFile, String>()
            override fun run(indicator: ProgressIndicator) {
                ReadAction.run<RuntimeException> {
                    val manager = FileDocumentManager.getInstance()
                    val sources = collectMigrationFiles(project, folder) { indicator.checkCanceled() }.mapNotNull { file ->
                        indicator.checkCanceled()
                        indicator.text2 = file.path
                        val psi = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return@mapNotNull null
                        val migration = ParcelizeMigration.plan(psi)
                        if (migration == null && !ParcelizeMigration.hasModernReferences(psi)) return@mapNotNull null
                        Entry(file, migration ?: ParcelizeMigration.Result(psi.text, psi.text))
                    }
                    sources.groupBy { findBuildScript(project, it.file) }.forEach { (build, entries) ->
                        indicator.checkCanceled()
                        val before = build?.let { manager.getDocument(it)?.text }
                        val configuration = before?.let { ParcelizeGradleMigration.plan(it, build.name.endsWith(".kts")) }
                        if (build == null || configuration?.after == null) {
                            val reason = configuration?.reason ?: "未找到对应模块的 build.gradle(.kts)，请先完成 Gradle Sync 并检查模块配置。"
                            entries.forEach { results += it.copy(applicable = false, note = reason) }
                        } else {
                            buildSnapshots[build] = configuration.before
                            entries.filter { it.result.before != it.result.after }.forEach { results += it }
                            if (configuration.before != configuration.after) {
                                results += Entry(build, ParcelizeMigration.Result(configuration.before, configuration.after), note = configuration.reason)
                            }
                        }
                    }
                }
            }
            override fun onSuccess() {
                if (project.isDisposed) return
                if (results.isEmpty()) {
                    Messages.showInfoMessage(project, "所选范围的 Kotlin 源码中没有找到需要迁移的 Parcelize 引用或 Gradle 配置。请先完成 Gradle Sync。", "Parcelize 迁移")
                    return
                }
                val entries = results.sortedBy { it.file.path }
                if (!Preview(project, entries, folder).showAndGet()) return
                apply(project, entries.filter { it.applicable }, buildSnapshots)
            }
            override fun onThrowable(error: Throwable) {
                if (!project.isDisposed) Messages.showErrorDialog(project, error.message ?: error.javaClass.simpleName, "Parcelize 扫描失败（未修改文件）")
            }
        }.queue()
    }

    companion object {
        internal fun findBuildScript(project: Project, source: VirtualFile): VirtualFile? {
            val base = project.basePath
            val index = ProjectFileIndex.getInstance(project)
            var directory = source.parent
            while (directory != null && (index.isInContent(directory) ||
                    base != null && (directory.path == base || directory.path.startsWith("$base/")))) {
                val scripts = listOfNotNull(directory.findChild("build.gradle.kts"), directory.findChild("build.gradle"))
                if (scripts.size > 1) return null
                if (scripts.size == 1) return scripts.single()
                directory = directory.parent
            }
            return null
        }

        internal fun collectMigrationFiles(project: Project, target: VirtualFile?, checkCanceled: () -> Unit = {}): List<VirtualFile> {
            val index = ProjectFileIndex.getInstance(project)
            val found = mutableListOf<VirtualFile>()
            fun visit(file: VirtualFile): Boolean {
                checkCanceled()
                if (file.isValid && !file.isDirectory && file.extension == "kt" &&
                    index.isInSourceContent(file) &&
                    file.path.split('/').none { it in setOf("build", ".gradle", ".git", "generated") }) found += file
                return true
            }
            if (target == null) index.iterateContent { visit(it) } else if (target.isDirectory) index.iterateContentUnderDirectory(target) { visit(it) } else visit(target)
            return found
        }
    }
    private fun apply(project: Project, entries: List<Entry>, buildSnapshots: Map<VirtualFile, String>) {
        if (entries.isEmpty()) return
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(entries.map { it.file }).hasReadonlyFiles()) return
        val manager = FileDocumentManager.getInstance()
        val documents = entries.map { manager.getDocument(it.file) }
        if (entries.indices.any { !entries[it].file.isValid || documents[it]?.text != entries[it].result.before } ||
            buildSnapshots.any { (file, before) -> !file.isValid || manager.getDocument(file)?.text != before }) {
            Messages.showWarningDialog(project, "扫描后文件已修改或不可用。请重新运行迁移，避免覆盖新改动。", "Parcelize 迁移")
            return
        }
        WriteCommandAction.writeCommandAction(project).withName("Migrate legacy Parcelize references").withGlobalUndo().run<RuntimeException> {
            entries.indices.forEach { documents[it]!!.setText(entries[it].result.after) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        Messages.showInfoMessage(project, "已迁移 ${entries.size} 个文件，可通过 Undo 撤销。\n已同时处理对应模块的 Parcelize Gradle 配置。请执行 Gradle Sync 并编译；跳过的模块请按预览说明处理。", "Parcelize 迁移完成")
    }

    private data class Entry(val file: VirtualFile, val result: ParcelizeMigration.Result, val applicable: Boolean = true, val note: String = "迁移 Kotlin Parcelize 引用；对应模块 Gradle 配置一起预览和应用。") {
        override fun toString(): String = "${if (applicable) "[转换]" else "[跳过]"} ${file.path}"
    }

    private class Preview(project: Project, entries: List<Entry>, selected: VirtualFile?) : DialogWrapper(project) {
        private val panel = JPanel(BorderLayout(8, 8))
        init {
            title = "Parcelize 迁移预览"
            setOKButtonText("应用 ${entries.count { it.applicable }} 个文件的改动")
            isOKActionEnabled = entries.any { it.applicable }
            val list = JList(entries.toTypedArray())
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            val details = JTextArea(3, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
            val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            list.addListSelectionListener {
                val entry = list.selectedValue ?: return@addListSelectionListener
                details.text = entry.note
                val factory = DiffContentFactory.getInstance()
                diff.setRequest(SimpleDiffRequest(entry.file.path, factory.create(project, entry.result.before), factory.create(project, if (entry.applicable) entry.result.after else entry.result.before), "迁移前", "迁移后"))
            }
            val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(list), diff.component)
            split.resizeWeight = 0.25
            panel.add(JLabel("${selected?.path}：共 ${entries.size} 个源码/Gradle 文件（含测试源码），应用后可统一撤销。"), BorderLayout.NORTH)
            panel.add(split, BorderLayout.CENTER)
            panel.add(JScrollPane(details), BorderLayout.SOUTH)
            panel.preferredSize = Dimension(1100, 750)
            init()
            list.selectedIndex = 0
        }
        override fun createCenterPanel(): JComponent = panel
    }
}
