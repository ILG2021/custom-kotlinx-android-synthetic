package com.github.kevin58cmd.synthetic

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class GeneratorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root.toPath()
    private fun write(relative: String, content: String): Path = root.resolve(relative).also {
        Files.createDirectories(it.parent); Files.writeString(it, content)
    }
    private fun xml(body: String) = "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">$body</LinearLayout>"
    private fun spec(vararg layers: String) = GenerationSpec("sample", layers.map { listOf(root.resolve(it)) }, root.resolve("out"))
    private fun generated(layout: String = "screen") = Files.readString(root.resolve("out/kotlinx/android/synthetic/main/$layout/synthetic.kt"))

    @Test fun `layout container dependency enables extensions and removal reconciles output`() {
        write("res/layout/screen.xml", xml("""<TextView android:id="@+id/title"/>"""))
        val dependency = root.resolve("runtime.jar")
        java.util.zip.ZipOutputStream(Files.newOutputStream(dependency)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("kotlinx/android/extensions/LayoutContainer.class"))
            zip.write(byteArrayOf(0)) // Detection only; no class loading or signature parsing needed.
            zip.closeEntry()
        }
        Generator.generate(spec("res").copy(classpath = listOf(dependency), viewHolders = true))
        assertTrue(generated().contains("val kotlinx.android.extensions.LayoutContainer.`title`"))
        assertFalse(generated().contains("RecyclerView"))
        assertFalse(Files.readString(root.resolve("out/kotlinx/android/synthetic/main/screen/view.kt")).contains("LayoutContainer"))
        Generator.generate(spec("res"))
        assertFalse(generated().contains("LayoutContainer"))
    }

    @Test fun `overlay wins and qualifiers merge with a diagnostic`() {
        write("main/layout/screen.xml", xml("<TextView android:id=\"@+id/old\"/>"))
        write("debug/layout/screen.xml", xml("<TextView android:id=\"@+id/title\"/>"))
        write("main/layout-land/screen.xml", xml("<Button android:id=\"@+id/title\"/><View android:id=\"@+id/landOnly\"/>"))
        val warnings = Generator.generate(spec("main", "debug"))
        assertFalse(generated().contains("`old`"))
        assertTrue(generated().contains("`title`: android.view.View"))
        assertTrue(generated().contains("`landOnly`"))
        assertTrue(warnings.any { it.contains("differing types") })
    }

    @Test fun `qualifier only views remain in their own layout package`() {
        write("res/layout/screen.xml", xml("""<androidx.constraintlayout.widget.Barrier android:id="@+id/defaultBarrier"/>"""))
        write("res/layout-fr/screen.xml", xml("""<androidx.constraintlayout.widget.Barrier android:id="@+id/barrier"/><TextView android:id="@+id/optionalTitle"/>"""))
        write("res/layout/wrapper.xml", xml("""<include layout="@layout/screen"/>"""))
        val config = spec("res")
        Generator.generate(config)
        assertFalse(generated("wrapper").contains("`barrier`"))
        for (layout in listOf("screen")) {
            assertTrue(generated(layout).contains("`barrier`: androidx.constraintlayout.widget.Barrier?"))
            assertFalse(generated(layout).contains("R.id.`barrier`"))
            assertFalse(generated(layout).contains("Missing view 'barrier'"))
            assertTrue(generated(layout).contains("`defaultBarrier`: androidx.constraintlayout.widget.Barrier?"))
            assertTrue(generated(layout).contains("`optionalTitle`: android.widget.TextView?"))
            assertTrue(Files.readString(root.resolve("out/kotlinx/android/synthetic/main/$layout/view.kt")).contains("`barrier`: androidx.constraintlayout.widget.Barrier?"))
        }
        write("res/layout/screen.xml", xml("""<androidx.constraintlayout.widget.Barrier android:id="@+id/barrier"/>"""))
        Generator.generate(config)
        assertTrue(generated().contains("`barrier`: androidx.constraintlayout.widget.Barrier"))
        write("res/layout/screen.xml", xml(""))
        Generator.generate(config)
        assertTrue(generated().contains("`barrier`: androidx.constraintlayout.widget.Barrier?"))
        assertFalse(generated().contains("Missing view 'barrier'"))
        write("res/layout-fr/screen.xml", xml(""))
        Generator.generate(config)
        assertFalse(generated().contains("`barrier`"))
    }

    @Test fun `rename delete clean and unchanged runs reconcile outputs`() {
        val layout = write("res/layout/screen.xml", xml("<TextView android:id=\"@+id/title\"/>"))
        val config = spec("res")
        Generator.generate(config)
        val oldFile = root.resolve("out/kotlinx/android/synthetic/main/screen/synthetic.kt")
        val timestamp = Files.getLastModifiedTime(oldFile)
        Generator.generate(config)
        assertEquals(timestamp, Files.getLastModifiedTime(oldFile))
        Files.delete(oldFile)
        Generator.generate(config)
        assertTrue(Files.exists(oldFile))
        Files.move(layout, layout.resolveSibling("renamed.xml"))
        write("out/user.kt", "// user-owned")
        Generator.generate(config)
        assertFalse(Files.exists(oldFile))
        assertTrue(generated("renamed").contains("`title`"))
        Files.delete(layout.resolveSibling("renamed.xml"))
        Generator.generate(config)
        assertFalse(Files.exists(root.resolve("out/kotlinx/android/synthetic/main/renamed/view.kt")))
        assertTrue(Files.exists(root.resolve("out/user.kt")))
    }

    @Test fun `bad XML preserves last successful generation and rejects external entities`() {
        val file = write("res/layout/screen.xml", xml("<View android:id=\"@+id/title\"/>"))
        Generator.generate(spec("res"))
        val previous = generated()
        Files.writeString(file, "<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///does-not-exist'>]><foo>&x;</foo>")
        assertThrows(IllegalArgumentException::class.java) { Generator.generate(spec("res")) }
        assertEquals(previous, generated())
    }

    @Test fun `includes data binding namespace aliases framework IDs and generic overrides`() {
        write("res/layout/child.xml", """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/oldRoot"><TextView android:id="@+id/child"/></LinearLayout>""")
        write("res/layout/screen.xml", """
            <layout xmlns:a="http://schemas.android.com/apk/res/android"><data/>
            <LinearLayout><include layout="@layout/child" a:id="@+id/container"/>
            <view class="sample.GenericView" a:id="@+id/when"/>
            <TextView a:id="@android:id/text1"/></LinearLayout></layout>
        """.trimIndent())
        Generator.generate(spec("res").copy(typeOverrides = mapOf("sample.GenericView" to "sample.GenericView<*>")))
        assertFalse(generated().contains("`child`"))
        assertTrue(generated().contains("`container`: android.view.View"))
        assertFalse(generated().contains("`oldRoot`"))
        assertTrue(generated("child").contains("`oldRoot`"))
        assertTrue(generated().contains("`when`: sample.GenericView<*>"))
        assertTrue(generated().contains("android.R.id.`text1`"))
    }

    @Test fun `duplicate priority and unsupported fragments fail but includes are not traversed`() {
        write("a/layout/screen.xml", xml(""))
        write("b/layout/screen.xml", xml(""))
        assertThrows(IllegalArgumentException::class.java) {
            Generator.generate(spec("a").copy(resourceLayers = listOf(listOf(root.resolve("a"), root.resolve("b")))))
        }
        write("a/layout/screen.xml", xml("<include layout=\"@layout/screen\"/>"))
        Generator.generate(spec("a"))
        write("a/layout/screen.xml", xml("<include layout=\"@layout/dependency_layout\" android:id=\"@+id/container\"/>"))
        Generator.generate(spec("a"))
        assertTrue(generated().contains("`container`: android.view.View"))
        write("a/layout/screen.xml", xml("<fragment android:id=\"@+id/fragment\"/>"))
        assertThrows(IllegalArgumentException::class.java) { Generator.generate(spec("a")) }
    }

    @Test fun `IDE model round trips paths settings and remains stable`() {
        val config = spec("resources with spaces", "debug").copy(androidX = false, typeOverrides = mapOf("a.Generic" to "a.Generic<*>"), classpath = listOf(root.resolve("deps with spaces/views.jar")))
        val file = root.resolve("model.properties")
        IdeModel.write(file, config)
        val stamp = Files.getLastModifiedTime(file)
        assertEquals(config, IdeModel.read(file))
        IdeModel.write(file, config)
        assertEquals(stamp, Files.getLastModifiedTime(file))
    }

    @Test fun `IDE models reject incompatible versions before touching generated output`() {
        write("res/layout/screen.xml", xml("""<TextView android:id="@+id/title"/>"""))
        val config = spec("res")
        Generator.generate(config)
        val previous = generated()
        val model = root.resolve("model.properties")
        IdeModel.write(model, config)
        val text = Files.readString(model)
        assertTrue(text.lineSequence().any { it == "version=3" })
        for (version in listOf("1", "2", "4")) {
            Files.writeString(model, text.replace("version=3", "version=$version"))
            val error = assertThrows(IllegalArgumentException::class.java) {
                Generator.generate(IdeModel.read(model))
            }
            assertTrue(error.message.orEmpty().contains("generateSyntheticViews"))
            assertEquals(previous, generated())
        }
    }

    @Test fun `nested include descendants stay in their original package`() {
        write("res/layout/child.xml", """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/label"><TextView android:id="@+id/body"/></LinearLayout>""")
        write("res/layout-land/child.xml", xml("""<TextView android:id="@+id/label"/>"""))
        write("res/layout/screen.xml", xml("""<include layout="@layout/child" android:id="@+id/container"/>"""))
        write("res/layout/outer.xml", xml("""<include layout="@layout/screen" android:id="@+id/outerContainer"/>"""))
        Generator.generate(spec("res"))
        assertFalse(generated().contains("`label`"))
        assertFalse(generated().contains("`body`"))
        assertFalse(generated("outer").contains("`label`"))
        assertFalse(generated("outer").contains("`container`"))
        assertTrue(generated("outer").contains("`outerContainer`"))
        assertTrue(generated().contains("`container`"))
        assertTrue(generated("child").contains("`label`: android.view.View"))
    }

    @Test fun `merge includes do not export children or phantom container IDs`() {
        write("res/layout/child.xml", """<merge xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/ignoredRoot"><TextView android:id="@+id/label"/></merge>""")
        write("res/layout/screen.xml", xml("""<include layout="@layout/child" android:id="@+id/container"/>"""))
        write("res/layout/wrapper.xml", xml("""<include layout="@layout/child"/>"""))
        write("res/layout/outer.xml", xml("""<include layout="@layout/screen" android:id="@+id/outerContainer"/>"""))
        Generator.generate(spec("res"))
        for (name in listOf("screen", "wrapper", "outer")) {
            assertFalse(generated(name).contains("`label`"))
            assertFalse(generated(name).contains("`container`"))
            assertFalse(generated(name).contains("`ignoredRoot`"))
        }
        assertFalse(Files.readString(root.resolve("out/kotlinx/android/synthetic/main/screen/view.kt")).contains("`container`"))
        assertFalse(generated("child").contains("`ignoredRoot`"))
        // A real descendant remains available only through the child package.
        write("res/layout/child.xml", """<merge xmlns:android="http://schemas.android.com/apk/res/android"><TextView android:id="@+id/container"/></merge>""")
        Generator.generate(spec("res"))
        assertFalse(generated().contains("`container`"))
        assertTrue(generated("child").contains("`container`: android.widget.TextView"))
        assertFalse(generated().contains("`label`"))
    }

    @Test fun `mixed merge and normal roots retain only actual qualifier properties`() {
        write("res/layout/child.xml", """<layout xmlns:android="http://schemas.android.com/apk/res/android"><data/><merge><TextView android:id="@+id/label"/></merge></layout>""")
        write("res/layout-land/child.xml", """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/oldRoot"><TextView android:id="@+id/body"/></LinearLayout>""")
        write("res/layout/screen.xml", xml("""<include layout="@layout/child" android:id="@+id/container"/>"""))
        write("res/layout/unmodified.xml", xml("""<include layout="@layout/child"/>"""))
        Generator.generate(spec("res"))
        assertTrue(generated().contains("`container`: android.view.View"))
        assertFalse(generated().contains("`label`"))
        assertFalse(generated().contains("`body`"))
        assertFalse(generated().contains("`oldRoot`"))
        assertFalse(generated("unmodified").contains("`oldRoot`"))
    }

    @Test fun `generated Kotlin compiles and accesses current activity fragment and holder trees`() {
        val bannerXml = """<com.zhouwei.mzbanner.MZBannerView android:id="@+id/banner"/>"""
        write("res/layout/screen.xml", xml("""<TextView android:id="@+id/when"/><TextView android:id="@+id/foo"/><TextView android:id="@+id/Foo"/>""" + bannerXml))
        write("res/layout-fr/screen.xml", xml("""<TextView android:id="@+id/when"/><TextView android:id="@+id/foo"/><TextView android:id="@+id/Foo"/><TextView android:id="@+id/localizedOnly"/><androidx.constraintlayout.widget.Barrier android:id="@+id/barrier"/>""" + bannerXml))
        write("res/layout/activity_version_compare.xml", xml("""<include layout="@layout/layout_version_compare_bottom"/>"""))
        write("res/layout/layout_version_compare_bottom.xml", xml("""<TextView android:id="@+id/bottomBar"/>"""))
        write("app/java/com/zhouwei/mzbanner/MZBannerView.java", """
            package com.zhouwei.mzbanner;
            public class MZBannerView<T> extends android.widget.RelativeLayout {}
        """.trimIndent())
        write("app/java/kotlinx/android/extensions/LayoutContainer.java", """
            package kotlinx.android.extensions;
            public interface LayoutContainer { android.view.View getContainerView(); }
        """.trimIndent())
        val config = spec("res").copy(javaSourceRoots = listOf(root.resolve("app/java")))
        Generator.generate(config)
        // The IDE consumes the same exported dependency model as Gradle.
        val model = root.resolve("model.properties")
        IdeModel.write(model, config)
        Generator.generate(IdeModel.read(model))
        assertTrue(generated().contains("`banner`: com.zhouwei.mzbanner.MZBannerView<*>"))
        assertTrue(generated().contains("findViewById<com.zhouwei.mzbanner.MZBannerView<*>>"))
        assertTrue(generated().contains("`localizedOnly`: android.widget.TextView?"))
        assertFalse(generated().contains("R.id.`localizedOnly`"))
        assertFalse(generated().contains("R.id.`barrier`"))
        assertFalse(generated().contains("Missing view 'barrier'"))
        write("stubs/Resources.kt", """
            package android.content.res
            class Resources {
                var optionalId = 42
                fun getIdentifier(name: String, type: String, pkg: String): Int = if (pkg != "installed.app" || optionalId == 0) 0 else when (name) { "localizedOnly" -> optionalId; "barrier" -> 43; else -> 0 }
            }
        """.trimIndent())
        write("stubs/Context.kt", "package android.content; class Context { val packageName = \"installed.app\"; val resources = android.content.res.Resources() }")
        write("stubs/View.kt", """
            package android.view
            open class View {
                val children = mutableMapOf<Int, View>()
                val resources = android.content.res.Resources()
                val context = android.content.Context()
                @Suppress("UNCHECKED_CAST")
                fun <T : View> findViewById(id: Int): T? = children[id] as T?
            }
        """.trimIndent())
        write("stubs/TextView.kt", "package android.widget; class TextView : android.view.View()")
        write("stubs/Banner.kt", "package com.zhouwei.mzbanner; class MZBannerView<T : Any> : android.view.View()")
        write("stubs/Barrier.kt", "package androidx.constraintlayout.widget; class Barrier : android.view.View()")
        write("stubs/Activity.kt", "package android.app; open class Activity : android.view.View() { val packageName = \"installed.app\" }; open class Dialog : android.view.View()")
        write("stubs/Fragment.kt", """
            package androidx.fragment.app
            class Fragment {
                var view: android.view.View? = null
                fun requireView() = checkNotNull(view)
            }
        """.trimIndent())
        write("stubs/RecyclerView.kt", "package androidx.recyclerview.widget; class RecyclerView { open class ViewHolder(val itemView: android.view.View) }")
        write("stubs/LayoutContainer.kt", "package kotlinx.android.extensions; interface LayoutContainer { val containerView: android.view.View? }")
        write("stubs/R.kt", "package sample; object R { object id { const val `when` = 1; const val foo = 2; const val Foo = 3; const val banner = 4; const val bottomBar = 5 } }")
        write("stubs/Checks.kt", """
            package sample
            import kotlinx.android.synthetic.main.screen.*
            import kotlinx.android.synthetic.main.screen.view.*
            import kotlinx.android.synthetic.main.activity_version_compare.*
            import kotlinx.android.synthetic.main.layout_version_compare_bottom.*
            class Popup : kotlinx.android.extensions.LayoutContainer {
                override var containerView: android.view.View? = null
                fun directAccess() = `when`
            }
            class Holder(view: android.view.View) :
                androidx.recyclerview.widget.RecyclerView.ViewHolder(view),
                kotlinx.android.extensions.LayoutContainer {
                override var containerView: android.view.View? = view
                fun directAccess() = `when`
            }
            fun verify() {
                val first = android.widget.TextView()
                val second = android.widget.TextView()
                val activity = android.app.Activity()
                val banner = com.zhouwei.mzbanner.MZBannerView<String>()
                activity.children[4] = banner
                check(activity.banner === banner)
                check((activity as android.view.View).banner === banner)
                activity.children[1] = first
                activity.children[5] = first
                check(activity.bottomBar === first)
                check(activity.localizedOnly == null)
                check(activity.barrier == null)
                val barrier = androidx.constraintlayout.widget.Barrier()
                activity.children[43] = barrier
                check(activity.barrier === barrier)
                activity.children[42] = first
                check(activity.localizedOnly === first)
                activity.resources.optionalId = 0
                check(activity.localizedOnly == null)
                check(activity.barrier == null)
                activity.resources.optionalId = 42
                check(activity.`when` === first)
                activity.children[1] = second
                check(activity.`when` === second)
                val fragment = androidx.fragment.app.Fragment()
                fragment.view = android.view.View().apply { children[1] = first }
                check(fragment.`when` === first)
                fragment.view = null
                check(runCatching { fragment.`when` }.isFailure)
                fragment.view = android.view.View().apply { children[1] = second }
                check(fragment.`when` === second)
                check(fragment.requireView().`when` === second)
                val plainHolder = androidx.recyclerview.widget.RecyclerView.ViewHolder(fragment.requireView())
                check(plainHolder.itemView.`when` === second)
                val holder = Holder(fragment.requireView())
                check(holder.`when` === second)
                check(holder.directAccess() === second)
                holder.containerView = android.view.View().apply { children[1] = first }
                check(holder.directAccess() === first)
                holder.containerView = null
                check(holder.localizedOnly == null)
                check(runCatching { holder.directAccess() }.isFailure)
                holder.containerView = fragment.requireView()
                val popup = Popup()
                check(popup.localizedOnly == null)
                check(runCatching { popup.directAccess() }.isFailure)
                popup.containerView = android.view.View().apply {
                    children[1] = first
                    children[42] = second
                }
                check(popup.directAccess() === first)
                check(popup.localizedOnly === second)
                popup.containerView = android.view.View().apply { children[1] = second }
                check(popup.directAccess() === second)
                check(popup.localizedOnly == null)
                val dialog = android.app.Dialog().apply { children[1] = first }
                check(dialog.`when` === first)
                // Kotlin getter mangling would collapse foo/Foo without explicit JVM names.
                for (tree in listOf(activity, dialog, fragment.requireView())) {
                    tree.children[2] = first
                    tree.children[3] = second
                    check(tree.foo === first && tree.Foo === second)
                }
                check(activity.foo === first && activity.Foo === second)
                check(dialog.foo === first && dialog.Foo === second)
                check(fragment.foo === first && fragment.Foo === second)
                check(holder.foo === first && holder.Foo === second)
            }
        """.trimIndent())
        val sources = Files.walk(root).use { it.filter { path -> path.toString().endsWith(".kt") }.map(Path::toString).toList() }
        val classes = root.resolve("classes")
        val output = ByteArrayOutputStream()
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val result = K2JVMCompiler().exec(PrintStream(output), "-no-stdlib", "-no-reflect", "-classpath", stdlib,
            "-jvm-target", "17", "-d", classes.toString(), *sources.toTypedArray())
        assertEquals(output.toString(), ExitCode.OK, result)
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            loader.loadClass("sample.ChecksKt").getMethod("verify").invoke(null)
        }
    }
}
