package com.github.kevin58cmd.customkotlinxandroidsynthetic

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ResourceSwitchMigration
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ResourceSwitchMigrationTest : BasePlatformTestCase() {
    private val resources = """package example; public class R { public static class id {
        public static int a=1,b=2,sb_text_size=4,sb_text_alpha=5,sb_brightness=6,
            sb_blur=7,sb_line_spacing=8,sb_word_spacing=9;
        public static final int fixed=3;
    } }"""

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("example/R.java", resources)
    }

    private fun plan(body: String) = ResourceSwitchMigration.plan(myFixture.configureByText("Sample.java",
        "package example; class Sample { int next(){return 1;} void use(){} void run(int id) { $body } }") as PsiJavaFile)

    fun testGroupedLabelsDefaultAndSingleEvaluation() {
        val result = plan("switch(next()){ case R.id.a: case R.id.b: use(); break; default: use(); break; }")!!
        val after = result.after!!
        assertTrue(after.contains("syntheticViewId0 == R.id.a || syntheticViewId0 == R.id.b"))
        assertTrue(after.contains("else"))
        assertEquals(1, Regex("= next\\(\\)").findAll(after).count())
        assertFalse(after.contains("break;"))
        val parsed = PsiFileFactory.getInstance(project).createFileFromText("Sample.java", JavaFileType.INSTANCE, after)
        assertFalse(PsiTreeUtil.hasErrorElements(parsed))
    }

    fun testSkipsFallthroughAndConditionalBreak() {
        assertNull(plan("switch(id){case R.id.a: use(); case R.id.b: use(); break;}")!!.after)
        assertNull(plan("switch(id){case R.id.a: if(id>0) break; use(); break;}")!!.after)
    }

    fun testIgnoresConstantAndUnresolvedLabels() {
        assertNull(plan("switch(id){case R.id.fixed: use(); break;}"))
        assertNull(plan("switch(id){case Missing.id.a: use(); break;}"))
    }

    fun testAvoidsNameCollisionAndPreservesComments() {
        val after = plan("int syntheticViewId0=0; switch(id){ /* before */ case R.id.a: /* action */ use(); break; // after\n }")!!.after!!
        assertTrue(after.contains("int syntheticViewId1 = id;"))
        assertTrue(after.contains("/* before */"))
        assertTrue(after.contains("/* action */"))
        assertTrue(after.contains("// after"))
    }

    fun testSkipsCrossBranchDeclarationsAndConvertsIndependentSwitch() {
        val result = plan("switch(id){case R.id.a: int x=0; break; case R.id.b: x=1; break;} switch(id){case R.id.a: use(); break;}")!!
        assertTrue(result.after!!.contains("int x=0"))
        assertTrue(result.after!!.contains("if ("))
        assertTrue(result.reason!!.contains("跳过 1"))
    }

    fun testReturnThrowAndLoopBreak() {
        val after = plan("switch(id){case R.id.a: while(id>0){break;} return; default: throw new RuntimeException();}")!!.after!!
        assertTrue(after.contains("while(id>0){break;}"))
        assertTrue(after.contains("return;"))
        assertTrue(after.contains("throw new RuntimeException();"))
    }

    fun testBranchLocalDeclarationsAndReferencesRemainInSameScope() {
        val after = plan("""switch(id) {
            case R.id.a:
                int rgb = 0, color = rgb + 1;
                if (id > 0) rgb = 255;
                final int captured = color;
                Runnable action = () -> { int copy = captured; use(); };
                action.run();
                break;
            case R.id.b:
                use();
                break;
        }""")!!.after!!
        val parsed = PsiFileFactory.getInstance(project).createFileFromText("Sample.java", JavaFileType.INSTANCE, after)
        val declarations = PsiTreeUtil.findChildrenOfType(parsed, com.intellij.psi.PsiLocalVariable::class.java)
        for (name in listOf("rgb", "color", "captured", "action")) {
            val declaration = declarations.single { it.name == name }
            val references = PsiTreeUtil.findChildrenOfType(parsed, com.intellij.psi.PsiReferenceExpression::class.java)
                .filter { it.referenceName == name && it.qualifierExpression == null }
            assertTrue("No usages found for $name", references.isNotEmpty())
            assertTrue("Lost scope for $name", references.all { it.resolve() == declaration })
        }
    }

    fun testSkipsLocalClassesAndCrossBranchCaptures() {
        assertNull(plan("switch(id){case R.id.a: class Local {} use(); break;}")!!.after)
        assertNull(plan("switch(id){case R.id.a: int x=0; break; case R.id.b: Runnable r=()->{use(); x++;}; break;}")!!.after)
    }

    private fun runBody(source: String): com.intellij.psi.PsiCodeBlock {
        val parsed = PsiFileFactory.getInstance(project).createFileFromText("Sample.java", JavaFileType.INSTANCE, source) as PsiJavaFile
        assertFalse(PsiTreeUtil.hasErrorElements(parsed))
        return parsed.classes.single().findMethodsByName("run", false).single().body!!
    }

    fun testMethodBodyDoesNotGainAnotherBlock() {
        val after = plan("switch(id){case R.id.a: use(); break;}")!!.after!!
        val statements = runBody(after).statements
        assertEquals(2, statements.size)
        assertTrue(statements[0] is com.intellij.psi.PsiDeclarationStatement)
        assertTrue(statements[1] is com.intellij.psi.PsiIfStatement)
    }

    fun testMultipleSwitchesShareScopeWithoutDuplicateCacheVariables() {
        val after = plan("switch(id){case R.id.a: use(); break;} switch(next()){case R.id.b: use(); break;}")!!.after!!
        val body = runBody(after)
        assertEquals(4, body.statements.size)
        val variables = body.statements.filterIsInstance<com.intellij.psi.PsiDeclarationStatement>()
            .map { it.declaredElements.single() as com.intellij.psi.PsiLocalVariable }
        assertEquals(2, variables.map { it.name }.distinct().size)
        val references = PsiTreeUtil.findChildrenOfType(body, com.intellij.psi.PsiReferenceExpression::class.java)
            .filter { it.referenceName?.startsWith("syntheticViewId") == true }
        assertEquals(2, references.size)
        assertTrue(references.all { it.resolve() in variables })
    }

    fun testSingleStatementIfLoopAndLabelKeepRequiredBlocks() {
        val after = plan("""if(id>0) switch(id){case R.id.a: use(); break;} else use();
            while(id>0) switch(next()){case R.id.b: use(); break;}
            target: switch(id){case R.id.a: use(); break;}
        """)!!.after!!
        val statements = runBody(after).statements
        val conditional = statements[0] as com.intellij.psi.PsiIfStatement
        val loop = statements[1] as com.intellij.psi.PsiWhileStatement
        val label = statements[2] as com.intellij.psi.PsiLabeledStatement
        assertTrue(conditional.thenBranch is com.intellij.psi.PsiBlockStatement)
        assertNotNull(conditional.elseBranch)
        assertTrue(loop.body is com.intellij.psi.PsiBlockStatement)
        assertTrue(label.statement is com.intellij.psi.PsiBlockStatement)
        assertNull(ResourceSwitchMigration.plan(PsiFileFactory.getInstance(project)
            .createFileFromText("Sample.java", JavaFileType.INSTANCE, after) as PsiJavaFile))
    }

    fun testCleansLegacyGeneratedBlockAndPreservesComments() {
        val result = plan("""{
            /* cache */ int syntheticViewId0 = id;
            if(syntheticViewId0 == R.id.a || syntheticViewId0 == R.id.b) {
                /* action */ use();
            } else { use(); }
            // end
        }""")!!
        val after = result.after!!
        assertEquals(2, runBody(after).statements.size)
        assertTrue(after.contains("/* cache */"))
        assertTrue(after.contains("/* action */"))
        assertTrue(after.contains("// end"))
        assertTrue(result.reason!!.contains("清理多余代码块 1"))
        assertNull(ResourceSwitchMigration.plan(myFixture.configureByText("Sample.java", after) as PsiJavaFile))
    }

    fun testOnlyCleansGeneratedScopesThatCanBeFlattened() {
        assertNull(plan("{int customId=id; if(customId==R.id.a){use();}}"))
        assertNull(plan("{int syntheticViewId0=id; if(syntheticViewId0==R.id.a){syntheticViewId0++;}}"))
        assertNull(plan("if(id>0){int syntheticViewId0=id; if(syntheticViewId0==R.id.a){use();}}"))
    }

    fun testLegacyCleanupAvoidsFieldAndRepeatedCacheNameConflicts() {
        val before = """package example; class Sample {
            int syntheticViewId0 = 99, count;
            void use() { count++; }
            void run(int id) {
                { int syntheticViewId0 = id; if(syntheticViewId0 == R.id.a) { use(); } }
                { int syntheticViewId0 = id; if(syntheticViewId0 == R.id.b) { use(); } }
                if(syntheticViewId0 != 99) throw new AssertionError("field was shadowed");
            }
            public static void main(String[] args) {
                Sample sample = new Sample();
                sample.run(1); sample.run(2); sample.run(99);
                System.out.println(sample.count + "," + sample.syntheticViewId0);
            }
        }""".trimIndent()
        val result = ResourceSwitchMigration.plan(myFixture.configureByText("Sample.java", before) as PsiJavaFile)!!
        val after = result.after!!
        assertTrue(result.reason!!.contains("清理多余代码块 2"))
        assertEquals(5, runBody(after).statements.size)
        assertEquals(compileAndRun(before, finalIds = false), compileAndRun(after, finalIds = false))
    }

    fun testIndentationChangesPreserveMultilineStringValues() {
        val levels = com.intellij.openapi.roots.LanguageLevelProjectExtension.getInstance(project)
        val previous = levels.languageLevel
        levels.languageLevel = com.intellij.pom.java.LanguageLevel.JDK_17
        try {
            val quote = "\"\"\""
            val before = """package example; class Sample {
                void run(int id) {
                    switch(id) {
                        case R.id.a:
                            String text = $quote
                                indented
                      lessIndented
                                tail
                                $quote;
                            System.out.print(text);
                            break;
                    }
                }
                public static void main(String[] args) { new Sample().run(R.id.a); }
            }""".trimIndent()
            val after = ResourceSwitchMigration.plan(myFixture.configureByText("Sample.java", before) as PsiJavaFile)!!.after!!
            val expected = compileAndRun(before, finalIds = true)
            assertEquals(expected, compileAndRun(after, finalIds = false))
            val body = runBody(after)
            val legacy = after.replaceRange(body.textRange.startOffset, body.textRange.endOffset, "{\n${body.text}\n}")
            val cleaned = ResourceSwitchMigration.plan(myFixture.configureByText("Sample.java", legacy) as PsiJavaFile)!!.after!!
            assertEquals(2, runBody(cleaned).statements.size)
            assertEquals(expected, compileAndRun(cleaned, finalIds = false))
        } finally {
            levels.languageLevel = previous
        }
    }

    fun testSeekBarCallbackCompilesAndPreservesRuntimeBehavior() {
        val before = """package example;
            class Sample {
                static final int MAX_BRIGHTNESS_VALUE = 200;
                int textSize, textAlpha, brightness, blur, lineSpacing, wordSpacing, initCalls;
                float startWordSpacing = 0.5f;
                boolean hasBitmap = true, supportsWordSpacing = true;
                int initialRgb() { initCalls++; return 0; }
                static class SeekBar {
                    int id, reads;
                    SeekBar(int id) { this.id = id; }
                    int getId() { reads++; return id; }
                }
                void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    switch (seekBar.getId()) {
                        case R.id.sb_text_size:
                            textSize = progress;
                            break;
                        case R.id.sb_text_alpha:
                            textAlpha = progress;
                            break;
                        case R.id.sb_brightness:
                            int rgb = initialRgb();
                            if (progress > MAX_BRIGHTNESS_VALUE / 2) rgb = 255;
                            int color = (Math.abs(MAX_BRIGHTNESS_VALUE / 2 - progress) << 24)
                                | (rgb << 16) | (rgb << 8) | rgb;
                            brightness = color;
                            break;
                        case R.id.sb_blur:
                            if (hasBitmap) {
                                if (progress == 0) blur = 0;
                                else blur = progress * 2;
                            }
                            break;
                        case R.id.sb_line_spacing:
                            if (progress > 100) lineSpacing = 100;
                            else lineSpacing = progress;
                            break;
                        case R.id.sb_word_spacing:
                            if (supportsWordSpacing) {
                                float change = startWordSpacing + progress * 0.01f;
                                if (change > 1.0f) wordSpacing = 50;
                                else wordSpacing = progress;
                            }
                            break;
                    }
                }
                public static void main(String[] args) {
                    Sample sample = new Sample();
                    for (int id : new int[]{4,5,6,7,8,9,99}) {
                        for (int progress : new int[]{0,50,100,101,200}) {
                            SeekBar bar = new SeekBar(id);
                            int calls = sample.initCalls;
                            sample.onProgressChanged(bar, progress, true);
                            if (bar.reads != 1) throw new AssertionError("selector evaluated repeatedly");
                            if (sample.initCalls - calls != (id == 6 ? 1 : 0)) throw new AssertionError("wrong initializer timing");
                            System.out.println(sample.textSize + "," + sample.textAlpha + "," + sample.brightness
                                + "," + sample.blur + "," + sample.lineSpacing + "," + sample.wordSpacing);
                        }
                    }
                    sample.hasBitmap = false;
                    sample.supportsWordSpacing = false;
                    sample.onProgressChanged(new SeekBar(7), 10, false);
                    sample.onProgressChanged(new SeekBar(9), 10, false);
                    System.out.println(sample.blur + "," + sample.wordSpacing);
                }
            }
        """.trimIndent()
        val result = ResourceSwitchMigration.plan(myFixture.configureByText("Sample.java", before) as PsiJavaFile)!!
        val after = result.after ?: error(result.reason ?: "Callback was skipped")
        val parsed = PsiFileFactory.getInstance(project).createFileFromText("Sample.java", JavaFileType.INSTANCE, after) as PsiJavaFile
        val callback = parsed.classes.single().findMethodsByName("onProgressChanged", false).single()
        assertEquals(2, callback.body!!.statements.size)
        assertEquals(compileAndRun(before, finalIds = true), compileAndRun(after, finalIds = false))
    }

    private fun compileAndRun(source: String, finalIds: Boolean): String {
        val folder = TemporaryFolder()
        folder.create()
        try {
            val root = folder.root.toPath()
            val sample = root.resolve("Sample.java")
            val r = root.resolve("R.java")
            Files.writeString(sample, source)
            Files.writeString(r, if (finalIds) resources.replace("public static int", "public static final int") else resources)
            val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
            fun command(args: List<String>): String {
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    error("Java verification timed out")
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 0, process.exitValue())
                return output
            }
            command(listOf(Path.of(javaHome, "bin", "javac$suffix").toString(), "-proc:none", "-d", root.toString(), sample.toString(), r.toString()))
            return command(listOf(Path.of(javaHome, "bin", "java$suffix").toString(), "-cp", root.toString(), "example.Sample"))
        } finally {
            folder.delete()
        }
    }
}

