package com.github.kevin58cmd.synthetic

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class JavaViewTypesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `unrelated syntax errors do not prevent generation but referenced errors preserve output`() {
        val root = temporary.root.toPath()
        val sources = root.resolve("src")
        val layout = root.resolve("res/layout/screen.xml")
        Files.createDirectories(sources)
        Files.createDirectories(layout.parent)
        val banner = sources.resolve("Banner.java")
        Files.writeString(banner, "package sample; class Banner<T> {}")
        Files.writeString(sources.resolve("Unrelated.java"), "package sample; class Unrelated { void editing( }")
        Files.writeString(layout, """<sample.Banner xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/banner"/>""")
        val spec = GenerationSpec("sample", listOf(listOf(root.resolve("res"))), root.resolve("out"), javaSourceRoots = listOf(sources))
        Generator.generate(spec)
        val output = root.resolve("out/kotlinx/android/synthetic/main/screen/synthetic.kt")
        val previous = Files.readString(output)
        assertTrue(previous.contains("`banner`: sample.Banner<*>"))
        assertEquals("android.widget.TextView", ViewTypes(emptyList(), listOf(sources)).resolve("android.widget.TextView"))
        Files.writeString(banner, "package sample; class Banner<T { }")
        val error = assertThrows(IllegalArgumentException::class.java) { Generator.generate(spec) }
        assertTrue(error.message.orEmpty().contains("Banner.java"))
        assertEquals(previous, Files.readString(output))
        Files.writeString(banner, "package sample; class Banner<T, U> {}")
        Generator.generate(spec)
        assertTrue(Files.readString(output).contains("`banner`: sample.Banner<*, *>"))
    }

    @Test fun `Java declarations resolve without dependencies and follow edits`() {
        val root = temporary.root.toPath()
        val source = root.resolve("views.java")
        Files.writeString(source, """
            package custom.views;
            // class Fake<A, B> is only a comment.
            class Banner<T extends MissingType> extends MissingBase {
                String misleading = "class Fake<A, B> {}";
                static class Pair<A, B> {}
                void method() { class Local<X> {} }
            }
            class Plain extends Banner<String> {}
        """.trimIndent())
        val types = ViewTypes(emptyList(), listOf(root))
        assertEquals("custom.views.Banner<*>", types.resolve("custom.views.Banner"))
        assertEquals("custom.views.Banner.Pair<*, *>", types.resolve("custom.views.Banner.Pair"))
        assertEquals("custom.views.Plain", types.resolve("custom.views.Plain"))
        assertEquals("custom.views.Fake", types.resolve("custom.views.Fake"))
        assertEquals("custom.views.Banner.Local", types.resolve("custom.views.Banner.Local"))
        Files.writeString(source, "package custom.views; class Banner<T, U> {}")
        assertEquals("custom.views.Banner<*, *>", ViewTypes(emptyList(), listOf(root)).resolve("custom.views.Banner"))
    }
}
