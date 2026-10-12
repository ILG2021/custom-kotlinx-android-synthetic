package com.github.kevin58cmd.synthetic

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ViewTypesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun declaration(name: String, signature: String?): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, signature, "unavailable/BaseView", null)
        visitEnd()
    }.toByteArray()

    private fun archive(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun `generic arity comes from declarations in directories jars and aars without loading classes`() {
        val root = temporary.root.toPath()
        val single = declaration("sample/Single", "<T:Ljava/lang/Object;>Lunavailable/BaseView;")
        val pair = declaration("sample/Pair", "<T:Ljava/lang/Object;U:Ljava/lang/Number;>Lunavailable/BaseView;")
        val plain = declaration("sample/Plain", "Lsample/Single<Ljava/lang/String;>;")
        val directory = root.resolve("classes/sample")
        Files.createDirectories(directory)
        Files.write(directory.resolve("Single.class"), single)
        val jar = root.resolve("views.jar")
        Files.write(jar, archive(mapOf("sample/Pair.class" to pair, "sample/Plain.class" to plain)))
        val aar = root.resolve("views.aar")
        Files.write(aar, archive(mapOf("classes.jar" to archive(mapOf("sample/Single.class" to single)),
            "libs/extra.jar" to archive(mapOf("sample/Pair.class" to pair)))))
        val types = ViewTypes(listOf(root.resolve("classes"), jar))
        assertEquals("sample.Single<*>", types.resolve("sample.Single"))
        assertEquals("sample.Pair<*, *>", types.resolve("sample.Pair"))
        assertEquals("sample.Plain", types.resolve("sample.Plain"))
        assertEquals("sample.Unknown", types.resolve("sample.Unknown"))
        assertEquals("sample.Single<*>", ViewTypes(listOf(aar)).resolve("sample.Single"))
        assertEquals("sample.Pair<*, *>", ViewTypes(listOf(aar)).resolve("sample.Pair"))
        val layout = root.resolve("res/layout/screen.xml")
        Files.createDirectories(layout.parent)
        Files.writeString(layout, """<sample.Single xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/view"/>""")
        val spec = GenerationSpec("sample", listOf(listOf(root.resolve("res"))), root.resolve("out"),
            typeOverrides = mapOf("sample.Single" to "sample.Pair<*, *>"), classpath = listOf(root.resolve("classes")))
        Generator.generate(spec)
        assertTrue(Files.readString(root.resolve("out/kotlinx/android/synthetic/main/screen/synthetic.kt"))
            .contains("`view`: sample.Pair<*, *>"))
    }
}
