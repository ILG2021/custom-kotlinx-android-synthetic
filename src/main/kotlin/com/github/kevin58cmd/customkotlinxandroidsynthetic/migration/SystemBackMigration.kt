package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

import com.intellij.psi.*
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*

/** Plans dispatcher registration and extracts Activity business handlers when safe. */
object SystemBackMigration {
    data class Location(val line: Int, val code: String)
    data class Result(val before: String, val after: String?, val reason: String, val locations: List<Location> = emptyList())
    private const val MARKER = "// SyntheticCompat system back bridge"
    private const val ACTIVITY = "androidx.activity.ComponentActivity"
    private const val CALLBACK = "androidx.activity.OnBackPressedCallback"
    private const val GUARDED_OVERRIDE = "// SyntheticCompat guarded system back override"
    private val recursionNames = setOf("onBackPressed", "onBackPressedDispatcher", "getOnBackPressedDispatcher")

    fun plan(file: PsiFile): Result? {
        val result = planLegacy(file)
        if (result != null && result.after == null) return result
        return SystemBackHandlerExtraction.plan(file, result)
    }

    /** Plan against the original snapshot: superclass handlers must exist in this batch. */
    fun planFiles(files: List<PsiFile>): Map<PsiFile, Result> {
        val legacy = files.associateWith { planLegacy(it) }
        val owners = files.mapNotNull { file -> SystemBackHandlerExtraction.owner(file)?.let { file to it } }.toMap()
        val activities = files.associateWith { SystemBackHandlerExtraction.activities(it) }
        val ambiguous = owners.values.groupBy { it.qualifiedName }.filterValues { it.size > 1 }.keys
        var handlers = owners.filter { (file, _) ->
            val result = legacy[file]
            (result?.after ?: file.text).contains("// SyntheticCompat system back bridge") &&
                !(result != null && result.after == null) && owners[file]?.qualifiedName !in ambiguous
        }.mapNotNull { (file, owner) -> owner.qualifiedName?.let { it to SystemBackHandlerExtraction.name(file, owner) } }.toMap()
        // All overrides in a connected hierarchy share one collision-free virtual name.
        // This also preserves a parent's toolbar/helper call dispatching into its child
        // before lifecycle callbacks have been registered.
        val pending = handlers.keys.toMutableSet()
        val named = handlers.toMutableMap()
        while (pending.isNotEmpty()) {
            val group = mutableSetOf(pending.first())
            do {
                val before = group.size
                pending.filter { name ->
                    val owner = owners.values.single { it.qualifiedName == name }
                    group.any { member ->
                        val other = owners.values.single { it.qualifiedName == member }
                        owner == other || InheritanceUtil.isInheritor(owner, member) || InheritanceUtil.isInheritor(other, name)
                    }
                }.forEach { group += it }
            } while (group.size != before)
            val inherited = owners.values.filter { it.qualifiedName in group }
                .mapNotNull { SystemBackHandlerExtraction.parentHandler(it, emptyMap()) }.distinct().singleOrNull()
            var helper = inherited ?: "syntheticCompatHandleSystemBack"
            var suffix = 1
            val family = owners.values.filter { it.qualifiedName in group }.flatMap { SystemBackHandlerExtraction.family(it) }.distinct()
            while (inherited == null && family.any { owner ->
                    Regex("\\b${Regex.escape(helper)}\\b").containsMatchIn(owner.navigationElement.containingFile.text) ||
                        owner.findMethodsByName(helper, true).isNotEmpty() }) {
                helper = "syntheticCompatHandleSystemBack" + suffix++
            }
            group.forEach { named[it] = helper }
            pending.removeAll(group)
        }
        handlers = named
        val candidates = handlers.keys
        while (true) {
            val results = files.mapNotNull { file ->
                val old = legacy[file]
                val result = if (owners[file] != null && owners[file]?.qualifiedName in ambiguous) {
                    Result(file.text, null, "存在同名 Activity 类型，无法唯一解析继承关系；请先处理重复类或源码配置。")
                } else if (owners[file]?.qualifiedName in candidates && owners[file]?.qualifiedName !in handlers) {
                    Result(file.text, null, "继承链中的返回方法无法一起安全提取；请先处理同一继承链的人工处理报告。")
                } else if (old != null && old.after == null) old else SystemBackHandlerExtraction.plan(file, old, handlers)
                result?.let { file to it }
            }.toMap()
            var successful = handlers.filter { (name, _) ->
                val file = owners.entries.singleOrNull { it.value.qualifiedName == name }?.key
                results[file]?.after?.contains("// SyntheticCompat extracted system back handler") == true
            }
            // Keep an entire connected source hierarchy together when one level cannot
            // be extracted (edited bridges, key handlers, unresolved types, etc.).
            val blocked = activities.filter { (file, _) ->
                results[file]?.after?.contains("// SyntheticCompat extracted system back handler") != true
            }.values.flatten().filter { owner -> owner.methods.any {
                it.name == "onBackPressed" && it.parameterList.parametersCount == 0 && it.returnType == PsiTypes.voidType()
            } }.toMutableSet()
            do {
                val before = blocked.size
                owners.values.filter { owner -> blocked.any { other ->
                    owner == other || InheritanceUtil.isInheritor(owner, other.qualifiedName ?: "") ||
                        InheritanceUtil.isInheritor(other, owner.qualifiedName ?: "")
                } }.forEach { blocked += it }
            } while (blocked.size != before)
            successful = successful.filterKeys { name -> blocked.none { it.qualifiedName == name } }
            if (successful == handlers) return results
            handlers = successful
        }
    }

