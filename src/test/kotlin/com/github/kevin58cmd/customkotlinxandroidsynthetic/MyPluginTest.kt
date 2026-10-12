package com.github.kevin58cmd.customkotlinxandroidsynthetic

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.components.service
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import com.github.kevin58cmd.customkotlinxandroidsynthetic.services.SyntheticProjectService
import com.github.kevin58cmd.synthetic.GenerationSpec
import com.github.kevin58cmd.synthetic.Generator
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import org.junit.rules.TemporaryFolder

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {

    fun testXMLFile() {
        val psiFile = myFixture.configureByText(XmlFileType.INSTANCE, "<foo>bar</foo>")
        val xmlFile = assertInstanceOf(psiFile, XmlFile::class.java)

        assertFalse(PsiErrorElementUtil.hasErrors(project, xmlFile.virtualFile))

        assertNotNull(xmlFile.rootTag)

        xmlFile.rootTag?.let {
            assertEquals("foo", it.name)
            assertEquals("bar", it.value.text)
        }
    }

    fun testRename() {
        myFixture.testRename("foo.xml", "foo_after.xml", "a2")
    }

    fun testProjectService() {
        assertSame(project.service<SyntheticProjectService>(), project.service<SyntheticProjectService>())
    }

    fun testGeneratedFilesBecomeVisibleToVfs() {
        // The default light fixture uses temp:// URLs, not disk paths.
        val folder = TemporaryFolder()
        folder.create()
        try {
            val root = folder.root.toPath()
            val input = root.resolve("res/layout/screen.xml")
            Files.createDirectories(input.parent)
            Files.writeString(input, """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/label"/>""")
            val output = root.resolve("generated")
            val spec = GenerationSpec("sample", listOf(listOf(root.resolve("res"))), output)
            Generator.generate(spec)
            val generated = output.resolve("kotlinx/android/synthetic/main/screen/synthetic.kt")
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(generated)
            assertNotNull(file)
            assertTrue(String(file!!.contentsToByteArray()).contains("`label`"))
            Files.delete(input)
            Generator.generate(spec)
            LocalFileSystem.getInstance().refreshIoFiles(listOf(output.toFile()), false, true, null)
            assertFalse(file.isValid)
        } finally {
            folder.delete()
        }
    }

    override fun getTestDataPath() = "src/test/testData/rename"
}
