package com.github.kevin58cmd.customkotlinxandroidsynthetic

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.SystemBackMigration
import com.github.kevin58cmd.customkotlinxandroidsynthetic.actions.MigrateSystemBackProjectAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.rules.TemporaryFolder
import java.io.File

class SystemBackMigrationTest : BasePlatformTestCase() {

    fun testDescendantWithoutBackOverrideAvoidsHandlerCollision() {
        val parent = myFixture.addFileToProject("Parent.java", java().text.replace("Screen", "Parent"))
        val childSource = "class Child extends Parent { private void syntheticCompatHandleSystemBack() {} }"
        val child = myFixture.addFileToProject("Child.java", childSource)
        val plans = SystemBackMigration.planFiles(listOf(parent, child))
        assertTrue(plans[parent]!!.after!!.contains("protected void syntheticCompatHandleSystemBack1()"))
        compileJava(plans[parent]!!.after!!.replace("Parent", "Screen"), extraSources = mapOf("Child" to childSource.replace("Parent", "Screen")))
    }

    fun testSkippedMultiClassChildBlocksParentExtraction() {
        val parent = myFixture.addFileToProject("Parent.java", java().text.replace("Screen", "Parent"))
        val child = myFixture.addFileToProject("Child.java", """
            class Child extends Parent { @Override public void onBackPressed() { finish(); } }
            class Other extends androidx.activity.ComponentActivity { @Override public void onBackPressed() { finish(); } }
        """.trimIndent())
        val plans = SystemBackMigration.planFiles(listOf(parent, child))
        assertNull(plans[child]!!.after)
        assertNull("Must not extract a parent when a child override is skipped", plans[parent]!!.after)
    }

    fun testKotlinDescendantWithoutOverrideAvoidsHandlerCollision() {
        val parent = myFixture.addFileToProject("Parent.kt", """
            open class Parent : androidx.activity.ComponentActivity() {
                override fun onBackPressed() { finish() }
            }
        """.trimIndent())
        val child = myFixture.addFileToProject("Screen.kt", """
            class Screen : Parent() { private fun syntheticCompatHandleSystemBack() {} }
        """.trimIndent())
        val plans = SystemBackMigration.planFiles(listOf(parent, child))
        val parentAfter = plans[parent]!!.after!!
        assertTrue(parentAfter.contains("protected open fun syntheticCompatHandleSystemBack1()"))
        compileKotlinWindow(parentAfter + "\n" + child.text, windowStubs(), """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.onCreate(null); screen.onBackPressed();
                if (screen.finishes != 1) throw new AssertionError("parent handler lost");
            } }
        """.trimIndent())
    }

    fun testSkippedKotlinMultiClassChildBlocksParentExtraction() {
        val parent = myFixture.addFileToProject("Parent.kt", """
            open class Parent : androidx.activity.ComponentActivity() {
                override fun onBackPressed() { finish() }
            }
        """.trimIndent())
        val child = myFixture.addFileToProject("Children.kt", """
            class Child : Parent() { override fun onBackPressed() { finish() } }
            class Other : androidx.activity.ComponentActivity() { override fun onBackPressed() { finish() } }
        """.trimIndent())
        val plans = SystemBackMigration.planFiles(listOf(parent, child))
        assertNull(plans[child]!!.after)
        assertNull(plans[parent]!!.after)
    }

    fun testSuperCallOutsideHandlerDoesNotDispatchChildAgain() {
        val parent = myFixture.addFileToProject("Parent.java", java("parents++; super.onBackPressed();", extra = "int parents;").text.replace("Screen", "Parent"))
        val child = myFixture.addFileToProject("Child.java", """
            class Child extends Parent {
                int children;
                @Override public void onBackPressed() { children++; super.onBackPressed(); }
                void parentOnlyBack() { super.onBackPressed(); }
                Runnable parentBack = super::onBackPressed;
            }
        """.trimIndent())
        val plans = SystemBackMigration.planFiles(listOf(parent, child))
        compileJava(plans[child]!!.after!!.replace("Child", "Screen"), """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.parentOnlyBack(); screen.onCreate(null);
                screen.parentOnlyBack(); screen.parentBack.run();
                if (screen.children != 0 || screen.parents != 3 || screen.finishes != 3)
                    throw new AssertionError("children=" + screen.children + " parents=" + screen.parents + " finishes=" + screen.finishes);
                if (!screen.getOnBackPressedDispatcher().hasEnabledCallbacks()) throw new AssertionError("guards not restored");
            } }
        """.trimIndent(), mapOf("Parent" to plans[parent]!!.after!!))
    }

    fun testKotlinSuperOutsideHandlerPreservesDefaultAndRestoresAfterException() {
        val parent = myFixture.addFileToProject("Parent.kt", """
            open class Parent : androidx.activity.ComponentActivity() {
                var parents = 0
                var fail = false
                override fun onBackPressed() { parents++; if (fail) error("back failed"); super.onBackPressed() }
            }
        """.trimIndent())
        val child = myFixture.addFileToProject("Screen.kt", """
            class Screen : Parent() {
                var children = 0
                override fun onBackPressed() { children++; super.onBackPressed() }
                fun parentOnlyBack() { super.onBackPressed() }
            }
        """.trimIndent())
        val plans = SystemBackMigration.planFiles(listOf(parent, child))
        compileKotlinWindow(plans[parent]!!.after!! + "\n" + plans[child]!!.after!!, windowStubs(), """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.parentOnlyBack(); screen.onCreate(null); screen.parentOnlyBack();
                if (screen.getChildren() != 0 || screen.getParents() != 2 || screen.finishes != 2) throw new AssertionError("super changed");
                screen.setFail(true);
                try { screen.parentOnlyBack(); throw new AssertionError("exception swallowed"); } catch (IllegalStateException expected) {}
                screen.setFail(false); screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.getChildren() != 1 || screen.getParents() != 4 || screen.finishes != 3) throw new AssertionError("guards not restored");
            } }
        """.trimIndent())
    }

    fun testSuperOutsideLeafHandlerBypassesNewCallback() {
        val after = migrated(kotlin("calls++", extra = "var calls = 0; fun defaultBack() { super.onBackPressed() }"))
        compileKotlinWindow(after, windowStubs(), """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.onCreate(null); screen.defaultBack();
                if (screen.getCalls() != 0 || screen.finishes != 1) throw new AssertionError("default entered callback");
            } }
        """.trimIndent())
    }

    fun testExtractedNestedLabelPreservesLocalReturn() {
        val stubs = windowStubs()
        val file = kotlin("""
            val action = onBackPressed@ { return@onBackPressed }
            action()
            calls++
        """.trimIndent(), extra = "var calls = 0")
        val after = migrated(file)
        assertTrue(after.contains("onBackPressed@ { return@onBackPressed }"))
        compileKotlinWindow(after, stubs, """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.onCreate(null); screen.onBackPressed();
                if (screen.getCalls() != 1) throw new AssertionError("local lambda return became function return");
            } }
        """.trimIndent())
    }

    fun testExtractedInheritedHelperNameDoesNotBreakJavaCompilation() {
        val middle = "class Middle extends androidx.activity.ComponentActivity { protected void syntheticCompatHandleSystemBack() {} protected void syntheticCompatHandleSystemBack1() {} }"
        addJavaClass(middle)
        val source = java("calls++;", extra = "int calls;").text.replace("extends androidx.activity.ComponentActivity", "extends Middle")
        val after = migrated(myFixture.configureByText("Screen.java", source))
        assertTrue(after.contains("private void syntheticCompatHandleSystemBack2()"))
        compileJava(after, extraSources = mapOf("Middle" to middle))
    }

    fun testExtractedInheritedHelperNameDoesNotBreakKotlinCompilation() {
        val stubs = windowStubs() + ("Middle" to "public class Middle extends androidx.activity.ComponentActivity { protected void syntheticCompatHandleSystemBack() {} }")
        addJavaClass(stubs.getValue("Middle"))
        val source = kotlin("calls++", extra = "var calls = 0").text.replace("androidx.activity.ComponentActivity()", "Middle()")
        val after = migrated(myFixture.configureByText("Screen.kt", source))
        assertTrue(after.contains("private fun syntheticCompatHandleSystemBack1()"))
        compileKotlinWindow(after, stubs, """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.onCreate(null); screen.onBackPressed();
                if (screen.getCalls() != 1) throw new AssertionError("inherited name collision lost handler");
            } }
        """.trimIndent())
    }