    internal fun planLegacy(file: PsiFile): Result? {
        val specialized = SystemBackSpecialCases.plan(file)
        if (specialized.handled) return specialized.result
        return when (file) {
            is PsiJavaFile -> planJava(file)
            is KtFile -> planKotlin(file)
            else -> null
        }
    }

    private fun skip(file: PsiFile, reason: String): Result {
        val elements = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).filter { it.name == "onBackPressed" } +
            PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).filter { it.name == "onBackPressed" } +
            PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java).filter { it.referenceName == "KEYCODE_BACK" } +
            PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java).filter { it.getReferencedName() == "KEYCODE_BACK" }
        val locations = elements.sortedBy { it.textOffset }.map {
            val code = when (it) {
                is PsiReferenceExpression -> PsiTreeUtil.getParentOfType(it, PsiMethod::class.java)?.text ?: it.text
                is KtNameReferenceExpression -> PsiTreeUtil.getParentOfType(it, KtNamedFunction::class.java)?.text ?: it.text
                else -> it.text
            }
            Location(file.text.take(it.textOffset).count { char -> char == '\n' } + 1, code)
        }
        return Result(file.text, null, reason, locations)
    }

    private fun ownerProblem(owner: PsiClass): String? = when {
        owner.name == null || owner is PsiAnonymousClass -> "匿名或未命名类需要人工确认回调所属 Activity。"
        !InheritanceUtil.isInheritor(owner, ACTIVITY) -> "无法解析为 AndroidX ComponentActivity 的子类；请先完成 Gradle Sync 并检查 Activity 基类。"
        JavaPsiFacade.getInstance(owner.project).findClass(CALLBACK, owner.resolveScope) == null -> "未解析到 androidx.activity.OnBackPressedCallback；请检查 AndroidX Activity 依赖。"
        else -> null
    }

    private fun insert(file: PsiFile, offset: Int, registration: String): Result = Result(
        file.text,
        file.text.substring(0, offset) + registration + file.text.substring(offset),
        "在 super.onCreate() 后注册生命周期回调，转发到原 onBackPressed()。原方法及其调用方保持有效；请验证返回行为。"
    )

    private fun javaBridge(name: String, guarded: Boolean, keyHandler: String? = null): String {
        val dispatch = (keyHandler?.let { "if ($name.this.$it(android.view.KeyEvent.KEYCODE_BACK, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))) return;\n                    " } ?: "") + "$name.this.onBackPressed();"
        val forward = if (guarded) "setEnabled(false);\n                try {\n                    $dispatch\n                } finally {\n                    setEnabled(true);\n                }" else dispatch
        return "\n        $MARKER\n        getOnBackPressedDispatcher().addCallback(this, new $CALLBACK(true) {\n            @Override public void handleOnBackPressed() {\n                $forward\n            }\n        });"
    }

    private fun kotlinBridge(name: String, guarded: Boolean): String {
        val forward = if (guarded) "isEnabled = false\n                try {\n                    this@$name.onBackPressed()\n                } finally {\n                    isEnabled = true\n                }" else "this@$name.onBackPressed()"
        return "\n        $MARKER\n        onBackPressedDispatcher.addCallback(this, object : $CALLBACK(true) {\n            override fun handleOnBackPressed() {\n                $forward\n            }\n        })"
    }

    private fun upgradeBridge(file: PsiFile, old: String, guarded: String): Result? {
        if (file.text.contains(guarded)) return null
        if (!file.text.contains(old)) return skip(file, "已有迁移标记，但回调已被编辑；请人工检查重入保护。")
        return Result(file.text, file.text.replaceFirst(old, guarded), "为旧版生成的回调补充重入保护，转发期间禁用自身并在 finally 中恢复。")
    }

    /** Inspect resolvable helper bodies as well as direct calls; do not follow cycles indefinitely. */
    private fun callsBackApi(body: PsiElement, allowed: Set<PsiElement> = emptySet(), visited: MutableSet<PsiElement> = mutableSetOf()): Boolean {
        com.intellij.openapi.progress.ProgressManager.checkCanceled()
        if (!visited.add(body)) return false
        if (PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression::class.java).any { it.referenceName in recursionNames && it !in allowed } ||
            PsiTreeUtil.findChildrenOfType(body, KtNameReferenceExpression::class.java).any { it.getReferencedName() in recursionNames && it !in allowed }) return true
        val targets = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java).filter { it.methodExpression !in allowed }.mapNotNull { it.resolveMethod() } +
            PsiTreeUtil.findChildrenOfType(body, KtCallExpression::class.java).filter { call -> call.calleeExpression?.let { it !in allowed } ?: true }.mapNotNull {
                (it.calleeExpression as? KtNameReferenceExpression)?.mainReference?.resolve()
            }
        return targets.any { target ->
            // Library implementations (finish, fragment transactions, dialog builders, etc.)
            // are not application helpers. Following their entire call graph produces false positives.
            val targetFile = target.navigationElement.containingFile?.virtualFile
            val qualifiedName = (target as? PsiMethod)?.containingClass?.qualifiedName.orEmpty()
            if (qualifiedName.startsWith("android.") || qualifiedName.startsWith("androidx.") ||
                targetFile == null || !ProjectFileIndex.getInstance(body.project).isInSourceContent(targetFile)) return@any false
            val helperBody = when (val declaration = target.navigationElement) {
                is KtNamedFunction -> declaration.bodyExpression
                is PsiMethod -> declaration.body
                else -> null
            }
            helperBody != null && callsBackApi(helperBody, allowed, visited)
        }
    }

    private fun deferred(element: PsiElement, body: PsiElement): Boolean = generateSequence(element.parent) { it.parent }
        .takeWhile { it != body }.any { it is PsiLambdaExpression || it is PsiClass || it is KtLambdaExpression || it is KtNamedFunction || it is KtClassOrObject }

    private fun uniqueName(file: PsiFile, base: String): String {
        var name = base
        var suffix = 1
        while (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(file.text)) name = base + suffix++
        return name
    }

    private fun guardedDefault(file: PsiFile, body: PsiElement, createOffset: Int, memberOffset: Int, name: String, kotlin: Boolean, newCreate: Boolean = false, keyHandler: String? = null): Result {
        val field = uniqueName(file, "syntheticCompatSystemBackCallback")
        val callback = uniqueName(file, "syntheticCompatCurrentCallback")
        val enabled = uniqueName(file, "syntheticCompatWasEnabled")
        val bridge = if (kotlin) kotlinBridge(name, true) else javaBridge(name, true, keyHandler)
        val registration = if (kotlin) {
            bridge.replace("onBackPressedDispatcher.addCallback(this, object", "this.$field = object")
                .removeSuffix(")") + "\n        onBackPressedDispatcher.addCallback(this, this.$field!!)"
        } else {
            bridge.replace("getOnBackPressedDispatcher().addCallback(this, new", "this.$field = new")
                .removeSuffix(");") + ";\n        getOnBackPressedDispatcher().addCallback(this, this.$field);"
        }
        val originalBody = if (kotlin && body !is KtBlockExpression) "{ ${body.text} }" else body.text
        val wrapped = if (kotlin) {
            "{\n        $GUARDED_OVERRIDE\n        val $callback = this.$field\n        val $enabled = $callback?.isEnabled ?: false\n        if ($callback != null) $callback.isEnabled = false\n        try $originalBody finally {\n            if ($callback != null) $callback.isEnabled = $enabled\n        }\n    }"
        } else {
            "{\n        $GUARDED_OVERRIDE\n        $CALLBACK $callback = this.$field;\n        boolean $enabled = $callback != null && $callback.isEnabled();\n        if ($callback != null) $callback.setEnabled(false);\n        try ${body.text} finally {\n            if ($callback != null) $callback.setEnabled($enabled);\n        }\n    }"
        }
        val member = (if (kotlin) "\n    private var $field: $CALLBACK? = null\n" else "\n    private $CALLBACK $field;\n") +
            (if (newCreate) createMethod(registration, kotlin) else "")
        val bodyStart = if (kotlin && body !is KtBlockExpression) (body.parent as? KtNamedFunction)?.equalsToken?.textRange?.startOffset ?: body.textRange.startOffset else body.textRange.startOffset
        val edits = listOf(Triple(bodyStart, body.textRange.endOffset, wrapped), Triple(memberOffset, memberOffset, member)) +
            (if (newCreate) emptyList() else listOf(Triple(createOffset, createOffset, registration)))
        var after = file.text
        edits.sortedByDescending { it.first }.forEach { (start, end, replacement) ->
            after = after.substring(0, start) + replacement + after.substring(end)
        }
        return Result(file.text, after, "保留条件、原方法及 super.onBackPressed()；在原方法和回调转发期间禁用新回调，避免递归与重复执行。")
    }

    private fun createMethod(registration: String, kotlin: Boolean): String = if (kotlin) {
        "\n    override fun onCreate(savedInstanceState: android.os.Bundle?) {\n        super.onCreate(savedInstanceState)$registration\n    }\n"
    } else {
        "\n    @Override public void onCreate(android.os.Bundle savedInstanceState) {\n        super.onCreate(savedInstanceState);$registration\n    }\n"
    }

    internal fun planActivityWithLegacyKeys(file: PsiJavaFile, keyHandler: String? = null): Result? = planJava(file, true, keyHandler)
    internal fun planActivityWithWindowKeys(file: PsiFile): Result? = when (file) {
        is PsiJavaFile -> planJava(file, true)
        is KtFile -> planKotlin(file, true)
        else -> null
    }

    private fun planJava(file: PsiJavaFile, legacyKeys: Boolean = false, keyHandler: String? = null): Result? {
        val methods = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)
        val back = methods.filter { it.name == "onBackPressed" && it.parameterList.parametersCount == 0 && it.body != null }
        val references = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java)
        val keys = references.any { it.referenceName == "KEYCODE_BACK" && !SystemBackSpecialCases.isExclusion(it) }
        if (back.isEmpty() && (!keys || legacyKeys)) return null
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) return skip(file, "源码存在语法错误。")
        if (keys && !legacyKeys) return skip(file, "检测到 KEYCODE_BACK 引用（可能位于 onKeyDown/onKeyUp）。请人工迁移返回分支，保留其他按键逻辑。")
        if (back.size != 1) return skip(file, "发现多个 onBackPressed()，请人工检查继承关系。")
        val method = back.single()
        val owner = method.containingClass ?: return skip(file, "无法确定方法所属 Activity。")
        ownerProblem(owner)?.let { return skip(file, it) }
        if (method.returnType != PsiTypes.voidType() || method.findSuperMethods().isEmpty()) return skip(file, "此方法不是可解析的 Activity.onBackPressed() 重写。")
        val body = method.body ?: return skip(file, "方法缺少方法体。")
        if (body.text.contains(GUARDED_OVERRIDE)) return null
        val hasSuperBackCalls = references.any { it.referenceName == "onBackPressed" && it.qualifierExpression is PsiSuperExpression }
        val defaults = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java).filter {
            it.methodExpression.referenceName == "onBackPressed" && it.methodExpression.qualifierExpression is PsiSuperExpression &&
                it.argumentList.expressions.isEmpty() && it.resolveMethod() != null && !deferred(it, body)
        }.map { it.methodExpression }.toSet()
        if (callsBackApi(body, defaults)) {
            return skip(file, "原返回逻辑或其辅助方法会再次调用返回 API，桥接后可能递归或重复执行；请人工迁移。")
        }
        val creates = owner.methods.filter {
            it.name == "onCreate" && it.parameterList.parametersCount == 1 && it.body != null &&
                it.parameterList.parameters.single().type.canonicalText == "android.os.Bundle" && it.findSuperMethods().isNotEmpty()
        }
        if (creates.isEmpty()) {
            if (references.any { it.referenceName in setOf("addCallback", "registerOnBackInvokedCallback") }) return skip(file, "已有返回回调注册，请人工确认回调优先级。")
            val offset = owner.rBrace?.textRange?.startOffset ?: return skip(file, "无法确定类的插入位置。")
            if (hasSuperBackCalls || hasBackInheritance(owner)) return guardedDefault(file, body, offset, offset, owner.name!!, false, true, keyHandler)
            return insert(file, offset, createMethod(javaBridge(owner.name!!, true, keyHandler), false))
        }
        val create = creates.singleOrNull() ?: return skip(file, "未找到唯一且可解析的 onCreate(Bundle) 重写。")
        val createBody = create.body ?: return skip(file, "onCreate 缺少方法体。")
        if (createBody.text.contains(MARKER)) return upgradeBridge(file, javaBridge(owner.name!!, false), javaBridge(owner.name!!, true))
        if (references.any { it.referenceName in setOf("addCallback", "registerOnBackInvokedCallback") }) return skip(file, "已有返回回调注册，请人工确认回调优先级。")
        val statements = createBody.statements
        val statement = statements.filterIsInstance<PsiExpressionStatement>().singleOrNull {
            val call = it.expression as? PsiMethodCallExpression
            call?.methodExpression?.referenceName == "onCreate" && call.methodExpression.qualifierExpression is PsiSuperExpression
        } ?: return skip(file, "onCreate 中没有唯一的顶层 super.onCreate()，无法安全确定注册时机。")
        if (statements.takeWhile { it != statement }.any { preceding ->
                PsiTreeUtil.findChildOfType(preceding, PsiReturnStatement::class.java) != null || preceding is PsiReturnStatement
            }) return skip(file, "super.onCreate() 前存在提前返回，无法保证注册回调。")
        val call = statement.expression as? PsiMethodCallExpression
            ?: return skip(file, "onCreate 的第一条语句不是 super.onCreate()。")
        if (call.methodExpression.referenceName != "onCreate" || call.methodExpression.qualifierExpression !is PsiSuperExpression || call.resolveMethod() == null) {
            return skip(file, "onCreate 的第一条语句不是可解析的 super.onCreate()。")
        }
        if (hasSuperBackCalls || hasBackInheritance(owner)) return guardedDefault(file, body, statement.textRange.endOffset,
            owner.rBrace?.textRange?.startOffset ?: return skip(file, "无法确定类的插入位置。"), owner.name!!, false, keyHandler = keyHandler)
        return insert(file, statement.textRange.endOffset, javaBridge(owner.name!!, true, keyHandler))
    }

    private fun planKotlin(file: KtFile, legacyKeys: Boolean = false): Result? {
        val functions = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
        val back = functions.filter { it.name == "onBackPressed" && it.valueParameters.isEmpty() }
        val references = PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java)
        val keys = references.any { it.getReferencedName() == "KEYCODE_BACK" && !SystemBackSpecialCases.isExclusion(it) }
        if (back.isEmpty() && (!keys || legacyKeys)) return null
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) return skip(file, "源码存在语法错误。")
        if (keys && !legacyKeys) return skip(file, "检测到 KEYCODE_BACK 引用（可能位于 onKeyDown/onKeyUp）。请人工迁移返回分支，保留其他按键逻辑。")
        if (back.size != 1) return skip(file, "发现多个 onBackPressed()，请人工检查继承关系。")
        val method = back.single()
        val owner = method.parent?.parent as? KtClassOrObject ?: return skip(file, "此方法不是 Activity 的直接成员。")
        val light = owner.toLightClass() ?: return skip(file, "无法解析 Kotlin Activity 类型。")
        ownerProblem(light)?.let { return skip(file, it) }
        if (!method.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return skip(file, "此方法不是 onBackPressed() 重写。")
        val resolvedBack = light.methods.singleOrNull { it.name == "onBackPressed" && it.parameterList.parametersCount == 0 }
        if (resolvedBack == null || resolvedBack.returnType != PsiTypes.voidType() || resolvedBack.findSuperMethods().isEmpty()) return skip(file, "无法解析 Activity.onBackPressed() 重写。")
        val body = method.bodyExpression ?: return skip(file, "方法缺少方法体。")
        if (body.text.contains(GUARDED_OVERRIDE)) return null
        val hasSuperBackCalls = references.any {
            it.getReferencedName() == "onBackPressed" &&
                ((it.parent as? KtCallExpression)?.parent as? KtDotQualifiedExpression)?.receiverExpression is KtSuperExpression
        }
        val defaults = PsiTreeUtil.findChildrenOfType(body, KtCallExpression::class.java).filter {
            val qualified = it.parent as? KtDotQualifiedExpression
            val callee = it.calleeExpression as? KtNameReferenceExpression
            qualified?.receiverExpression is KtSuperExpression && callee?.getReferencedName() == "onBackPressed" &&
                it.valueArguments.isEmpty() && callee.mainReference.resolve() != null && !deferred(it, body)
        }.mapNotNull { it.calleeExpression }.toSet()
        if (callsBackApi(body, defaults)) {
            return skip(file, "原返回逻辑或其辅助方法会再次调用返回 API，桥接后可能递归或重复执行；请人工迁移。")
        }
        val creates = owner.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == "onCreate" && it.valueParameters.size == 1 && it.hasModifier(KtTokens.OVERRIDE_KEYWORD) }
        val name = owner.nameIdentifier?.text ?: return skip(file, "Activity 没有可引用的类名。")
        if (creates.isEmpty()) {
            if (references.any { it.getReferencedName() in setOf("addCallback", "registerOnBackInvokedCallback") }) return skip(file, "已有返回回调注册，请人工确认回调优先级。")
            val offset = owner.body?.rBrace?.textRange?.startOffset ?: return skip(file, "无法确定类的插入位置。")
            if (hasSuperBackCalls || hasBackInheritance(light)) return guardedDefault(file, body, offset, offset, name, true, true)
            return insert(file, offset, createMethod(kotlinBridge(name, true), true))
        }
        val create = creates.singleOrNull() ?: return skip(file, "未找到唯一的 onCreate(Bundle) 重写。")
        val resolvedCreate = light.methods.singleOrNull { it.name == "onCreate" && it.parameterList.parametersCount == 1 }
            ?: return skip(file, "无法解析 onCreate(Bundle) 重写。")
        if (resolvedCreate.parameterList.parameters.single().type.canonicalText != "android.os.Bundle" || resolvedCreate.findSuperMethods().isEmpty()) return skip(file, "无法解析 onCreate(Bundle) 重写。")
        val createBody = create.bodyBlockExpression ?: return skip(file, "onCreate 不是块方法体。")
        if (createBody.text.contains(MARKER)) return upgradeBridge(file, kotlinBridge(name, false), kotlinBridge(name, true))
        if (references.any { it.getReferencedName() in setOf("addCallback", "registerOnBackInvokedCallback") }) return skip(file, "已有返回回调注册，请人工确认回调优先级。")
        val statement = createBody.statements.filterIsInstance<KtDotQualifiedExpression>().singleOrNull {
            it.receiverExpression is KtSuperExpression &&
                ((it.selectorExpression as? KtCallExpression)?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "onCreate"
        } ?: return skip(file, "onCreate 中没有唯一的顶层 super.onCreate()，无法安全确定注册时机。")
        if (createBody.statements.takeWhile { it != statement }.any { preceding ->
                PsiTreeUtil.findChildOfType(preceding, KtReturnExpression::class.java) != null || preceding is KtReturnExpression
            }) return skip(file, "super.onCreate() 前存在提前返回，无法保证注册回调。")
        val call = statement.selectorExpression as? KtCallExpression
        if (statement.receiverExpression !is KtSuperExpression || (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != "onCreate") return skip(file, "onCreate 的第一条语句不是 super.onCreate()。")
        if (hasSuperBackCalls || hasBackInheritance(light)) return guardedDefault(file, body, statement.textRange.endOffset,
            owner.body?.rBrace?.textRange?.startOffset ?: return skip(file, "无法确定类的插入位置。"), name, true)
        return insert(file, statement.textRange.endOffset, kotlinBridge(name, true))
    }

    internal fun hasBackInheritance(owner: PsiClass): Boolean =
        com.intellij.psi.search.searches.ClassInheritorsSearch.search(owner, owner.useScope, true).findFirst() != null ||
            owner.superClass?.let { parent ->
                parent.qualifiedName != ACTIVITY && InheritanceUtil.isInheritor(parent, ACTIVITY) &&
                    parent.findMethodsByName("onBackPressed", true).any {
                        it.containingClass?.qualifiedName?.let { name -> !name.startsWith("android.") && !name.startsWith("androidx.") } == true
                    }
            } == true
}
