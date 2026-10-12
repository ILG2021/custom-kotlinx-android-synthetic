package com.github.kevin58cmd.synthetic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Read declarations without loading or executing application classes. */
internal class ViewTypes(private val classpath: List<Path>, javaSourceRoots: List<Path> = emptyList()) {
    private val cache = mutableMapOf<String, String>()
    private val sources = JavaViewTypes(javaSourceRoots)

    fun contains(name: String): Boolean = sources.parameters(name) != null || findClass(name) != null

    fun resolve(name: String): String = cache.getOrPut(name) {
        sources.parameters(name)?.let { return@getOrPut projected(name, it) }
        val bytes = findClass(name) ?: return@getOrPut name
        var parameters = 0
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, internalName: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
                if (signature != null) SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
                    override fun visitFormalTypeParameter(name: String?) { parameters++ }
                })
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        projected(name, parameters)
    }

    private fun projected(name: String, parameters: Int) =
        name + if (parameters == 0) "" else List(parameters) { "*" }.joinToString(", ", "<", ">")

    private fun findClass(name: String): ByteArray? {
        val entry = name.replace('.', '/') + ".class"
        for (path in classpath) {
            if (Files.isDirectory(path)) {
                val file = path.resolve(entry)
                if (Files.isRegularFile(file)) return Files.readAllBytes(file)
            } else if (Files.isRegularFile(path) && path.fileName.toString().substringAfterLast('.') in setOf("jar", "aar", "zip")) {
                ZipFile(path.toFile()).use { zip ->
                    zip.getEntry(entry)?.let { return zip.getInputStream(it).use { stream -> stream.readAllBytes() } }
                    // Raw AARs also occur in exported dependency models.
                    if (path.fileName.toString().endsWith(".aar")) {
                        val jars = zip.entries().asSequence().filter { it.name == "classes.jar" || (it.name.startsWith("libs/") && it.name.endsWith(".jar")) }
                        for (jar in jars) ZipInputStream(zip.getInputStream(jar)).use { stream ->
                            while (true) {
                                val nested = stream.nextEntry ?: break
                                if (nested.name == entry) return stream.readAllBytes()
                            }
                        }
                    }
                }
            }
        }
        return null
    }
}
