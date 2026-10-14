package com.github.kevin58cmd.customkotlinxandroidsynthetic

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ParcelizeGradleMigration
import junit.framework.TestCase

class ParcelizeGradleMigrationTest : TestCase() {
    fun testGroovyApplicationUsesApplyImmediatelyAfterAndroid() {
        for (declaration in listOf("apply plugin: 'kotlin-android-extensions'", "apply plugin: 'kotlin-parcelize'",
            "plugins { id 'kotlin-android-extensions' }", "plugins { id('org.jetbrains.kotlin.plugin.parcelize') }", "")) {
            val source = "$declaration\napply plugin: 'com.android.application' // Android first\napply plugin: 'kotlin-android'\nandroid { compileSdk 34 }"
            val result = ParcelizeGradleMigration.plan(source, false).after!!
            assertTrue(result.contains("apply plugin: 'com.android.application' // Android first\napply plugin: 'kotlin-parcelize'\napply plugin: 'kotlin-android'"))
            assertFalse(result.contains("kotlin-android-extensions"))
            assertEquals(1, Regex("kotlin-parcelize").findAll(result).count())
            assertEquals(result, ParcelizeGradleMigration.plan(result, false).after)
        }
    }

    fun testGroovyApplicationOrderingWithCrLfAndSameLineStatements() {
        val source = "apply plugin: 'com.android.application'\r\napply plugin: 'kotlin-android-extensions'\r\n"
        val expected = "apply plugin: 'com.android.application'\r\napply plugin: 'kotlin-parcelize'\r\n"
        assertEquals(expected, ParcelizeGradleMigration.plan(source, false).after)
        assertEquals(expected, ParcelizeGradleMigration.plan(expected, false).after)
        val inline = "apply plugin: 'com.android.application'; apply plugin: 'kotlin-android-extensions'"
        val result = ParcelizeGradleMigration.plan(inline, false).after!!
        assertTrue(result.startsWith("apply plugin: 'com.android.application';\napply plugin: 'kotlin-parcelize'"))
        assertEquals(result, ParcelizeGradleMigration.plan(result, false).after)
        assertNull(ParcelizeGradleMigration.plan("plugins { id 'kotlin-android-extensions' version '1.6.21' }\napply plugin: 'com.android.application'", false).after)
    }
    fun testKotlinDslAndVersionArePreserved() {
        val source = "plugins {\n    id(\"org.jetbrains.kotlin.android.extensions\") version \"1.8.0\"\n}\n"
        val result = ParcelizeGradleMigration.plan(source, true)
        assertEquals(source.replace("org.jetbrains.kotlin.android.extensions", "kotlin-parcelize"), result.after)
        assertEquals(result.after, ParcelizeGradleMigration.plan(result.after!!, true).after)
    }

    fun testGroovyAndLegacyApply() {
        for (source in listOf("plugins { id 'kotlin-android-extensions' }", "apply plugin: 'kotlin-android-extensions'")) {
            assertEquals(source.replace("kotlin-android-extensions", "kotlin-parcelize"), ParcelizeGradleMigration.plan(source, false).after)
        }
        val source = "apply(plugin = \"kotlin-android-extensions\")"
        assertEquals(source.replace("kotlin-android-extensions", "kotlin-parcelize"), ParcelizeGradleMigration.plan(source, true).after)
        assertEquals("plugins { kotlin(\"parcelize\") }", ParcelizeGradleMigration.plan("plugins { kotlin(\"android.extensions\") }", true).after)
    }

    fun testInsertWithoutChangingCommentsOrLiterals() {
        val source = "// id(\"kotlin-parcelize\")\nplugins {\n    id(\"org.jetbrains.kotlin.android\")\n}\nval example = \"id('kotlin-android-extensions')\""
        val result = ParcelizeGradleMigration.plan(source, true).after!!
        assertTrue(result.contains("    id(\"kotlin-parcelize\")"))
        assertTrue(result.startsWith("// id(\"kotlin-parcelize\")"))
        assertTrue(result.endsWith("val example = \"id('kotlin-android-extensions')\""))
        assertEquals(result, ParcelizeGradleMigration.plan(result, true).after)
    }

    fun testAlreadyEnabledAndRootDeclarations() {
        val source = "plugins { id(\"org.jetbrains.kotlin.plugin.parcelize\") }"
        assertEquals(source, ParcelizeGradleMigration.plan(source, true).after)
        assertNull(ParcelizeGradleMigration.plan("plugins { id(\"kotlin-parcelize\") apply false }", true).after)
        assertNull(ParcelizeGradleMigration.plan("plugins { id(\"kotlin-parcelize\").apply(false) }", true).after)
        assertNull(ParcelizeGradleMigration.plan("plugins { id('kotlin-parcelize'); id('kotlin-android-extensions') }", false).after)
        assertNull(ParcelizeGradleMigration.plan("// no module plugins", true).after)
        val nested = "plugins { id('kotlin-parcelize') }\ncustom { id('kotlin-android-extensions') }"
        assertEquals(nested, ParcelizeGradleMigration.plan(nested, false).after)
    }

    fun testRemovesObsoleteExtensionBlock() {
        val source = "plugins { id('kotlin-android-extensions') }\nandroidExtensions { experimental = true }\nandroid { namespace = 'example' }"
        val result = ParcelizeGradleMigration.plan(source, false).after!!
        assertFalse(result.contains("androidExtensions"))
        assertTrue(result.contains("id('kotlin-parcelize')"))
        assertTrue(result.contains("android { namespace = 'example' }"))
    }

    fun testGroovyInsertionPreservesLineEndings() {
        val source = "plugins { id 'com.android.library' }\r\n"
        val result = ParcelizeGradleMigration.plan(source, false).after!!
        assertTrue(result.contains("\r\n    id 'kotlin-parcelize'\r\n"))
    }

    fun testOpaqueAndIndirectConfigurationsAreNotModified() {
        assertNull(ParcelizeGradleMigration.plan("plugins { alias(libs.plugins.custom) }", true).after)
        assertNull(ParcelizeGradleMigration.plan("plugins {}\ndef pattern = /} id 'kotlin-android-extensions' {/", false).after)
        assertNull(ParcelizeGradleMigration.plan("val plugin = \"kotlin-android-extensions\"", true).after)
        assertNull(ParcelizeGradleMigration.plan("val example = \"kotlin-android\"", true).after)
        assertNull(ParcelizeGradleMigration.plan("plugins { id('kotlin-parcelize')", false).after)
    }

    fun testLegacyApplyRequiresRealDeclaration() {
        val source = "apply plugin: 'kotlin-android'\nandroid { compileSdk 34 }"
        val result = ParcelizeGradleMigration.plan(source, false).after!!
        assertTrue(result.endsWith("apply plugin: 'kotlin-parcelize'\n"))
        assertEquals(result, ParcelizeGradleMigration.plan(result, false).after)
    }
}