    fun testExtractedJavaMethodReferenceKeepsEarlyBusinessCall() {
        val after = migrated(java("calls++;", extra = """
            int calls;
            Runnable action = this::onBackPressed;
            java.util.function.Consumer<Screen> unbound = Screen::onBackPressed;
            Runnable fallback = super::onBackPressed;
            Runnable other = new androidx.activity.ComponentActivity()::onBackPressed;
        """.trimIndent()))
        assertTrue(after.contains("this::syntheticCompatHandleSystemBack"))
        assertTrue(after.contains("Screen::syntheticCompatHandleSystemBack"))
        assertTrue(after, after.contains("Screen.this::syntheticCompatCallSuperSystemBack"))
        assertTrue(after.contains("new androidx.activity.ComponentActivity()::onBackPressed"))
        compileJava(after, """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.action.run(); screen.unbound.accept(screen);
                if (screen.calls != 2 || screen.finishes != 0) throw new AssertionError("early method reference lost business handler");
                screen.fallback.run(); screen.other.run();
                if (screen.calls != 2 || screen.finishes != 1) throw new AssertionError("unrelated method reference was renamed");
            } }
        """.trimIndent())
    }

    fun testReviewLegacyKeyPathDoesNotExecuteBackBranchTwice() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen extends androidx.activity.ComponentActivity {
                int prompts;
                protected void onCreate(android.os.Bundle state) { super.onCreate(state); }
                public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) { prompts++; }
                    return false;
                }
            }
        """.trimIndent())
        compileJava(migrated(file), """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.onCreate(null);
                    screen.onKeyDown(4, new android.view.KeyEvent(0, 4));
                    screen.onBackPressed();
                    if (screen.prompts != 1) throw new AssertionError("legacy BACK branch executed " + screen.prompts + " times");
                }
            }
        """.trimIndent(), stubs)
    }

    fun testReviewExhaustiveBackBranchProducesCompilableHelper() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen extends androidx.activity.ComponentActivity {
                boolean consume;
                public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        if (consume) return true; else return false;
                    }
                    return false;
                }
            }
        """.trimIndent())
        compileJava(migrated(file), extraSources = stubs)
    }

    fun testReviewMixedActivityAndViewListenerPreservesActivityOverride() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen extends androidx.activity.ComponentActivity {
                int calls; android.view.View input = new android.view.View();
                protected void onCreate(android.os.Bundle state) {
                    super.onCreate(state);
                    input.setOnKeyListener((view, code, event) -> code == android.view.KeyEvent.KEYCODE_BACK);
                }
                @Override public void onBackPressed() { calls++; }
            }
        """.trimIndent())
        compileJava(migrated(file), """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.onCreate(null);
                    screen.getOnBackPressedDispatcher().onBackPressed();
                    if (screen.calls != 1 || screen.finishes != 0) throw new AssertionError("Activity override bypassed: calls=" + screen.calls + ", finishes=" + screen.finishes);
                }
            }
        """.trimIndent(), stubs)
    }

    fun testReviewBindingReplacedDuringBackDoesNotReactivateDisposedHandler() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen {
                android.view.View input = new android.view.View();
                int oldCalls, newCalls;
                void bind(boolean old) {
                    input.setOnKeyListener((view, code, event) -> {
                        if (code == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                            if (old) { oldCalls++; bind(false); } else newCalls++;
                            return true;
                        }
                        return false;
                    });
                }
            }
        """.trimIndent())
        compileJava(migrated(file), """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.input.attach(); screen.bind(true);
                    screen.input.dispatcher.callback.onBackInvoked();
                    screen.input.dispatcher.callback.onBackInvoked();
                    if (screen.oldCalls != 1 || screen.newCalls != 1) throw new AssertionError("disposed handler restored: old=" + screen.oldCalls + ", new=" + screen.newCalls);
                }
            }
        """.trimIndent(), stubs)
    }
    fun testKotlinReentrantRebindDoesNotReviveOldCallback() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.kt", """
            class Screen {
                val input = android.view.View()
                var oldCalls = 0
                var newCalls = 0
                fun bind(old: Boolean) {
                    input.setOnKeyListener { _, code, event ->
                        if (code == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN) {
                            if (old) { oldCalls++; bind(false) } else newCalls++
                            true
                        } else false
                    }
                }
            }
        """.trimIndent())
        compileKotlinWindow(migrated(file), stubs, """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.getInput().attach(); screen.bind(true);
                    screen.getInput().dispatcher.callback.onBackInvoked();
                    screen.getInput().dispatcher.callback.onBackInvoked();
                    if (screen.getOldCalls() != 1 || screen.getNewCalls() != 1) throw new AssertionError("disposed Kotlin binding revived");
                }
            }
        """.trimIndent())
    }

    fun testKotlinMixedActivityAndListenerKeepsActivityOverride() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.kt", """
            class Screen : androidx.activity.ComponentActivity() {
                var calls = 0
                val input = android.view.View()
                override fun onCreate(state: android.os.Bundle?) {
                    super.onCreate(state)
                    input.setOnKeyListener { _, code, _ -> code == android.view.KeyEvent.KEYCODE_BACK }
                }
                override fun onBackPressed() { calls++ }
            }
        """.trimIndent())
        compileKotlinWindow(migrated(file), stubs, """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.onCreate(null);
                    screen.getOnBackPressedDispatcher().onBackPressed();
                    if (screen.getCalls() != 1 || screen.finishes != 0) throw new AssertionError("Kotlin Activity override bypassed");
                }
            }
        """.trimIndent())
    }

    private fun oldWindowTemplate(source: String): String = source.lineSequence().filterNot {
        it.trim() in setOf("private boolean disposed;", "private var disposed = false", "if (disposed) return;", "if (disposed) return")
    }.joinToString("\n").replace("disposed || ", "").replace("if (!disposed) handled |=", "handled |=")
        .replace("disposed = true; ", "").replace("// SyntheticCompat window back bridge v2", "// SyntheticCompat window back bridge")

    fun testUpgradesOldJavaWindowTemplateAndRejectsEditedHelper() {
        windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen {
                void bind(android.view.View view) {
                    view.setOnKeyListener((v, code, event) -> code == android.view.KeyEvent.KEYCODE_BACK);
                }
            }
        """.trimIndent())
        val old = oldWindowTemplate(migrated(file))
        val upgraded = migrated(myFixture.configureByText("Screen.java", old))
        assertTrue(upgraded.contains("private boolean disposed;"))
        compileJava(upgraded, extraSources = windowStubs())
        val edited = old.replace("void dispose() {", "void dispose() { System.out.println(\"custom\");")
        assertNull(SystemBackMigration.plan(myFixture.configureByText("Screen.java", edited))!!.after)
    }

    fun testUpgradesOldKotlinWindowTemplate() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.kt", """
            class Screen {
                fun bind(view: android.view.View) {
                    view.setOnKeyListener { _, code, _ -> code == android.view.KeyEvent.KEYCODE_BACK }
                }
            }
        """.trimIndent())
        val old = oldWindowTemplate(migrated(file))
        val upgraded = migrated(myFixture.configureByText("Screen.kt", old))
        assertTrue(upgraded.contains("private var disposed = false"))
        compileKotlinWindow(upgraded, stubs, """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); android.view.View view = new android.view.View();
                view.attach(); screen.bind(view); view.dispatcher.callback.onBackInvoked();
                if (view.fallbacks != 0) throw new AssertionError("upgraded listener lost");
            } }
        """.trimIndent())
    }

    fun testOldWindowUpgradeAlsoMigratesPreviouslyMissedActivity() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen {
                void bind(android.view.View view) {
                    view.setOnKeyListener((v, code, event) -> code == android.view.KeyEvent.KEYCODE_BACK);
                }
            }
        """.trimIndent())
        val old = oldWindowTemplate(migrated(file)).replace("class Screen {", """
            class Screen extends androidx.activity.ComponentActivity {
                int calls;
                protected void onCreate(android.os.Bundle state) { super.onCreate(state); }
                public void onBackPressed() { calls++; }
        """.trimIndent())
        compileJava(migrated(myFixture.configureByText("Screen.java", old)), """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.onCreate(null);
                screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.calls != 1 || screen.finishes != 0) throw new AssertionError("old mixed Activity skipped again");
            } }
        """.trimIndent(), stubs)
    }

    fun testUpgradesOldActivityKeyTemplateAndUnreachableTail() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen extends androidx.activity.ComponentActivity {
                int calls; boolean consume;
                public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        calls++; if (consume) return true; else return false;
                    }
                    return false;
                }
            }
        """.trimIndent())
        val current = migrated(file)
        val call = "syntheticCompatHandleBackKey(android.view.KeyEvent.KEYCODE_BACK, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))"
        val legacy = current.replace("if (Screen.this.$call) return;", "")
            .replace("super.onBackPressed();", "if ($call) return; super.onBackPressed();")
            .replace("// SyntheticCompat Activity key bridge v2", "// SyntheticCompat Activity key bridge")
        val marker = "// SyntheticCompat Activity key bridge"
        val old = legacy.substringBefore(marker) + marker + legacy.substringAfter(marker)
            .replace("calls++; if (consume) return true; else return false;\n", "calls++; if (consume) return true; else return false;\nreturn false;\n")
        val upgraded = migrated(myFixture.configureByText("Screen.java", old))
        compileJava(upgraded, """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.onCreate(null);
                screen.onKeyDown(4, new android.view.KeyEvent(0, 4)); screen.onBackPressed();
                if (screen.calls != 1) throw new AssertionError("legacy branch duplicated after upgrade");
                screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.calls != 2) throw new AssertionError("modern branch missing after upgrade");
            } }
        """.trimIndent(), stubs)
    }

    override fun setUp() {
        super.setUp()
        addJavaClass("package android.os; public class Bundle {}")
        addJavaClass("package android.view; public class KeyEvent { public static final int KEYCODE_BACK = 4; }")
        addJavaClass("""
            package android.app;
            public class Activity {
                protected void onCreate(android.os.Bundle state) {}
                public int finishes;
                public void onBackPressed() { finish(); }
                public void finish() { finishes++; }
            }
        """.trimIndent())
        addJavaClass("""
            package androidx.activity;
            public abstract class OnBackPressedCallback {
                private boolean enabled;
                public OnBackPressedCallback(boolean enabled) { this.enabled = enabled; }
                public boolean isEnabled() { return enabled; }
                public void setEnabled(boolean enabled) { this.enabled = enabled; }
                public abstract void handleOnBackPressed();
            }
        """.trimIndent())
        addJavaClass("""
            package androidx.activity;
            public class OnBackPressedDispatcher {
                private final Runnable fallback;
                private final java.util.List<OnBackPressedCallback> callbacks = new java.util.ArrayList<>();
                public OnBackPressedDispatcher(Runnable fallback) { this.fallback = fallback; }
                public void addCallback(ComponentActivity owner, OnBackPressedCallback callback) { callbacks.add(callback); }
                public boolean hasEnabledCallbacks() {
                    for (OnBackPressedCallback callback : callbacks) if (callback.isEnabled()) return true;
                    return false;
                }
                public void onBackPressed() {
                    for (int i = callbacks.size() - 1; i >= 0; i--) {
                        OnBackPressedCallback callback = callbacks.get(i);
                        if (callback.isEnabled()) { callback.handleOnBackPressed(); return; }
                    }
                    fallback.run();
                }
            }
        """.trimIndent())
        addJavaClass("""
            package androidx.activity;
            public class ComponentActivity extends android.app.Activity {
                private final OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> super.onBackPressed());
                public OnBackPressedDispatcher getOnBackPressedDispatcher() { return dispatcher; }
                @Override public void onBackPressed() { dispatcher.onBackPressed(); }
            }
        """.trimIndent())
    }

    private fun addJavaClass(source: String) {
        val packageName = Regex("package\\s+([\\w.]+)").find(source)?.groupValues?.get(1).orEmpty()
        val name = Regex("class\\s+(\\w+)").find(source)!!.groupValues[1]
        putSource((if (packageName.isEmpty()) "" else packageName.replace('.', '/') + "/") + name + ".java", source)
    }

    private fun putSource(path: String, source: String) {
        val existing = myFixture.findFileInTempDir(path)
        if (existing == null) myFixture.addFileToProject(path, source)
        else WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(existing)!!
            document.setText(source)
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun java(body: String = "this.finish();", create: String = "super.onCreate(state);", extra: String = ""): PsiFile =
        myFixture.configureByText("Screen.java", """
            class Screen extends androidx.activity.ComponentActivity {
                @Override protected void onCreate(android.os.Bundle state) { $create }
                @Override public void onBackPressed() { $body }
                $extra
            }
        """.trimIndent())

    private fun kotlin(body: String = "this.finish()", create: String = "super.onCreate(state)", extra: String = ""): PsiFile =
        myFixture.configureByText("Screen.kt", """
            class Screen : androidx.activity.ComponentActivity() {
                override fun onCreate(state: android.os.Bundle?) { $create }
                override fun onBackPressed() { $body }
                $extra
            }
        """.trimIndent())

    private fun migrated(file: PsiFile): String {
        val result = SystemBackMigration.plan(file)!!
        assertNotNull(result.reason, result.after)
        val after = result.after!!
        val parsed = myFixture.configureByText(file.name, after)
        assertNull(PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java))
        assertNull("A second migration must not add another callback", SystemBackMigration.plan(parsed))
        return after
    }

    fun testJavaPreservesOriginalReceiverAndDirectCallers() {
        val original = java("this.finish(); super.finish();", extra = "void toolbarBack() { this.onBackPressed(); }")
        val after = migrated(original)
        assertTrue(after.contains("private void syntheticCompatHandleSystemBack() { this.finish(); super.finish(); }"))
        assertTrue(after.contains("void toolbarBack() { this.syntheticCompatHandleSystemBack(); }"))
        assertTrue(after.contains("Screen.this.syntheticCompatHandleSystemBack();"))
    }

    fun testGeneratedJavaCompilesWithOriginalReceiverAndEarlyReturn() {
        val after = migrated(java("this.finish(); super.finish();", "super.onCreate(state); return;", "void toolbarBack() { onBackPressed(); }"))
        compileJava(after)
    }

    private fun compileJava(after: String, main: String? = null, extraSources: Map<String, String> = emptyMap()) {
        val names = listOf("android.os.Bundle", "android.app.Activity", "androidx.activity.ComponentActivity",
            "androidx.activity.OnBackPressedCallback", "androidx.activity.OnBackPressedDispatcher")
        val facade = JavaPsiFacade.getInstance(project)
        val code = names.associateWith { facade.findClass(it, GlobalSearchScope.allScope(project))!!.containingFile.text } + ("Screen" to after) + extraSources +
            (if (main == null) emptyMap() else mapOf("Main" to main))
        val folder = TemporaryFolder()
        folder.create()
        try {
            val sources = code.map { (name, text) ->
                File(folder.root, name.replace('.', '/') + ".java").apply {
                    parentFile.mkdirs()
                    writeText(text)
                }.absolutePath
            }
            val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
            val compiler = File(javaHome, "bin/javac$suffix")
            assertTrue("JDK compiler is missing: $compiler", compiler.isFile)
            val process = ProcessBuilder(listOf(compiler.absolutePath, "-proc:none", "-d", folder.root.path) + sources)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            if (main != null) {
                val runtime = File(javaHome, "bin/java$suffix")
                val run = ProcessBuilder(runtime.absolutePath, "-cp", folder.root.path, "Main").redirectErrorStream(true).start()
                val runtimeOutput = run.inputStream.bufferedReader().readText()
                assertEquals(runtimeOutput, 0, run.waitFor())
            }
        } finally {
            folder.delete()
        }
    }

    fun testKotlinPreservesOriginalReceiverAndDirectCallers() {
        val after = migrated(kotlin("this.finish(); super.finish()", extra = "fun toolbarBack() { onBackPressed() }"))
        assertTrue(after.contains("private fun syntheticCompatHandleSystemBack() { this.finish(); super.finish() }"))
        assertTrue(after.contains("fun toolbarBack() { syntheticCompatHandleSystemBack() }"))
        assertTrue(after.contains("this@Screen.syntheticCompatHandleSystemBack()"))
    }

    fun testExtractedJavaHandlerRunsForLegacyModernAndEarlyDirectEntry() {
        val after = migrated(java("calls++; if (allow) super.onBackPressed();", extra = "int calls; boolean allow; void toolbarBack() { this.onBackPressed(); }"))
        assertFalse(after.contains("public void onBackPressed()"))
        compileJava(after, """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.toolbarBack();
                screen.onCreate(null); screen.onBackPressed();
                screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.calls != 3 || screen.finishes != 0) throw new AssertionError("entry lost or duplicated");
                screen.allow = true; screen.toolbarBack(); screen.onBackPressed();
                screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.calls != 6 || screen.finishes != 3) throw new AssertionError("default return duplicated");
            } }
        """.trimIndent())
    }

    fun testExtractedKotlinHandlerAvoidsCollisionAndPreservesNamedReturn() {
        val stubs = windowStubs()
        val file = kotlin("calls++; if (!allow) return@onBackPressed; super.onBackPressed()",
            extra = "var calls = 0; var allow = false; private fun syntheticCompatHandleSystemBack() {}; fun toolbarBack() { onBackPressed() }")
        val after = migrated(file)
        assertFalse(after.contains("override fun onBackPressed()"))
        assertTrue(after, after.contains("return@syntheticCompatHandleSystemBack1"))
        compileKotlinWindow(after, stubs, """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.toolbarBack(); screen.onCreate(null);
                screen.onBackPressed(); screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.getCalls() != 3 || screen.finishes != 0) throw new AssertionError("Kotlin entry lost or duplicated");
                screen.setAllow(true); screen.toolbarBack(); screen.onBackPressed();
                screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.getCalls() != 6 || screen.finishes != 3) throw new AssertionError("Kotlin fallback duplicated");
            } }
        """.trimIndent())
    }

    fun testGuarded026BridgeUpgradesToIndependentHandler() {
        val original = java("calls++; if (allow) super.onBackPressed();", extra = "int calls; boolean allow;")
        val old = SystemBackMigration.planLegacy(original)!!.after!!
        val after = migrated(myFixture.configureByText("Screen.java", old))
        assertFalse(after.contains("public void onBackPressed()"))
        assertEquals(1, Regex("addCallback\\(").findAll(after).count())
        compileJava(after, """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.allow = true; screen.onCreate(null);
                screen.onBackPressed(); screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.calls != 2 || screen.finishes != 2) throw new AssertionError("upgraded default handler duplicated");
            } }
        """.trimIndent())
    }

    fun testEditedGuardedCallbackIsNotExtracted() {
        val old = SystemBackMigration.planLegacy(java("super.onBackPressed();"))!!.after!!
            .replace("setEnabled(true);", "setEnabled(true); finish();")
        val result = SystemBackMigration.plan(myFixture.configureByText("Screen.java", old))!!
        assertNull(result.after)
        assertTrue(result.reason.contains("已被编辑"))
    }

    fun testKotlinPreservesMultilineLiteralExactly() {
        val quote = "\"\"\""
        val body = "val message = $quote\n  first\n\n    second  \n$quote\nfinish()"
        val after = migrated(kotlin(body))
        assertTrue(after.contains(body))
    }

    fun testRegistersBeforeJavaEarlyReturn() {
        val after = migrated(java(create = "super.onCreate(state); return;"))
        assertTrue(after.indexOf("addCallback") < after.indexOf("return;"))
    }

    fun testRegistersBeforeKotlinEarlyReturn() {
        val after = migrated(kotlin(create = "super.onCreate(state); return"))
        assertTrue(after.indexOf("addCallback") < after.indexOf("; return"))
    }

    fun testRejectsEarlyReturnBeforeSuper() {
        assertNull(SystemBackMigration.plan(java(create = "if (state == null) return; super.onCreate(state);"))!!.after)
        assertNull(SystemBackMigration.plan(kotlin(create = "if (state == null) return; super.onCreate(state)"))!!.after)
    }

    fun testRejectsRecursiveBackApis() {
        migrated(java("super.onBackPressed();"))
        migrated(kotlin("super.onBackPressed()"))
        assertNull(SystemBackMigration.plan(java("getOnBackPressedDispatcher().onBackPressed();"))!!.after)
        assertNull(SystemBackMigration.plan(kotlin("onBackPressedDispatcher.onBackPressed()"))!!.after)
    }

    fun testCommentsAndLiteralsDoNotTriggerKeyDetection() {
        migrated(java(extra = "// KEYCODE_BACK\nString text = \"KEYCODE_BACK\";"))
        migrated(kotlin(extra = "// KEYCODE_BACK\nval text = \"KEYCODE_BACK\""))
    }

    fun testRealKeyReferencesAreReported() {
        val down = java(extra = "boolean onKeyDown(int code, android.view.KeyEvent event) { return code == android.view.KeyEvent.KEYCODE_BACK; }")
        assertNull(SystemBackMigration.plan(down)!!.after)
        val up = kotlin(extra = "fun onKeyUp(code: Int): Boolean = code == android.view.KeyEvent.KEYCODE_BACK")
        assertNull(SystemBackMigration.plan(up)!!.after)
    }

    fun testSuperclassTextInCommentDoesNotPassTypeCheck() {
        val fake = myFixture.configureByText("Fake.java", """
            class Fake {
                // AppCompatActivity ComponentActivity
                void onBackPressed() {}
                void onCreate(android.os.Bundle state) {}
            }
        """.trimIndent())
        assertNull(SystemBackMigration.plan(fake)!!.after)
    }

    fun testIndirectInheritanceResolves() {
        addJavaClass("class Middle extends androidx.activity.ComponentActivity {}")
        val source = java().text.replace("extends androidx.activity.ComponentActivity", "extends Middle")
        migrated(myFixture.configureByText("Screen.java", source))
    }

    fun testActivityWithSubclassWithoutOverrideIsExtracted() {
        val parent = myFixture.addFileToProject("Parent.java", java("calls++; super.onBackPressed();", extra = "int calls;").text.replace("Screen", "Parent"))
        addJavaClass("class Child extends Parent {}")
        val after = migrated(parent).replace("Parent", "Screen")
        assertFalse(after.contains("public void onBackPressed()"))
        assertTrue(after.contains("protected void syntheticCompatHandleSystemBack()"))
        compileJava(after, """
            public class Main {
                public static void main(String[] args) {
                    Child child = new Child(); child.onCreate(null);
                    child.getOnBackPressedDispatcher().onBackPressed();
                    child.onBackPressed();
                    if (child.calls != 2 || child.finishes != 2) throw new AssertionError("virtual dispatch duplicated");
                }
            }
        """.trimIndent(), mapOf("Child" to "class Child extends Screen {}"))
    }

    fun testInitializationBeforeSuperOnCreateIsPreserved() {
        val after = migrated(java(create = "finishes = 0; super.onCreate(state);"))
        assertTrue(after.indexOf("finishes = 0;") < after.indexOf("super.onCreate(state);"))
        assertTrue(after.indexOf("addCallback") > after.indexOf("super.onCreate(state);"))
        compileJava(after)
        val kt = migrated(kotlin(create = "val mode = 1; super.onCreate(state)"))
        assertTrue(kt.indexOf("val mode = 1") < kt.indexOf("super.onCreate(state)"))
    }

    fun testBothParentAndChildCallbacksDoNotDuplicateSuperBack() {
        val source = java("parents++; super.onBackPressed();", extra = "int parents;").text.replace("Screen", "Parent")
        val parent = myFixture.addFileToProject("Parent.java", source)
        val child = myFixture.addFileToProject("Child.java", """
            class Child extends Parent {
                int children;
                @Override protected void onCreate(android.os.Bundle state) { super.onCreate(state); }
                @Override public void onBackPressed() { children++; super.onBackPressed(); }
            }
        """.trimIndent())
        val plans = SystemBackMigration.planFiles(listOf(child, parent))
        val parentAfter = plans[parent]!!.after!!
        val childAfter = plans[child]!!.after!!.replace("Child", "Screen")
        assertFalse(parentAfter.contains("public void onBackPressed()"))
        assertFalse(childAfter.contains("public void onBackPressed()"))
        assertTrue(childAfter.contains("super.syntheticCompatHandleSystemBack()"))
        compileJava(childAfter, """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.onCreate(null);
                    screen.getOnBackPressedDispatcher().onBackPressed();
                    screen.onBackPressed();
                    if (screen.parents != 2 || screen.children != 2 || screen.finishes != 2) throw new AssertionError("inheritance callback duplicated");
                    if (!screen.getOnBackPressedDispatcher().hasEnabledCallbacks()) throw new AssertionError("guards not restored");
                }
            }
        """.trimIndent(), mapOf("Parent" to parentAfter))
    }

    fun testKotlinHierarchyPreservesDirectCallsBeforeCreateAndConditionalSuper() {
        val parent = myFixture.addFileToProject("Parent.kt", """
            open class Parent : androidx.activity.ComponentActivity() {
                var parents = 0
                override fun onBackPressed() { parents++; super.onBackPressed() }
                fun parentToolbarBack() { onBackPressed() }
            }
        """.trimIndent())
        val child = myFixture.addFileToProject("Screen.kt", """
            class Screen : Parent() {
                var children = 0
                var allow = false
                override fun onBackPressed() { children++; if (allow) super.onBackPressed() }
                fun toolbarBack() { onBackPressed() }
            }
        """.trimIndent())
        val plans = SystemBackMigration.planFiles(listOf(child, parent))
        val parentAfter = plans[parent]!!.after!!
        val childAfter = plans[child]!!.after!!
        assertFalse(parentAfter.contains("override fun onBackPressed()"))
        assertFalse(childAfter.contains("override fun onBackPressed()"))
        assertTrue(childAfter.contains("super.syntheticCompatHandleSystemBack()"))
        compileKotlinWindow(parentAfter + "\n" + childAfter, windowStubs(), """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen();
                screen.parentToolbarBack();
                if (screen.getChildren() != 1 || screen.getParents() != 0 || screen.finishes != 0) throw new AssertionError("blocked back changed");
                screen.setAllow(true); screen.toolbarBack();
                if (screen.getChildren() != 2 || screen.getParents() != 1 || screen.finishes != 1) throw new AssertionError("pre-create super lost");
                screen.onCreate(null); screen.onBackPressed(); screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.getChildren() != 4 || screen.getParents() != 3 || screen.finishes != 3) throw new AssertionError("hierarchy duplicated");
                if (!screen.getOnBackPressedDispatcher().hasEnabledCallbacks()) throw new AssertionError("callbacks not restored");
            } }
        """.trimIndent())
    }

    fun testMixedHierarchyWithAlreadyExtractedJavaParent() {
        val parent = myFixture.addFileToProject("Parent.java", java("parents++; super.onBackPressed();", extra = "public int parents;").text.replace("Screen", "Parent").replace("class Parent", "public class Parent"))
        val child = myFixture.addFileToProject("Screen.kt", """
            class Screen : Parent() {
                var children = 0
                override fun onBackPressed() { children++; super.onBackPressed() }
            }
        """.trimIndent())
        val parentAfter = SystemBackMigration.planFiles(listOf(parent, child))[parent]!!.after!!
        putSource("Parent.java", parentAfter)
        val childAfter = migrated(child)
        assertTrue(childAfter.contains("protected override fun syntheticCompatHandleSystemBack()"))
        compileKotlinWindow(childAfter, windowStubs() + ("Parent" to parentAfter), """
            public class Main { public static void main(String[] args) {
                Screen screen = new Screen(); screen.onCreate(null);
                screen.onBackPressed(); screen.getOnBackPressedDispatcher().onBackPressed();
                if (screen.parents != 2 || screen.getChildren() != 2 || screen.finishes != 2) throw new AssertionError("mixed hierarchy duplicated");
            } }
        """.trimIndent())
    }

    fun testHierarchyWithEditedCallbackIsNotPartiallyExtracted() {
        val parent = myFixture.addFileToProject("Parent.java", java().text.replace("Screen", "Parent"))
        val source = "class Child extends Parent { @Override public void onBackPressed() { super.onBackPressed(); } }"
        val child = myFixture.addFileToProject("Child.java", source)
        val old = SystemBackMigration.planLegacy(child)!!.after!!.replace("setEnabled(true);", "setEnabled(true); finish();")
        putSource("Child.java", old)
        val plans = SystemBackMigration.planFiles(listOf(parent, child))
        assertNull(plans[parent]!!.after)
        assertNull(plans[child]!!.after)
    }

    fun testSingleFileScanIncludesHierarchyAndKeepsOneCollisionFreeName() {
        val parent = myFixture.addFileToProject("Parent.java", java("super.onBackPressed();").text.replace("Screen", "Parent"))
        val child = myFixture.addFileToProject("Child.java", """
            class Child extends Parent {
                private void syntheticCompatHandleSystemBack() {}
                @Override public void onBackPressed() { super.onBackPressed(); }
            }
        """.trimIndent())
        myFixture.addFileToProject("Other.java", java().text.replace("Screen", "Other"))
        val entries = MigrateSystemBackProjectAction.scan(project, selectedFile = child.virtualFile)
        assertEquals(setOf(parent.virtualFile, child.virtualFile), entries.map { it.file }.toSet())
        val parentAfter = entries.single { it.file == parent.virtualFile }.result.after!!
        val childAfter = entries.single { it.file == child.virtualFile }.result.after!!
        assertTrue(parentAfter.contains("protected void syntheticCompatHandleSystemBack1()"))
        assertTrue(childAfter.contains("super.syntheticCompatHandleSystemBack1()"))
        compileJava(childAfter.replace("Child", "Screen"), extraSources = mapOf("Parent" to parentAfter))
    }

    fun testLibraryImplementationIsNotScannedAsApplicationHelper() {
        addJavaClass("""
            package androidx.example;
            public class LibraryWidget {
                public static boolean shown() { hiddenBackInfrastructure(); return true; }
                private static void hiddenBackInfrastructure() { new androidx.activity.ComponentActivity().onBackPressed(); }
            }
        """.trimIndent())
        val after = migrated(java("if (androidx.example.LibraryWidget.shown()) { finish(); return; } super.onBackPressed();"))
        assertTrue(after.contains("LibraryWidget.shown()"))
        assertTrue(after.contains("guarded system back override"))
    }

    private fun windowStubs(): Map<String, String> {
        val sources = mapOf(
            "android.annotation.TargetApi" to "package android.annotation; public @interface TargetApi { int value(); }",
            "android.os.Build" to "package android.os; public class Build { public static class VERSION { public static int SDK_INT = 33; } }",
            "android.content.Context" to "package android.content; public class Context { public static final String INPUT_METHOD_SERVICE = \"input_method\"; public Object getSystemService(String name) { return new android.view.inputmethod.InputMethodManager(); } }",
            "android.content.ContextWrapper" to "package android.content; public class ContextWrapper extends Context { public Context getBaseContext() { return this; } }",
            "android.view.inputmethod.InputMethodManager" to "package android.view.inputmethod; public class InputMethodManager { public static int hides; public boolean hideSoftInputFromWindow(Object token, int flags) { hides++; return true; } }",
            "android.view.KeyEvent" to """
                package android.view;
                public class KeyEvent {
                    public static final int KEYCODE_BACK = 4, ACTION_DOWN = 0, ACTION_UP = 1;
                    private final int action, code;
                    public KeyEvent(int action, int code) { this.action = action; this.code = code; }
                    public int getAction() { return action; } public int getKeyCode() { return code; }
                    public int getRepeatCount() { return 0; }
                }
            """.trimIndent(),
            "android.window.OnBackInvokedCallback" to "package android.window; public interface OnBackInvokedCallback { void onBackInvoked(); }",
            "android.window.OnBackInvokedDispatcher" to """
                package android.window;
                public class OnBackInvokedDispatcher {
                    public static final int PRIORITY_DEFAULT = 0, PRIORITY_OVERLAY = 1000000;
                    public OnBackInvokedCallback callback; public int priority, registrations;
                    public void registerOnBackInvokedCallback(int priority, OnBackInvokedCallback callback) { this.priority = priority; this.callback = callback; registrations++; }
                    public void unregisterOnBackInvokedCallback(OnBackInvokedCallback callback) { if (this.callback == callback) this.callback = null; }
                }
            """.trimIndent(),
            "android.view.WindowInsets" to "package android.view; public class WindowInsets { public boolean ime; public boolean isVisible(int type) { return ime; } public static class Type { public static int ime() { return 1; } } }",
            "android.view.ViewTreeObserver" to """
                package android.view;
                public class ViewTreeObserver {
                    public interface OnGlobalFocusChangeListener { void onGlobalFocusChanged(View oldFocus, View newFocus); }
                    public java.util.List<OnGlobalFocusChangeListener> listeners = new java.util.ArrayList<>();
                    public boolean isAlive() { return true; }
                    public void addOnGlobalFocusChangeListener(OnGlobalFocusChangeListener listener) { listeners.add(listener); }
                    public void removeOnGlobalFocusChangeListener(OnGlobalFocusChangeListener listener) { listeners.remove(listener); }
                    public void changed(View view) { for (OnGlobalFocusChangeListener listener : new java.util.ArrayList<>(listeners)) listener.onGlobalFocusChanged(null, view); }
                }
            """.trimIndent(),
            "android.view.View" to """
                package android.view;
                public class View {
                    public interface OnKeyListener { boolean onKey(View v, int code, KeyEvent event); }
                    public interface OnAttachStateChangeListener { void onViewAttachedToWindow(View v); void onViewDetachedFromWindow(View v); }
                    public final java.util.List<OnAttachStateChangeListener> listeners = new java.util.ArrayList<>();
                    public final ViewTreeObserver observer = new ViewTreeObserver();
                    public final android.window.OnBackInvokedDispatcher dispatcher = new android.window.OnBackInvokedDispatcher();
                    public final WindowInsets insets = new WindowInsets();
                    public androidx.activity.OnBackPressedDispatcherOwner owner;
                    public boolean attached, focused = true, shown = true;
                    public OnKeyListener keyListener;
                    public View() { owner = new androidx.activity.OnBackPressedDispatcherOwner() { final androidx.activity.OnBackPressedDispatcher back = new androidx.activity.OnBackPressedDispatcher(() -> fallbacks++); public androidx.activity.OnBackPressedDispatcher getOnBackPressedDispatcher() { return back; } }; }
                    public int fallbacks;
                    public void setOnKeyListener(OnKeyListener listener) { keyListener = listener; }
                    public boolean isAttachedToWindow() { return attached; } public boolean hasFocus() { return focused; } public boolean isShown() { return shown; }
                    public ViewTreeObserver getViewTreeObserver() { return observer; }
                    public android.window.OnBackInvokedDispatcher findOnBackInvokedDispatcher() { return dispatcher; }
                    public android.content.Context getContext() { return new android.content.Context(); }
                    public WindowInsets getRootWindowInsets() { return insets; } public Object getWindowToken() { return this; }
                    public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) { listeners.add(listener); }
                    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener) { listeners.remove(listener); }
                    protected void onAttachedToWindow() {} protected void onDetachedFromWindow() {}
                    public void attach() { attached = true; onAttachedToWindow(); for (OnAttachStateChangeListener listener : new java.util.ArrayList<>(listeners)) listener.onViewAttachedToWindow(this); }
                    public void detach() { attached = false; for (OnAttachStateChangeListener listener : new java.util.ArrayList<>(listeners)) listener.onViewDetachedFromWindow(this); onDetachedFromWindow(); }
                    public void clearFocus() { focused = false; observer.changed(this); }
                    public void focus() { focused = true; observer.changed(this); }
                    public boolean onKeyPreIme(int code, KeyEvent event) { return false; }
                    public boolean dispatchKeyEventPreIme(KeyEvent event) { return false; }
                }
            """.trimIndent(),
            "androidx.activity.OnBackPressedDispatcherOwner" to "package androidx.activity; public interface OnBackPressedDispatcherOwner { OnBackPressedDispatcher getOnBackPressedDispatcher(); }",
            "androidx.activity.ViewTreeOnBackPressedDispatcherOwner" to "package androidx.activity; public class ViewTreeOnBackPressedDispatcherOwner { public static OnBackPressedDispatcherOwner get(android.view.View view) { return view.owner; } }",
            "android.content.DialogInterface" to "package android.content; public interface DialogInterface { interface OnKeyListener { boolean onKey(DialogInterface dialog, int code, android.view.KeyEvent event); } }",
            "android.view.Window" to "package android.view; public class Window { public final View decor = new View(); public View getDecorView() { return decor; } }",
            "android.app.Dialog" to "package android.app; public class Dialog implements android.content.DialogInterface { public final android.view.Window window = new android.view.Window(); public int dismisses; public android.view.Window getWindow() { return window; } public void setOnKeyListener(android.content.DialogInterface.OnKeyListener listener) {} public void onBackPressed() { dismiss(); } public void dismiss() { dismisses++; } }",
            "androidx.fragment.app.Fragment" to "package androidx.fragment.app; public class Fragment {}",
            "androidx.fragment.app.DialogFragment" to "package androidx.fragment.app; public class DialogFragment extends Fragment { public android.app.Dialog onCreateDialog(android.os.Bundle state) { return new android.app.Dialog(); } }"
        )
        sources.forEach { (name, source) -> putSource(name.replace('.', '/') + ".java", source) }
        return sources
    }

    fun testPreImeSearchViewUsesOverlayAndReleasesWindowRegistration() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen extends android.view.View {
                int closes;
                @Override public boolean onKeyPreIme(int keyCode, android.view.KeyEvent event) {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) { closes++; return true; }
                    return super.onKeyPreIme(keyCode, event);
                }
            }
        """.trimIndent())
        val after = migrated(file)
        compileJava(after, """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.attach();
                    if (screen.dispatcher.priority != android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY) throw new AssertionError("IME priority lost");
                    screen.dispatcher.callback.onBackInvoked();
                    if (screen.closes != 1 || screen.fallbacks != 0) throw new AssertionError("back duplicated");
                    screen.clearFocus(); if (screen.dispatcher.callback != null) throw new AssertionError("unfocused callback retained");
                    screen.focus(); if (screen.dispatcher.callback == null) throw new AssertionError("callback not restored");
                    screen.detach(); if (screen.dispatcher.callback != null || !screen.observer.listeners.isEmpty()) throw new AssertionError("detach leaked listeners");
                    screen.attach(); if (screen.dispatcher.callback == null) throw new AssertionError("reattach lost callback");
                }
            }
        """.trimIndent(), stubs)
    }

    fun testPreImeClearFocusPassesBackToImeThenActivity() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen extends android.view.View {
                @Override public boolean dispatchKeyEventPreIme(android.view.KeyEvent event) {
                    if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) { clearFocus(); return false; }
                    return super.dispatchKeyEventPreIme(event);
                }
            }
        """.trimIndent())
        compileJava(migrated(file), """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.attach(); screen.insets.ime = true;
                    screen.dispatcher.callback.onBackInvoked();
                    if (screen.focused || screen.fallbacks != 0 || android.view.inputmethod.InputMethodManager.hides != 1) throw new AssertionError("IME back changed");
                    screen.insets.ime = false; screen.focus(); screen.dispatcher.callback.onBackInvoked();
                    if (screen.fallbacks != 1) throw new AssertionError("activity fallback lost");
                }
            }
        """.trimIndent(), stubs)
    }

    fun testDialogBackListenerKeepsCustomFragmentHook() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.java", """
            class Screen extends androidx.fragment.app.DialogFragment {
                public android.app.Dialog onCreateDialog(android.os.Bundle state) {
                    android.app.Dialog dialog = super.onCreateDialog(state);
                    dialog.setOnKeyListener((dialog1, keyCode, event) -> {
                        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event != null && event.getAction() == android.view.KeyEvent.ACTION_UP) { dialog.dismiss(); return true; }
                        return false;
                    });
                    return dialog;
                }
                protected boolean onBackPressed() { return false; }
            }
        """.trimIndent())
        val after = migrated(file)
        assertTrue(after.contains("protected boolean onBackPressed() { return false; }"))
        compileJava(after, """
            public class Main {
                public static void main(String[] args) {
                    android.app.Dialog dialog = new Screen().onCreateDialog(null);
                    dialog.window.decor.attach(); dialog.window.decor.dispatcher.callback.onBackInvoked();
                    if (dialog.dismisses != 1) throw new AssertionError("dialog back duplicated");
                    dialog.window.decor.detach();
                    if (dialog.window.decor.dispatcher.callback != null) throw new AssertionError("dialog callback leaked");
                }
            }
        """.trimIndent(), stubs)
    }

    fun testFragmentBooleanBackHookIsNotAnActivityMigration() {
        windowStubs()
        val file = myFixture.configureByText("LabelFragment.kt", """
            open class FullScreenFragment : androidx.fragment.app.Fragment() { open fun onBackPressed(): Boolean = false }
            class LabelFragment : FullScreenFragment() { override fun onBackPressed(): Boolean { return true } }
        """.trimIndent())
        assertNull(SystemBackMigration.plan(file))
    }

    fun testKotlinListBindingCompilesAndRebindsWithoutRetainingOldHolder() {
        val stubs = windowStubs()
        val file = myFixture.configureByText("Screen.kt", """
            class Screen {
                var calls = 0
                fun bind(view: android.view.View) {
                    view.setOnKeyListener { _, keyCode, event ->
                        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            if (view.hasFocus()) {
                                calls++
                                view.clearFocus()
                                return@setOnKeyListener true
                            }
                        }
                        return@setOnKeyListener false
                    }
                }
            }
        """.trimIndent())
        val after = migrated(file)
        assertFalse(after.contains("return@setOnKeyListener"))
        compileKotlinWindow(after, stubs, """
            public class Main {
                public static void main(String[] args) {
                    Screen old = new Screen(), current = new Screen();
                    android.view.View view = new android.view.View(); view.attach();
                    old.bind(view); current.bind(view);
                    if (view.listeners.size() != 1 || view.observer.listeners.size() != 1) throw new AssertionError("rebind leaked old holder");
                    view.dispatcher.callback.onBackInvoked();
                    if (old.getCalls() != 0 || current.getCalls() != 1 || view.fallbacks != 0) throw new AssertionError("wrong holder or duplicated back");
                    view.focus(); view.detach();
                    if (view.dispatcher.callback != null || !view.observer.listeners.isEmpty()) throw new AssertionError("detach retained callback");
                    view.attach(); view.dispatcher.callback.onBackInvoked();
                    if (current.getCalls() != 2) throw new AssertionError("reattach lost handler");
                    android.os.Build.VERSION.SDK_INT = 32;
                    view.focus(); current.bind(view);
                    if (view.dispatcher.callback != null) throw new AssertionError("API33 registration on old Android");
                    view.keyListener.onKey(view, 4, new android.view.KeyEvent(0, 4));
                    if (current.getCalls() != 3) throw new AssertionError("legacy key listener lost");
                }
            }
        """.trimIndent())
    }

    private fun compileKotlinWindow(after: String, stubs: Map<String, String>, main: String) {
        val folder = TemporaryFolder(); folder.create()
        try {
            val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
            val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("org.jetbrains.kotlin"))!!
            val candidates = listOf(plugin.pluginPath.resolve("kotlinc").toFile(),
                File(com.intellij.openapi.application.PathManager.getHomePath(), "plugins/Kotlin/kotlinc"))
            val resource = org.jetbrains.kotlin.psi.KtFile::class.java.getResource("KtFile.class")
            val source = org.jetbrains.kotlin.psi.KtFile::class.java.protectionDomain.codeSource?.location
                ?: (resource?.openConnection() as? java.net.JarURLConnection)?.jarFileURL
            val locations = source?.let { location ->
                generateSequence(File(location.toURI()).parentFile) { it.parentFile }.take(4).map { File(it, "kotlinc") }.toList()
            }.orEmpty()
            val kotlinHome = System.getenv("KOTLIN_COMPILER_HOME") ?: (candidates + locations).first { File(it, "lib/kotlin-compiler.jar").isFile }.path
            val lib = File(kotlinHome, "lib")
            val compilerJars = lib.listFiles()!!.filter { it.extension == "jar" }.joinToString(File.pathSeparator) { it.path }
            val facade = JavaPsiFacade.getInstance(project)
            val names = listOf("android.os.Bundle", "android.app.Activity", "androidx.activity.ComponentActivity", "androidx.activity.OnBackPressedCallback", "androidx.activity.OnBackPressedDispatcher")
            val sources = (names.associateWith { facade.findClass(it, GlobalSearchScope.allScope(project))!!.containingFile.text } + stubs)
                .mapKeys { it.key.replace('.', '/') + ".java" } + mapOf("Screen.kt" to after, "Main.java" to main,
                    "androidx/activity/Extensions.kt" to "package androidx.activity\nfun android.view.View.findViewTreeOnBackPressedDispatcherOwner(): OnBackPressedDispatcherOwner? = this.owner")
            val paths = sources.map { (path, source) -> File(folder.root, path).apply { parentFile.mkdirs(); writeText(source) }.path }
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
            fun run(args: List<String>) {
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.waitFor())
            }
            run(listOf("$javaHome/bin/java$suffix", "-Dkotlin.home=$kotlinHome", "-cp", compilerJars, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-jvm-target", "17", "-d", folder.root.path) + paths)
            val classpath = folder.root.path + File.pathSeparator + File(lib, "kotlin-stdlib.jar").path
            run(listOf("$javaHome/bin/javac$suffix", "-proc:none", "-cp", classpath, "-d", folder.root.path) + paths.filter { it.endsWith(".java") })
            run(listOf("$javaHome/bin/java$suffix", "-cp", classpath, "Main"))
        } finally { folder.delete() }
    }

    fun testNestedScrollBackExclusionIsNotMigration() {
        val file = myFixture.configureByText("Scroll.java", """
            class Scroll {
                boolean executeKeyEvent(int code) {
                    if (code != android.view.KeyEvent.KEYCODE_BACK) return true;
                    return false;
                }
            }
        """.trimIndent())
        assertNull(SystemBackMigration.plan(file))
        val consuming = myFixture.configureByText("Consume.java", """
            class Consume { boolean key(int code) { if (code != android.view.KeyEvent.KEYCODE_BACK) return false; return true; } }
        """.trimIndent())
        assertNull(SystemBackMigration.plan(consuming)!!.after)
    }

    fun testActivityKeyDownExtractsOnlyBackBranchAndCompiles() {
        addJavaClass("""
            package android.view;
            public class KeyEvent {
                public static final int KEYCODE_BACK = 4, ACTION_DOWN = 0, ACTION_UP = 1;
                public KeyEvent(int action, int code) {}
            }
        """.trimIndent())
        val file = myFixture.configureByText("Screen.java", """
            class Screen extends androidx.activity.ComponentActivity {
                boolean editing; int prompts;
                @Override protected void onCreate(android.os.Bundle state) { super.onCreate(state); }
                public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        if (editing) { editing = false; return true; }
                        if (showBackTips()) return true;
                    }
                    return false;
                }
                boolean showBackTips() { prompts++; return false; }
            }
        """.trimIndent())
        val after = migrated(file)
        assertTrue(after.contains("public boolean onKeyDown"))
        compileJava(after, """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.onCreate(null); screen.editing = true;
                    screen.getOnBackPressedDispatcher().onBackPressed();
                    if (screen.editing || screen.finishes != 0 || screen.prompts != 0) throw new AssertionError("edit branch changed");
                    screen.getOnBackPressedDispatcher().onBackPressed();
                    if (screen.finishes != 1 || screen.prompts != 1) throw new AssertionError("fallback duplicated");
                }
            }
        """.trimIndent(), mapOf("android.view.KeyEvent" to JavaPsiFacade.getInstance(project).findClass("android.view.KeyEvent", GlobalSearchScope.allScope(project))!!.containingFile.text))
    }

    fun testWholeProjectScansJavaKotlinAndReportsManualCases() {
        val javaSource = java().text.replace("Screen", "JavaScreen")
        val kotlinSource = kotlin().text.replace("Screen", "KotlinScreen")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        // Framework stubs and configureByText files also live under this fixture's source root.
        val fixtureFiles = MigrateSystemBackProjectAction.scan(project).map { it.file }.toSet()
        val javaFile = myFixture.addFileToProject("feature/JavaScreen.java", javaSource)
        val kotlinFile = myFixture.addFileToProject("other/KotlinScreen.kt", kotlinSource)
        val manualFile = myFixture.addFileToProject("feature/KeyScreen.java", """
            class KeyScreen extends androidx.activity.ComponentActivity {
                boolean onKeyDown(int code) { return code == android.view.KeyEvent.KEYCODE_BACK; }
            }
        """.trimIndent())
        val generated = myFixture.addFileToProject("generated/GeneratedScreen.java", javaSource.replace("JavaScreen", "GeneratedScreen"))
        val output = myFixture.addFileToProject("build/BuildScreen.java", javaSource.replace("JavaScreen", "BuildScreen"))
        ModuleRootModificationUtil.updateModel(module) { model ->
            val content = model.contentEntries.single()
            val root = content.addSourceFolder(generated.virtualFile.parent, JavaSourceRootType.SOURCE)
            (root.jpsElement.properties as JavaSourceRootProperties).isForGeneratedSources = true
            content.addExcludeFolder(output.virtualFile.parent)
        }
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val entries = MigrateSystemBackProjectAction.scan(project).filterNot { it.file in fixtureFiles }
        assertEquals(setOf(javaFile.virtualFile, kotlinFile.virtualFile, manualFile.virtualFile), entries.map { it.file }.toSet())
        assertEquals(2, entries.count { it.result.after != null })
        assertTrue(entries.single { it.file == manualFile.virtualFile }.result.reason.contains("KEYCODE_BACK"))
        assertTrue(MigrateSystemBackProjectAction.applyChanges(project, entries))
        assertTrue(javaFile.text.contains("JavaScreen.this.syntheticCompatHandleSystemBack()"))
        assertTrue(kotlinFile.text.contains("this@KotlinScreen.syntheticCompatHandleSystemBack()"))
        assertFalse(manualFile.text.contains("addCallback"))
        val remaining = MigrateSystemBackProjectAction.scan(project).filterNot { it.file in fixtureFiles }
        assertEquals(listOf(manualFile.virtualFile), remaining.map { it.file })
    }

    fun testWholeProjectRejectsStalePreviewWithoutChangingOtherFiles() {
        val source = java().text
        val fixtureFiles = MigrateSystemBackProjectAction.scan(project).map { it.file }.toSet()
        val first = myFixture.addFileToProject("feature/First.java", source.replace("Screen", "First"))
        val second = myFixture.addFileToProject("feature/Second.java", source.replace("Screen", "Second"))
        val entries = MigrateSystemBackProjectAction.scan(project).filterNot { it.file in fixtureFiles }
        assertEquals(2, entries.size)
        val document = FileDocumentManager.getInstance().getDocument(first.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// edited after preview\n") }
        assertFalse(MigrateSystemBackProjectAction.applyChanges(project, entries))
        assertTrue(document.text.startsWith("// edited after preview"))
        assertFalse(document.text.contains("addCallback"))
        assertFalse(second.text.contains("addCallback"))
    }

    fun testIndirectFallbackDoesNotReenterAndRestoresCallbackAfterException() {
        val after = migrated(java("if (++calls == 1) throw new IllegalStateException(); fallback();",
            extra = "int calls; private void fallback() { finish(); }"))
        // A helper can change after migration or a library can hide its implementation.
        // Exercise the generated guard against an indirect dispatcher call at runtime.
        compileJava(after.replace("private void fallback() { finish(); }", "private void fallback() { super.onBackPressed(); }"), """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen();
                    screen.onCreate(null);
                    androidx.activity.OnBackPressedDispatcher dispatcher = screen.getOnBackPressedDispatcher();
                    try { dispatcher.onBackPressed(); throw new AssertionError("exception was swallowed"); }
                    catch (IllegalStateException expected) {}
                    if (!dispatcher.hasEnabledCallbacks()) throw new AssertionError("callback not restored after exception");
                    dispatcher.onBackPressed();
                    dispatcher.onBackPressed();
                    if (screen.calls != 3 || screen.finishes != 2) throw new AssertionError("recursive or duplicate dispatch");
                    if (!dispatcher.hasEnabledCallbacks()) throw new AssertionError("callback not restored after fallback");
                }
            }
        """.trimIndent())
    }

    fun testKotlinIndirectFallbackIncludesExceptionSafeGuard() {
        val after = migrated(kotlin("fallback()", extra = "private fun fallback() { finish() }"))
        assertTrue(after.contains("isEnabled = false"))
        assertTrue(after.contains("finally {\n                    isEnabled = true"))
    }

    fun testRejectsIndirectBackCallsThroughJavaAndKotlinHelpers() {
        val javaResult = SystemBackMigration.plan(java("first();", extra = "private void first() { fallback(); } private void fallback() { super.onBackPressed(); }"))!!
        assertNull(javaResult.after)
        assertTrue(javaResult.reason.contains("辅助方法"))
        val kotlinResult = SystemBackMigration.plan(kotlin("first()", extra = "private fun first() { fallback() }; private fun fallback() { super.onBackPressed() }"))!!
        assertNull(kotlinResult.after)
        assertTrue(kotlinResult.reason.contains("辅助方法"))
    }

    fun testRejectsInheritedHelperThatCallsBackApi() {
        addJavaClass("class Middle extends androidx.activity.ComponentActivity { protected void fallback() { super.onBackPressed(); } }")
        val source = java("fallback();").text.replace("extends androidx.activity.ComponentActivity", "extends Middle")
        assertNull(SystemBackMigration.plan(myFixture.configureByText("Screen.java", source))!!.after)
    }

    fun testUnchangedLegacyBridgesAreUpgradedWithoutAddingCallbacks() {
        val safeJava = SystemBackMigration.planLegacy(java())!!.after!!
        val oldJava = safeJava.replace("setEnabled(false);\n                try {\n                    Screen.this.onBackPressed();\n                } finally {\n                    setEnabled(true);\n                }", "Screen.this.onBackPressed();")
        val javaResult = SystemBackMigration.plan(myFixture.configureByText("Screen.java", oldJava))!!
        assertFalse(javaResult.after!!.contains("public void onBackPressed()"))
        assertTrue(javaResult.after!!.contains("Screen.this.syntheticCompatHandleSystemBack()"))
        compileJava(javaResult.after!!)
        val safeKotlin = SystemBackMigration.planLegacy(kotlin())!!.after!!
        val oldKotlin = safeKotlin.replace("isEnabled = false\n                try {\n                    this@Screen.onBackPressed()\n                } finally {\n                    isEnabled = true\n                }", "this@Screen.onBackPressed()")
        val kotlinResult = SystemBackMigration.plan(myFixture.configureByText("Screen.kt", oldKotlin))!!
        assertFalse(kotlinResult.after!!.contains("override fun onBackPressed()"))
        assertTrue(kotlinResult.after!!.contains("this@Screen.syntheticCompatHandleSystemBack()"))
    }

    fun testBusinessPackagesNamedBuildAndGeneratedAreIncluded() {
        val buildFile = myFixture.addFileToProject("com/example/build/Business.java", "package com.example.build; class Business {}")
        val generatedFile = myFixture.addFileToProject("com/example/generated/Business.kt", "package com.example.generated; class Business")
        val files = com.github.kevin58cmd.customkotlinxandroidsynthetic.actions.MigrateButterKnifeAction.collectMigrationFiles(project, null)
        assertTrue(buildFile.virtualFile in files)
        assertTrue(generatedFile.virtualFile in files)
    }

    fun testSourceRootUnderBuildNamedAncestorIsIncluded() {
        val file = myFixture.addFileToProject("build/myapp/src/main/java/Ancestor.java", "class Ancestor {}")
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.contentEntries.single().addSourceFolder(file.virtualFile.parent, JavaSourceRootType.SOURCE)
        }
        val files = com.github.kevin58cmd.customkotlinxandroidsynthetic.actions.MigrateButterKnifeAction.collectMigrationFiles(project, null)
        assertTrue(file.virtualFile in files)
    }

    fun testConditionalDefaultBackPreservesBothDirectAndDispatcherCalls() {
        val after = migrated(java("calls++; if (allow) super.onBackPressed();", extra = "int calls; boolean allow;"))
        compileJava(after, """
            public class Main {
                public static void main(String[] args) {
                    Screen screen = new Screen(); screen.onCreate(null);
                    screen.getOnBackPressedDispatcher().onBackPressed();
                    screen.onBackPressed();
                    if (screen.calls != 2 || screen.finishes != 0) throw new AssertionError("false condition must consume back");
                    screen.allow = true;
                    screen.onBackPressed();
                    screen.getOnBackPressedDispatcher().onBackPressed();
                    if (screen.calls != 4 || screen.finishes != 2) throw new AssertionError("default back duplicated");
                    if (!screen.getOnBackPressedDispatcher().hasEnabledCallbacks()) throw new AssertionError("callback not restored");
                }
            }
        """.trimIndent())
    }

    fun testUserPreferenceExampleAndMissingOnCreate() {
        addJavaClass("""
            package android.preference;
            public class PreferenceManager {
                public static PreferenceManager getDefaultSharedPreferences(android.app.Activity activity) { return new PreferenceManager(); }
                public boolean getBoolean(String key, boolean fallback) { return fallback; }
            }
        """.trimIndent())
        val file = myFixture.configureByText("Screen.kt", """
            import android.preference.PreferenceManager
            class Screen : androidx.activity.ComponentActivity() {
                override fun onBackPressed() {
                    if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("firsOpen", true)) super.onBackPressed()
                }
            }
        """.trimIndent())
        val after = migrated(file)
        assertTrue(after.contains("getBoolean(\"firsOpen\", true)"))
        assertTrue(after.contains("override fun onCreate(savedInstanceState: android.os.Bundle?)"))
        assertTrue(after.contains("try {"))
    }

    fun testExpressionDefaultAndMissingJavaOnCreate() {
        val kt = myFixture.configureByText("Screen.kt", """
            class Screen : androidx.activity.ComponentActivity() {
                override fun onBackPressed() = super.onBackPressed()
            }
        """.trimIndent())
        val kotlinAfter = migrated(kt)
        assertFalse(kotlinAfter.contains("onBackPressed() ="))
        val javaFile = myFixture.configureByText("Screen.java", """
            class Screen extends androidx.activity.ComponentActivity {
                @Override public void onBackPressed() { super.onBackPressed(); }
            }
        """.trimIndent())
        val javaAfter = migrated(javaFile)
        compileJava(javaAfter)
    }

    fun testDeferredDefaultBackStillRequiresManualReview() {
        assertNull(SystemBackMigration.plan(java("Runnable action = () -> super.onBackPressed(); action.run();"))!!.after)
        assertNull(SystemBackMigration.plan(kotlin("val action = { super.onBackPressed() }; action()"))!!.after)
    }

    fun testExistingCallbackWithoutOnCreateStillRequiresManualReview() {
        val javaFile = myFixture.configureByText("Screen.java", """
            class Screen extends androidx.activity.ComponentActivity {
                @Override public void onBackPressed() { super.onBackPressed(); }
                void register(androidx.activity.OnBackPressedCallback callback) {
                    getOnBackPressedDispatcher().addCallback(this, callback);
                }
            }
        """.trimIndent())
        assertNull(SystemBackMigration.plan(javaFile)!!.after)
        val kt = myFixture.configureByText("Screen.kt", """
            class Screen : androidx.activity.ComponentActivity() {
                override fun onBackPressed() { super.onBackPressed() }
                fun register(callback: androidx.activity.OnBackPressedCallback) {
                    onBackPressedDispatcher.addCallback(this, callback)
                }
            }
        """.trimIndent())
        assertNull(SystemBackMigration.plan(kt)!!.after)
    }

    fun testManualReportContainsPathsReasonsCodeAndDoesNotOverwrite() {
        val source = java(extra = "boolean onKeyDown(int code) { return code == android.view.KeyEvent.KEYCODE_BACK; }").text
        val file = myFixture.addFileToProject("manual/Screen.java", source)
        val result = SystemBackMigration.plan(file)!!
        assertNull(result.after)
        val entry = MigrateSystemBackProjectAction.Companion.Entry(file.virtualFile, result)
        val folder = TemporaryFolder()
        folder.create()
        try {
            val report = com.github.kevin58cmd.customkotlinxandroidsynthetic.actions.SystemBackMigrationReport.write(folder.root.toPath(), listOf(entry))!!
            val text = java.nio.file.Files.readString(report)
            assertTrue(text.contains(file.virtualFile.path))
            assertTrue(text.contains(result.reason))
            assertTrue(text.contains("KEYCODE_BACK"))
            assertTrue(text.contains("boolean onKeyDown"))
            assertTrue(text.contains("位置："))
            val second = com.github.kevin58cmd.customkotlinxandroidsynthetic.actions.SystemBackMigrationReport.write(folder.root.toPath(), listOf(entry))!!
            assertFalse(report == second)
            assertTrue(java.nio.file.Files.exists(report))
            assertEquals(source, file.text)
        } finally { folder.delete() }
    }
}
