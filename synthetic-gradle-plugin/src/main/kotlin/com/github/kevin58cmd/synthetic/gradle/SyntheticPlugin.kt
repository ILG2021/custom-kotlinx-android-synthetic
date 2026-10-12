package com.github.kevin58cmd.synthetic.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import com.github.kevin58cmd.synthetic.GenerationSpec
import com.github.kevin58cmd.synthetic.Generator
import com.github.kevin58cmd.synthetic.IdeModel
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Path

abstract class SyntheticExtension {
    abstract val androidX: Property<Boolean>
    // Legacy configuration accepted for compatibility; no longer affects generated receivers.
    abstract val viewHolders: Property<Boolean>
    abstract val platformFragments: Property<Boolean>
    abstract val typeOverrides: MapProperty<String, String>

    init {
        androidX.convention(true)
        viewHolders.convention(false)
        platformFragments.convention(false)
        typeOverrides.convention(emptyMap())
    }
}

@DisableCachingByDefault(because = "The accompanying IDE model contains machine-local absolute resource paths")
abstract class GenerateSyntheticTask : DefaultTask() {
    @get:Input abstract val namespace: Property<String>
    @get:Input abstract val resourceLayers: ListProperty<List<String>>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: ConfigurableFileCollection
    @get:Input abstract val androidX: Property<Boolean>
    @get:Input abstract val viewHolders: Property<Boolean>
    @get:Input abstract val platformFragments: Property<Boolean>
    @get:Input abstract val typeOverrides: MapProperty<String, String>
    @get:Classpath abstract val viewClasspath: ConfigurableFileCollection
    @get:Input abstract val javaSourceRoots: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javaSources: ConfigurableFileCollection
    // The IDE model contains these addresses, while @Classpath normalizes them away.
    @get:Input val viewClasspathPaths: List<String>
        get() = viewClasspath.files.map { it.absoluteFile.toPath().normalize().toString() }
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:OutputFile abstract val ideModel: RegularFileProperty

    @TaskAction fun generate() {
        val spec = GenerationSpec(namespace.get(), resourceLayers.get().map { layer -> layer.map(Path::of) },
            outputDirectory.get().asFile.toPath(), androidX.get(), viewHolders.get(), platformFragments.get(), typeOverrides.get(),
            viewClasspathPaths.map(Path::of), javaSourceRoots.get().map(Path::of))
        Generator.generate(spec).forEach { logger.warn(it) }
        IdeModel.write(ideModel.get().asFile.toPath(), spec)
    }
}

class SyntheticPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("syntheticCompat", SyntheticExtension::class.java)
        val generateAll = project.tasks.register("generateSyntheticViews") {
            it.group = "code generation"
            it.description = "Generate synthetic properties and IDE models for all Android variants"
        }
        // Attach where KGP provides this hook. Android-only projects may still need an initial build.
        project.tasks.matching { it.name == "prepareKotlinIdeaImport" }.configureEach { it.dependsOn(generateAll) }
        listOf("com.android.application", "com.android.library", "com.android.dynamic-feature").forEach { pluginId ->
            project.pluginManager.withPlugin(pluginId) {
                val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
                components.onVariants(components.selector().all()) { variant ->
                    val res = variant.sources.res ?: return@onVariants
                    val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
                    val modelKey = project.path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
                    val model = project.rootProject.layout.projectDirectory.file(".gradle/synthetic-compat/$modelKey-${variant.name}.properties")
                    val task = project.tasks.register("generate${suffix}SyntheticViews", GenerateSyntheticTask::class.java) { t ->
                        t.group = "code generation"
                        t.namespace.set(variant.namespace)
                        // AGP returns highest priority first; the generator applies overlays low to high.
                        t.resourceLayers.set(res.all.map { layers -> layers.reversed().map { roots -> roots.map { it.asFile.absolutePath }.sorted() } })
                        t.resources.from(res.all)
                        t.androidX.set(extension.androidX)
                        t.viewHolders.set(extension.viewHolders)
                        t.platformFragments.set(extension.platformFragments)
                        t.typeOverrides.set(extension.typeOverrides)
                        t.javaSourceRoots.convention(emptyList())
                        // Static roots exclude generated Kotlin output and its compile task dependencies.
                        variant.sources.java?.static?.let { roots ->
                            t.javaSourceRoots.set(roots.map { dirs -> dirs.map { it.asFile.absolutePath } })
                            t.javaSources.from(roots.map { dirs -> dirs.map { it.asFileTree.matching { pattern -> pattern.include("**/*.java") } } })
                        }
                        // compileClasspath also contains this variant's Kotlin output, which would
                        // make generation depend on the compilation that consumes generated sources.
                        t.viewClasspath.from(variant.compileConfiguration.incoming.artifactView { view ->
                            view.attributes.attribute(org.gradle.api.attributes.Attribute.of("artifactType", String::class.java), "android-classes-jar")
                        }.files)
                        t.ideModel.set(model)
                    }
                    // AGP 8's external Kotlin plugin consumes the Java source model. The separate
                    // Kotlin model is used by AGP 9's built-in Kotlin instead.
                    val kotlinSources = checkNotNull(if (components.pluginVersion.major >= 9) variant.sources.kotlin else variant.sources.java) {
                        "synthetic-compat requires Kotlin Android support in module ${project.path}"
                    }
                    kotlinSources.addGeneratedSourceDirectory(task, GenerateSyntheticTask::outputDirectory)
                    generateAll.configure { it.dependsOn(task) }
                }
            }
        }
    }
}
