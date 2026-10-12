package com.github.kevin58cmd.customkotlinxandroidsynthetic

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ParcelizeMigration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile

class ParcelizeMigrationTest : BasePlatformTestCase() {
    fun testFindsNearestModuleBuildScript() {
        val root = myFixture.addFileToProject("build.gradle.kts", "plugins {}")
        val module = myFixture.addFileToProject("app/build.gradle", "plugins {}")
        val source = myFixture.addFileToProject("app/src/main/kotlin/Example.kt", "class Example")
        assertEquals(module.virtualFile, com.github.kevin58cmd.customkotlinxandroidsynthetic.actions.MigrateParcelizeAction.findBuildScript(project, source.virtualFile))
        val other = myFixture.addFileToProject("src/main/kotlin/Other.kt", "class Other")
        assertEquals(root.virtualFile, com.github.kevin58cmd.customkotlinxandroidsynthetic.actions.MigrateParcelizeAction.findBuildScript(project, other.virtualFile))
    }
    private fun plan(text: String) = ParcelizeMigration.plan(myFixture.configureByText("Example.kt", text) as KtFile)

    fun testImportsAliasesAndWildcard() {
        val source = """
            import kotlinx.android.parcel.Parcelize as Persist
            import kotlinx.android.parcel.RawValue
            import kotlinx.android.parcel.Parceler
            import kotlinx.android.parcel.TypeParceler
            import kotlinx.android.parcel.WriteWith
            import kotlinx.android.parcel.IgnoredOnParcel
            import kotlinx.android.parcel.*
            @Persist class Example
        """.trimIndent()
        assertEquals(source.replace("kotlinx.android.parcel", "kotlinx.parcelize"), plan(source)!!.after)
    }

    fun testQualifiedReferencesAndLiteralPreservation() {
        val source = """
            // kotlinx.android.parcel.Parcelize
            val description = "kotlinx.android.parcel.Parcelize"
            @kotlinx.android.parcel.Parcelize
            class Example(val data: @kotlinx.android.parcel.RawValue Any)
        """.trimIndent()
        val result = plan(source)!!
        assertEquals(source.replace("@kotlinx.android.parcel.", "@kotlinx.parcelize."), result.after)
        assertNull(plan(result.after))
    }

    fun testUnrelatedPackageIsUnchanged() {
        assertNull(plan("import kotlinx.android.parcelable.Other\nclass Example"))
        assertNull(plan("package kotlinx.android.parcel\nclass Example"))
    }

    fun testCommentsWithinQualifiedReferencesArePreserved() {
        val source = "import kotlinx./* keep */android.parcel.Parcelize\n@kotlinx.android./* type comment */parcel.Parcelize class Example"
        val result = plan(source)!!.after
        assertTrue(result.contains("/* keep */"))
        assertTrue(result.contains("/* type comment */"))
        assertNull(plan(result))
    }

    fun testExistingModernImportDoesNotBecomeDuplicate() {
        val result = plan("import kotlinx.android./* retained */parcel.Parcelize\nimport kotlinx.parcelize.Parcelize\n@Parcelize class Example")!!.after
        assertTrue(result.contains("/* retained */"))
        assertEquals(1, Regex("import kotlinx.parcelize.Parcelize").findAll(result).count())
        assertNull(plan(result))
    }
}
