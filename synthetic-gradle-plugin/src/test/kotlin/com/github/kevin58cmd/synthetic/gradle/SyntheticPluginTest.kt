package com.github.kevin58cmd.synthetic.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** End-to-end AGP test: opt in with ANDROID_HOME pointing to an SDK with android-35 installed. */
class SyntheticPluginTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun write(path: String, content: String) = File(temporary.root, path).apply {
        parentFile.mkdirs(); writeText(content)
    }
    private fun run(vararg args: String) = GradleRunner.create().withProjectDir(temporary.root)
        .withArguments(*args, "--stacktrace", "--configuration-cache", "--max-workers=2",
            *(if (System.getenv("SYNTHETIC_TEST_OFFLINE") == "true") arrayOf("--offline") else emptyArray())).build()

    @Test fun `Android compilation clean regeneration overlays and configuration cache`() {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        assumeTrue("Set ANDROID_HOME to run the Android integration test", sdk != null && File(sdk, "platforms/android-35/android.jar").isFile)
        val pluginRoot = File(System.getProperty("synthetic.pluginRoot")).invariantSeparatorsPath
        val viewSource = write("dependency/sample/GenericView.java", """
            package sample;
            public class GenericView<T> extends android.widget.RelativeLayout {
                public GenericView(android.content.Context context) { super(context); }
            }
        """.trimIndent())
        val dependencyClasses = File(temporary.root, "dependency/classes").apply { mkdirs() }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-classpath", File(sdk, "platforms/android-35/android.jar").absolutePath,
            "-d", dependencyClasses.absolutePath, viewSource.absolutePath))
        val dependencyJar = File(temporary.root, "libs/views.jar").apply { parentFile.mkdirs() }
        java.util.jar.JarOutputStream(dependencyJar.outputStream()).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("sample/GenericView.class"))
            jar.write(File(dependencyClasses, "sample/GenericView.class").readBytes())
            jar.closeEntry()
        }
        write("settings.gradle", """
            pluginManagement {
                includeBuild('$pluginRoot')
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            rootProject.name = 'integration'
        """.trimIndent())
        write("gradle.properties", "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx1536m\nkotlin.daemon.jvmargs=-Xmx1024m\n")
        val buildScript = write("build.gradle", """
            plugins {
                id 'com.github.kevin58cmd.synthetic-compat'
                id 'com.android.library' version '8.7.2'
                id 'org.jetbrains.kotlin.android' version '2.1.20'
            }
            repositories { google(); mavenCentral() }
            dependencies { implementation files('libs/views.jar') }
            android {
                namespace 'sample'
                compileSdk 35
                defaultConfig { minSdk 23 }
                compileOptions {
                    sourceCompatibility JavaVersion.VERSION_17
                    targetCompatibility JavaVersion.VERSION_17
                }
                sourceSets.main.res.srcDirs = ['resources/base']
            }
            kotlin { jvmToolchain(17) }
            syntheticCompat { androidX.set(false); viewHolders.set(false) }
        """.trimIndent())
        write("src/main/AndroidManifest.xml", "<manifest/>")
        val sourceView = write("src/main/java/sample/SourceView.java", """
            package sample;
            public class SourceView<T> extends android.widget.RelativeLayout {
                public SourceView(android.content.Context context) { super(context); }
            }
        """.trimIndent())
        write("resources/base/layout/screen.xml", """<Button xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="wrap_content" android:layout_height="wrap_content" android:id="@+id/label"/>""")
        val overlay = write("src/debug/res/layout/screen.xml", """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="wrap_content" android:layout_height="wrap_content" android:id="@+id/label"/>""")
        write("resources/base/layout/proof.xml", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:id="@+id/foo" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <TextView android:id="@+id/Foo" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <sample.GenericView android:id="@+id/banner" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <sample.SourceView android:id="@+id/sourceBanner" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <include layout="@layout/included" android:id="@+id/container"/>
                <include layout="@layout/merged" android:id="@+id/phantom"/>
            </LinearLayout>
        """.trimIndent())
        write("resources/base/layout/included.xml", """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/includedLabel" android:layout_width="wrap_content" android:layout_height="wrap_content"/>""")
        write("resources/base/layout-land/included.xml", """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="wrap_content" android:layout_height="wrap_content"><TextView android:id="@+id/includedLabel" android:layout_width="wrap_content" android:layout_height="wrap_content"/></LinearLayout>""")
        write("resources/base/layout/merged.xml", """<merge xmlns:android="http://schemas.android.com/apk/res/android"><TextView android:id="@+id/mergedLabel" android:layout_width="wrap_content" android:layout_height="wrap_content"/></merge>""")
        write("src/main/kotlin/sample/Screen.kt", """
            package sample
            import kotlinx.android.synthetic.main.screen.*
            import kotlinx.android.synthetic.main.proof.*
            import kotlinx.android.synthetic.main.included.*
            import kotlinx.android.synthetic.main.merged.*
            class Screen : android.app.Activity() { fun update() { label.text = "compatible" } }
            class Proof : android.app.Activity() {
                fun update() {
                    setContentView(R.layout.proof)
                    foo.text = "lowercase"
                    Foo.text = "uppercase"
                    banner.visibility = android.view.View.VISIBLE
                    sourceBanner.visibility = android.view.View.VISIBLE
                    includedLabel.visibility = android.view.View.VISIBLE
                    mergedLabel.text = "merged descendant"
                }
            }
        """.trimIndent())
        val first = run("compileDebugKotlin")
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateDebugSyntheticViews")?.outcome)
        fun generated(layout: String = "screen") = File(temporary.root, "build/generated").walkTopDown().first {
            it.name == "synthetic.kt" && it.invariantSeparatorsPath.contains("/$layout/")
        }
        assertTrue(generated().readText().contains("`label`: android.widget.TextView"))
        assertFalse(generated("proof").readText().contains("`phantom`"))
        assertTrue(generated("proof").readText().contains("`banner`: sample.GenericView<*>"))
        assertTrue(generated("proof").readText().contains("`sourceBanner`: sample.SourceView<*>"))
        val second = run("compileDebugKotlin")
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateDebugSyntheticViews")?.outcome)
        assertTrue(second.output.contains("Reusing configuration cache"))
        // Identical dependency bytes at a new address must refresh the exported IDE model.
        val movedJar = File(temporary.root, "libs/relocated.jar")
        java.nio.file.Files.move(dependencyJar.toPath(), movedJar.toPath())
        buildScript.writeText(buildScript.readText().replace("libs/views.jar", "libs/relocated.jar"))
        val relocated = run("compileDebugKotlin")
        assertEquals(TaskOutcome.SUCCESS, relocated.task(":generateDebugSyntheticViews")?.outcome)
        val modelFile = File(temporary.root, ".gradle/synthetic-compat").listFiles()!!.single { it.name.endsWith(".properties") }
        val movedModel = com.github.kevin58cmd.synthetic.IdeModel.read(modelFile.toPath())
        assertTrue(movedModel.classpath.contains(movedJar.toPath().toAbsolutePath().normalize()))
        assertFalse(movedModel.classpath.contains(dependencyJar.toPath().toAbsolutePath().normalize()))
        com.github.kevin58cmd.synthetic.Generator.generate(movedModel)
        assertTrue(generated("proof").readText().contains("`banner`: sample.GenericView<*>"))
        val unchanged = run("compileDebugKotlin")
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":generateDebugSyntheticViews")?.outcome)
        assertTrue(unchanged.output.contains("Reusing configuration cache"))
        sourceView.writeText(sourceView.readText().replace("SourceView<T>", "SourceView<T, U>"))
        val sourceChanged = run("compileDebugKotlin")
        assertEquals(TaskOutcome.SUCCESS, sourceChanged.task(":generateDebugSyntheticViews")?.outcome)
        assertTrue(generated("proof").readText().contains("`sourceBanner`: sample.SourceView<*, *>"))
        val sourceModel = com.github.kevin58cmd.synthetic.IdeModel.read(modelFile.toPath())
        assertTrue(sourceModel.javaSourceRoots.contains(File(temporary.root, "src/main/java").toPath()))
        com.github.kevin58cmd.synthetic.Generator.generate(sourceModel)
        assertTrue(generated("proof").readText().contains("`sourceBanner`: sample.SourceView<*, *>"))
        run("clean", "compileDebugKotlin")
        assertTrue(generated().isFile)
        // Deleting a higher-priority layout must expose the lower-priority layout again.
        overlay.delete()
        run("generateDebugSyntheticViews")
        assertTrue(generated().readText().contains("`label`: android.widget.Button"))
        File(temporary.root, "resources/base/layout/screen.xml").delete()
        run("generateDebugSyntheticViews")
        assertFalse(File(temporary.root, "build/generated").walkTopDown().any { it.name == "synthetic.kt" && it.invariantSeparatorsPath.contains("/screen/") })
    }
}
