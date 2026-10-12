package com.github.kevin58cmd.synthetic

import com.sun.source.tree.ClassTree
import com.sun.source.util.JavacTask
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/** Parse declarations only: Android dependencies and compiled app classes are unnecessary. */
internal class JavaViewTypes(private val roots: List<Path>) {
    private data class Index(val parameters: Map<String, Int>, val errors: Map<String, String>)
    private val declarations: Index by lazy {
        val files = roots.filter(Files::isDirectory).flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.sorted().toList()
            }
        }.distinct()
        if (files.isEmpty()) return@lazy Index(emptyMap(), emptyMap())
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Java source view inference requires a JDK with jdk.compiler." }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none", "-encoding", "UTF-8"), null,
                manager.getJavaFileObjectsFromPaths(files)) as JavacTask
            val units = task.parse().toList()
            val fileErrors = diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }
                .groupBy { it.source?.toUri() }
            val result = linkedMapOf<String, Int>()
            val errors = linkedMapOf<String, String>()
            fun record(tree: ClassTree, prefix: String, error: String?) {
                val name = if (prefix.isEmpty()) tree.simpleName.toString() else "$prefix.${tree.simpleName}"
                if (result.putIfAbsent(name, tree.typeParameters.size) != null) errors[name] = "Duplicate Java view declaration: $name"
                if (error != null) errors[name] = error
                tree.members.filterIsInstance<ClassTree>().forEach { record(it, name, error) }
            }
            units.forEach { unit ->
                val diagnostic = fileErrors[unit.sourceFile.toUri()]?.firstOrNull()
                val error = diagnostic?.let { "Cannot parse Java source ${it.source.name}:${it.lineNumber}: ${it.getMessage(null)}" }
                val pkg = unit.packageName?.toString().orEmpty()
                val classes = unit.typeDecls.filterIsInstance<ClassTree>()
                classes.forEach { record(it, pkg, error) }
                // Javac may not recover a class tree from a broken class header.
                if (error != null && classes.none { it.simpleName.isNotEmpty() && it.simpleName.toString() != "<error>" }) {
                    val file = Path.of(unit.sourceFile.toUri())
                    val simpleName = file.fileName.toString().removeSuffix(".java")
                    errors[if (pkg.isEmpty()) simpleName else "$pkg.$simpleName"] = error
                    if (unit.packageName == null) roots.forEach { root ->
                        val absoluteRoot = root.toAbsolutePath().normalize()
                        if (file.startsWith(absoluteRoot)) {
                            val conventionalName = absoluteRoot.relativize(file).joinToString(".").removeSuffix(".java")
                            errors[conventionalName] = error
                        }
                    }
                }
            }
            val fatal = fileErrors[null]?.firstOrNull()
            require(fatal == null) { "Cannot parse Java view declarations: ${fatal?.getMessage(null)}" }
            Index(result, errors)
        }
    }

    fun parameters(name: String): Int? {
        val index = declarations
        index.errors[name]?.let { throw IllegalArgumentException(it) }
        return index.parameters[name]
    }
}
