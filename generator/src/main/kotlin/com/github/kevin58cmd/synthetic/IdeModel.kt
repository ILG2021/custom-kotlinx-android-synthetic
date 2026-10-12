package com.github.kevin58cmd.synthetic

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Gradle exports the resolved model; the IDE never parses build scripts. */
object IdeModel {
    const val VERSION = "3"
    fun write(file: Path, spec: GenerationSpec) {
        val properties = Properties().apply {
            setProperty("version", VERSION)
            setProperty("namespace", spec.namespace)
            setProperty("output", spec.output.toAbsolutePath().toString())
            setProperty("androidX", spec.androidX.toString())
            setProperty("viewHolders", spec.viewHolders.toString())
            setProperty("platformFragments", spec.platformFragments.toString())
            setProperty("layers", spec.resourceLayers.size.toString())
            spec.resourceLayers.forEachIndexed { i, roots ->
                setProperty("layer.$i.size", roots.size.toString())
                roots.forEachIndexed { j, root -> setProperty("layer.$i.$j", root.toAbsolutePath().toString()) }
            }
            spec.typeOverrides.forEach { (tag, type) -> setProperty("type.$tag", type) }
            setProperty("classpath.size", spec.classpath.size.toString())
            spec.classpath.forEachIndexed { i, path -> setProperty("classpath.$i", path.toAbsolutePath().toString()) }
            setProperty("javaSourceRoots.size", spec.javaSourceRoots.size.toString())
            spec.javaSourceRoots.forEachIndexed { i, path -> setProperty("javaSourceRoots.$i", path.toAbsolutePath().toString()) }
        }
        val writer = java.io.StringWriter()
        properties.store(writer, null)
        // Drop the timestamp so no-change runs preserve file timestamps.
        Generator.writeIfChanged(file, writer.toString().lineSequence().filterNot { it.startsWith("#") }.sorted().joinToString("\n"))
    }

    fun read(file: Path): GenerationSpec {
        val p = Properties().apply { Files.newBufferedReader(file).use { load(it) } }
        require(p.getProperty("version") == VERSION) { "Unsupported synthetic IDE model; update the IDE and Gradle plugins together, then rerun generateSyntheticViews." }
        return GenerationSpec(
            p.getProperty("namespace"),
            List(p.getProperty("layers").toInt()) { i -> List(p.getProperty("layer.$i.size").toInt()) { j -> Path.of(p.getProperty("layer.$i.$j")) } },
            Path.of(p.getProperty("output")), p.getProperty("androidX").toBoolean(),
            p.getProperty("viewHolders").toBoolean(), p.getProperty("platformFragments").toBoolean(),
            p.stringPropertyNames().filter { it.startsWith("type.") }.associate { it.removePrefix("type.") to p.getProperty(it) },
            List(p.getProperty("classpath.size").toInt()) { Path.of(p.getProperty("classpath.$it")) },
            List(p.getProperty("javaSourceRoots.size").toInt()) { Path.of(p.getProperty("javaSourceRoots.$it")) },
        )
    }
}
