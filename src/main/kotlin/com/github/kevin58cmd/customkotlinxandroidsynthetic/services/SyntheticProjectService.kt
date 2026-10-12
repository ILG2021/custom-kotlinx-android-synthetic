package com.github.kevin58cmd.customkotlinxandroidsynthetic.services

import com.github.kevin58cmd.synthetic.GenerationSpec
import com.github.kevin58cmd.synthetic.Generator
import com.github.kevin58cmd.synthetic.IdeModel
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsLocator
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class SyntheticProjectService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(SyntheticProjectService::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val modelDirectory = project.basePath?.let { Path.of(it, ".gradle", "synthetic-compat") }
    @Volatile private var specs: List<GenerationSpec> = emptyList()
    private var modelFingerprint = ""
    private val reported = mutableSetOf<String>()
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var started = false
    @Volatile private var disposed = false

    @Synchronized fun start() {
        if (started || disposed || modelDirectory == null) return
        started = true
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val roots = specs.flatMap { it.resourceLayers.flatten() + it.javaSourceRoots }
                if (events.filter { it.fileSystem.protocol == "file" }.any { event ->
                    val path = Path.of(event.path).toAbsolutePath().normalize()
                    roots.any { root -> path.startsWith(root) || root.startsWith(path) }
                }) pending.set(true)
            }
        })
        schedule(0)
    }

    private fun schedule(delay: Int) {
        if (!project.isDisposed && !disposed) alarm.addRequest({ tick() }, delay)
    }

    private fun tick() {
        if (project.isDisposed || disposed) return
        try {
            if (!TrustedProjects.isProjectTrusted(TrustedProjectsLocator.locateProject(project))) return
            val directory = modelDirectory ?: return
            // .gradle is excluded from VFS. Poll only the tiny model directory to discover first-sync output.
            val models = if (Files.isDirectory(directory)) Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".properties") }.sorted().toList()
            } else emptyList()
            val fingerprint = models.joinToString("|") { "$it:${Files.getLastModifiedTime(it)}:${Files.size(it)}" }
            if (fingerprint != modelFingerprint) {
                specs = models.map { IdeModel.read(it) }
                modelFingerprint = fingerprint
                pending.set(true)
            }
            if (pending.getAndSet(false)) {
                for (spec in specs) {
                    if (project.isDisposed || disposed) break
                    try {
                        Generator.generate(spec).forEach { report(it, NotificationType.WARNING) }
                        LocalFileSystem.getInstance().refreshIoFiles(listOf(spec.output.toFile()), true, true, null)
                    } catch (e: Exception) {
                        report(e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
                        log.warn("Synthetic generation failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            report(e.message ?: "Could not load synthetic Gradle models", NotificationType.ERROR)
            log.warn(e)
        } finally {
            schedule(750)
        }
    }

    private fun report(message: String, type: NotificationType) {
        if (!reported.add(message) || project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup("Synthetic compatibility")
            .createNotification("Synthetic compatibility", message, type).notify(project)
    }

    override fun dispose() { disposed = true }
}
