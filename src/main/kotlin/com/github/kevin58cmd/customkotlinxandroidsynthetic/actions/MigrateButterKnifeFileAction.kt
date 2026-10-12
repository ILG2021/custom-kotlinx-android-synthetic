package com.github.kevin58cmd.customkotlinxandroidsynthetic.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile

/** Starts with one selected file and previews its required binding dependencies. */
class MigrateButterKnifeFileAction : MigrateButterKnifeAction() {
    override val includeRelatedFiles = true

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun selectedFile(event: AnActionEvent): VirtualFile? {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (files != null && files.size != 1) return null
        return (files?.singleOrNull() ?: event.getData(CommonDataKeys.VIRTUAL_FILE))
            ?.takeIf { it.isValid && !it.isDirectory && it.extension in setOf("java", "kt") }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = true
        event.presentation.isEnabled = event.project != null && selectedFile(event) != null
        event.presentation.description = if (event.presentation.isEnabled) {
            "Preview and migrate ButterKnife in the selected file and required binding dependencies"
        } else {
            "Select one Java or Kotlin file to migrate ButterKnife"
        }
    }

    override fun selectTarget(event: AnActionEvent): VirtualFile? = selectedFile(event)
}
