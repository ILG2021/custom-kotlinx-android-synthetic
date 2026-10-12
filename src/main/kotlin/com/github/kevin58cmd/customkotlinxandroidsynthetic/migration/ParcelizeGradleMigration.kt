package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

/** Small lexer: plugin declarations are inspected without matching comments or string contents. */
object ParcelizeGradleMigration {
    data class Result(val before: String, val after: String?, val reason: String)
    private data class Token(val value: String, val start: Int, val end: Int, val string: Boolean = false)
    private val legacy = setOf("kotlin-android-extensions", "org.jetbrains.kotlin.android.extensions")
    private val modern = setOf("kotlin-parcelize", "org.jetbrains.kotlin.plugin.parcelize")

    fun plan(text: String, kotlinDsl: Boolean): Result {
        val tokens = lex(text) ?: return Result(text, null, "构建脚本包含无法识别的字符串或注释，请检查配置。")
        val edits = mutableListOf<Triple<Int, Int, String>>()
        var depth = 0
        var pluginOpen: Token? = null
        var pluginDepth = -1
        var enabled = false
        var declarationsOnly = false
        var legacyEnabled = false
        var modernEnabled = false
        var kotlinApplied = false
        for (i in tokens.indices) {
            val token = tokens[i]
            if (depth == 0 && !token.string && token.value == "androidExtensions" && tokens.getOrNull(i + 1)?.value == "{") {
                var blockDepth = 0
                var end: Int? = null
                for (next in tokens.drop(i + 1)) {
                    if (!next.string && next.value == "{") blockDepth++
                    if (!next.string && next.value == "}") {
                        blockDepth--
                        if (blockDepth == 0) { end = next.end; break }
                    }
                }
                if (end == null) return Result(text, null, "androidExtensions 块括号不完整。")
                edits += Triple(token.start, end, "")
            }
            if (!token.string && token.value == "{") {
                if (depth == 0 && tokens.getOrNull(i - 1)?.value == "plugins") {
                    if (pluginOpen != null) return Result(text, null, "发现多个 plugins 块，请检查配置。")
                    pluginOpen = token
                    pluginDepth = depth + 1
                }
                depth++
            } else if (!token.string && token.value == "}") {
                if (depth == pluginDepth) pluginDepth = -1
                depth--
            }
            if (depth < 0) return Result(text, null, "构建脚本括号不完整。")
            if (!token.string && token.value == "alias" && depth == pluginDepth) {
                return Result(text, null, "使用版本目录插件别名，无法仅凭别名确认是否已启用 Parcelize/Extensions，请检查版本目录配置。")
            }
            if (!token.string) continue
            val previous = tokens.getOrNull(i - 1)?.value
            val previous2 = tokens.getOrNull(i - 2)?.value
            val inPlugins = depth == pluginDepth && pluginOpen != null
            val id = inPlugins && (previous == "id" || previous == "(" && previous2 == "id")
            val kotlin = inPlugins && previous == "(" && previous2 == "kotlin"
            val apply = depth == 0 && (previous == ":" && previous2 == "plugin" && tokens.getOrNull(i - 3)?.value == "apply" ||
                previous == "=" && previous2 == "plugin" && tokens.getOrNull(i - 3)?.value == "(" && tokens.getOrNull(i - 4)?.value == "apply")
            if (apply && token.value in setOf("kotlin-android", "org.jetbrains.kotlin.android")) kotlinApplied = true
            if (!id && !apply && !kotlin) continue
            val legacyId = token.value in legacy || kotlin && token.value == "android.extensions"
            val modernId = token.value in modern || kotlin && token.value == "parcelize"
            if (!legacyId && !modernId) continue
            val tail = tokens.drop(i + 1).takeWhile { next ->
                next.value !in setOf("}", ";", "id", "kotlin", "alias")
            }
            val applyFalse = tail.filter { !it.string && it.value !in setOf("(", ")", ".") }
                .windowed(2).any { it[0].value == "apply" && it[1].value == "false" }
            if (applyFalse) { declarationsOnly = true; continue }
            if (modernId) { enabled = true; modernEnabled = true }
            if (legacyId) {
                // Replacing a versioned legacy declaration preserves its Kotlin version.
                edits += Triple(token.start + 1, token.end - 1, if (kotlin) "parcelize" else "kotlin-parcelize")
                enabled = true
                legacyEnabled = true
            }
        }
        if (depth != 0) return Result(text, null, "构建脚本括号不完整。")
        if (legacyEnabled && modernEnabled) return Result(text, null, "旧插件和 Parcelize 同时启用，请先移除重复的 Android Extensions 配置。")
        if (declarationsOnly) return Result(text, null, "发现 apply false 的 Parcelize/Android Extensions 声明，请在实际模块中启用。")
        if (!enabled) {
            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            val declaration = if (kotlinDsl) "id(\"kotlin-parcelize\")" else "id 'kotlin-parcelize'"
            if (pluginOpen != null) {
                edits += Triple(pluginOpen.end, pluginOpen.end, "$newline    $declaration$newline")
            } else {
                // Legacy apply scripts already load Kotlin via their buildscript classpath.
                if (!kotlinApplied) return Result(text, null, "未找到 plugins 块或 Kotlin Android 配置，请检查 convention plugin 配置。")
                val declarationApply = if (kotlinDsl) "apply(plugin = \"kotlin-parcelize\")" else "apply plugin: 'kotlin-parcelize'"
                edits += Triple(text.length, text.length, "$newline$declarationApply$newline")
            }
        }
        val after = StringBuilder(text)
        edits.sortedByDescending { it.first }.forEach { (start, end, replacement) -> after.replace(start, end, replacement) }
        return Result(text, after.toString(), "自动启用 kotlin-parcelize，并替换直接声明的 kotlin-android-extensions。应用后执行 Gradle Sync 并编译。")
    }

    private fun lex(text: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val start = i
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("//", i) -> { i = text.indexOf('\n', i).let { if (it < 0) text.length else it } }
                text.startsWith("/*", i) -> {
                    var nesting = 1
                    i += 2
                    while (i < text.length && nesting > 0) {
                        when {
                            text.startsWith("/*", i) -> { nesting++; i += 2 }
                            text.startsWith("*/", i) -> { nesting--; i += 2 }
                            else -> i++
                        }
                    }
                    if (nesting != 0) return null
                }
                // Groovy slashy strings can contain braces, quotes and plugin-looking text.
                // Without a Groovy parser, leave ambiguous scripts unchanged.
                text[i] == '/' -> return null
                text[i] == '\'' || text[i] == '"' -> {
                    val quote = text[i]
                    val triple = text.startsWith("$quote$quote$quote", i)
                    val delimiter = if (triple) "$quote$quote$quote" else "$quote"
                    i += delimiter.length
                    val content = i
                    while (i < text.length && !text.startsWith(delimiter, i)) {
                        if (!triple && text[i] == '\\') i += 2 else i++
                    }
                    if (i >= text.length) return null
                    val value = text.substring(content, i)
                    i += delimiter.length
                    // Multiline literals are opaque and cannot declare plugin IDs.
                    tokens += Token(if (triple) "" else value, start, i, true)
                }
                text[i].isLetterOrDigit() || text[i] == '_' -> {
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "_-")) i++
                    tokens += Token(text.substring(start, i), start, i)
                }
                else -> { i++; tokens += Token(text.substring(start, i), start, i) }
            }
        }
        return tokens
    }
}
