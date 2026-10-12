package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.siyeh.ig.psiutils.ControlFlowUtils
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.*

/** Window/view back handlers need a different lifetime and priority from Activity handlers. */
internal object SystemBackSpecialCases {
    data class Plan(val handled: Boolean, val result: SystemBackMigration.Result? = null)
    private const val MARKER = "// SyntheticCompat window back bridge"
    private const val WINDOW_V2 = "$MARKER v2"
    private const val KEY_MARKER = "// SyntheticCompat Activity key bridge"
    private const val KEY_V2 = "$KEY_MARKER v2"

    fun plan(file: PsiFile): Plan {
        val javaKeys = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java).filter { it.referenceName == "KEYCODE_BACK" }
        val kotlinKeys = PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java).filter { it.getReferencedName() == "KEYCODE_BACK" }
        val keys: List<PsiElement> = javaKeys + kotlinKeys
        if (keys.isEmpty()) {
            // A Boolean onBackPressed on a Fragment is an application hook, not a deprecated
            // framework override. The Dialog/window owner that invokes it is migrated separately.
            val javaBack = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).filter { it.name == "onBackPressed" }
            val kotlinBack = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).filter { it.name == "onBackPressed" }
            val hooks = javaBack.map { it.returnType == PsiTypes.booleanType() && it.containingClass?.let { owner -> InheritanceUtil.isInheritor(owner, "androidx.fragment.app.Fragment") } == true } +
                kotlinBack.map { method ->
                    val owner = (method.parent?.parent as? KtClassOrObject)?.toLightClass()
                    owner != null && InheritanceUtil.isInheritor(owner, "androidx.fragment.app.Fragment") &&
                        owner.methods.any { it.name == "onBackPressed" && it.returnType == PsiTypes.booleanType() }
                }
            return Plan(hooks.isNotEmpty() && hooks.all { it })
        }
        if (file.text.contains(WINDOW_V2) || file.text.contains(KEY_V2)) return Plan(true)
        if (file.text.contains(MARKER)) return upgradeWindow(file, keys)
        if (file.text.contains(KEY_MARKER)) return upgradeActivityKey(file, keys)
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) return manual(file, keys, "源码存在语法错误。")
        val active = keys.filterNot(::isExclusion)
        if (active.isEmpty()) return Plan(false) // Ordinary planning still handles an Activity override in this file.
        if (file is PsiJavaFile) activityKeys(file, active)?.let { return it }
        val facade = JavaPsiFacade.getInstance(file.project)
        if (facade.findClass("android.window.OnBackInvokedCallback", file.resolveScope) == null)
            return manual(file, active, "View/Dialog 返回迁移需要 compileSdk 33 或更高；同步 Android SDK 后重试。")
        if (facade.findClass("androidx.activity.ViewTreeOnBackPressedDispatcherOwner", file.resolveScope) == null)
            return manual(file, active, "View/Dialog 返回迁移需要 AndroidX Activity 1.6 或更高（ViewTreeOnBackPressedDispatcherOwner）。")
        return when (file) {
            is PsiJavaFile -> javaWindows(file, active)
            is KtFile -> kotlinListeners(file, active)
            else -> Plan(false)
        }
    }

    private fun upgradeWindow(file: PsiFile, keys: List<PsiElement>): Plan {
        val helper: PsiElement
        val source: String
        when (file) {
            is PsiJavaFile -> {
                val declaration = file.classes.singleOrNull { it.name?.matches(Regex("SyntheticCompat.*JavaWindowBack\\d*")) == true }
                    ?: return manual(file, keys, "旧窗口回调的辅助类已被编辑，无法自动升级。")
                helper = declaration
                source = javaHelper(declaration.name!!)
            }
            is KtFile -> {
                val declaration = file.declarations.filterIsInstance<KtObjectDeclaration>().singleOrNull { it.name?.matches(Regex("SyntheticCompat.*KotlinWindowBack\\d*")) == true }
                    ?: return manual(file, keys, "旧窗口回调的辅助对象已被编辑，无法自动升级。")
                val alias = file.importDirectives.singleOrNull { it.importedFqName?.asString() == "androidx.activity.findViewTreeOnBackPressedDispatcherOwner" }?.aliasName
                    ?: return manual(file, keys, "旧窗口回调的 View owner 导入已被编辑，无法自动升级。")
                helper = declaration
                source = kotlinHelper(declaration.name!!, alias)
            }
            else -> return Plan(false)
        }
        // Only replace a template we recognize exactly; edited helpers remain reviewable skips.
        val legacy = source.lineSequence().filterNot {
            it.trim() in setOf("private boolean disposed;", "private var disposed = false", "if (disposed) return;", "if (disposed) return")
        }.joinToString("\n").replace("disposed || ", "").replace("if (!disposed) handled |=", "handled |=")
            .replace("disposed = true; ", "")
        val actual = helper.text.trim().removePrefix(MARKER).trim()
        val expected = legacy.trim().substringAfter('\n').trim()
        if (actual != expected) return manual(file, keys, "旧窗口回调模板已被编辑，无法自动升级。")
        return changedWindows(file, listOf(Triple(helper.textRange.startOffset, helper.textRange.endOffset, "")), source,
            "升级旧窗口回调：已注销的 Binding 不再重新激活，同时补齐同文件的 Activity 返回迁移。")
    }

    private fun upgradeActivityKey(file: PsiFile, keys: List<PsiElement>): Plan {
        if (file !is PsiJavaFile) return manual(file, keys, "无法识别旧 Activity 按键回调模板。")
        val helpers = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).filter { it.name.startsWith("syntheticCompatHandleBackKey") }
        val helper = helpers.singleOrNull() ?: return manual(file, keys, "旧 Activity 按键辅助方法已被编辑。")
        val owner = helper.containingClass ?: return manual(file, keys, "无法确定旧 Activity 的所属类。")
        val back = owner.methods.singleOrNull { it.name == "onBackPressed" && it.parameterList.parametersCount == 0 }
            ?: return manual(file, keys, "无法识别旧 onBackPressed 重写。")
        val originalCall = PsiTreeUtil.findChildrenOfType(back, PsiMethodCallExpression::class.java).singleOrNull { it.methodExpression.referenceName == helper.name }
            ?: return manual(file, keys, "旧 Activity 的按键转发已被编辑。")
        val branch = originalCall.parent as? PsiIfStatement
            ?: return manual(file, keys, "旧 Activity 的按键条件已被编辑。")
        if ((branch.thenBranch as? PsiReturnStatement)?.returnValue != null || branch.thenBranch !is PsiReturnStatement || branch.elseBranch != null)
            return manual(file, keys, "旧 Activity 的按键条件已被编辑。")
        val forward = PsiTreeUtil.findChildrenOfType(owner, PsiMethodCallExpression::class.java).singleOrNull {
            it.methodExpression.text == "${owner.name}.this.onBackPressed" &&
                PsiTreeUtil.getParentOfType(it, PsiMethod::class.java)?.name == "handleOnBackPressed"
        } ?: return manual(file, keys, "旧 Activity 回调转发已被编辑。")
        val statement = forward.parent as? PsiExpressionStatement ?: return manual(file, keys, "旧 Activity 回调不是独立语句。")
        val prefix = "if (${owner.name}.this.${helper.name}(android.view.KeyEvent.KEYCODE_BACK, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))) return;\n                    "
        val edits = mutableListOf(Triple(branch.textRange.startOffset, branch.textRange.endOffset, ""),
            Triple(statement.textRange.startOffset, statement.textRange.startOffset, prefix))
        val body = helper.body ?: return manual(file, keys, "旧按键辅助方法没有方法体。")
        val last = body.statements.lastOrNull() as? PsiReturnStatement
        val preceding = body.statements.dropLast(1).lastOrNull()
        if (last?.returnValue?.text == "false" && preceding != null && !ControlFlowUtils.statementMayCompleteNormally(preceding))
            edits += Triple(last.textRange.startOffset, last.textRange.endOffset, "")
        val marker = file.text.indexOf(KEY_MARKER)
        edits += Triple(marker, marker + KEY_MARKER.length, KEY_V2)
        return changed(file, edits, "升级旧 Activity 按键回调：仅新 dispatcher 入口执行提取分支，保留旧按键及直接返回调用的原有行为，并修复不可达语句。")
    }

    internal fun isExclusion(key: PsiElement): Boolean {
        val binary = key.parent as? PsiBinaryExpression ?: key.parent?.parent as? PsiBinaryExpression
        val ktBinary = key.parent as? KtBinaryExpression ?: key.parent?.parent as? KtBinaryExpression
        if (binary?.operationTokenType == JavaTokenType.NE) {
            if (binary.parent is PsiReturnStatement) return true
            val statement = PsiTreeUtil.getParentOfType(binary, PsiIfStatement::class.java) ?: return false
            if (!PsiTreeUtil.isAncestor(statement.condition, key, false)) return false
            if (generateSequence(binary.parent) { it.parent }.takeWhile { it != statement.condition && it != statement }.any {
                    it !is PsiParenthesizedExpression && (it !is PsiBinaryExpression || it.operationTokenType != JavaTokenType.ANDAND)
                } || (statement.condition as? PsiBinaryExpression)?.let { it != binary && it.operationTokenType != JavaTokenType.ANDAND } == true) return false
            if ((statement.elseBranch as? PsiReturnStatement)?.returnValue?.text == "false") return true
            val statements = (statement.parent as? PsiCodeBlock)?.statements ?: return false
            val next = statements.getOrNull(statements.indexOf(statement) + 1)
            return (next as? PsiReturnStatement)?.returnValue?.text == "false"
        }
        if (ktBinary?.operationReference?.text == "!=") {
            if (ktBinary.parent is KtReturnExpression) return true
            val statement = PsiTreeUtil.getParentOfType(ktBinary, KtIfExpression::class.java) ?: return false
            if (!PsiTreeUtil.isAncestor(statement.condition, key, false)) return false
            if (generateSequence(ktBinary.parent) { it.parent }.takeWhile { it != statement.condition && it != statement }.any {
                    it !is KtParenthesizedExpression && (it !is KtBinaryExpression || it.operationReference.text != "&&")
                } || (statement.condition as? KtBinaryExpression)?.let { it != ktBinary && it.operationReference.text != "&&" } == true) return false
            if ((statement.`else` as? KtReturnExpression)?.returnedExpression?.text == "false") return true
            val statements = (statement.parent as? KtBlockExpression)?.statements ?: return false
            return (statements.getOrNull(statements.indexOf(statement) + 1) as? KtReturnExpression)?.returnedExpression?.text == "false"
        }
        return false
    }

    private fun manual(file: PsiFile, keys: List<PsiElement>, reason: String): Plan = Plan(true,
        SystemBackMigration.Result(file.text, null, "$reason（KEYCODE_BACK）", keys.map { key ->
            val method = PsiTreeUtil.getParentOfType(key, PsiMethod::class.java)
            val function = PsiTreeUtil.getParentOfType(key, KtNamedFunction::class.java)
            SystemBackMigration.Location(file.text.take(key.textOffset).count { it == '\n' } + 1, method?.text ?: function?.text ?: key.text)
        }))

    private fun name(file: PsiFile, base: String): String {
        var result = base
        var count = 1
        while (Regex("\\b${Regex.escape(result)}\\b").containsMatchIn(file.text)) result = base + count++
        return result
    }

    private fun changed(file: PsiFile, edits: List<Triple<Int, Int, String>>, reason: String): Plan {
        var after = file.text
        edits.sortedByDescending { it.first }.forEach { (start, end, text) -> after = after.substring(0, start) + text + after.substring(end) }
        return Plan(true, SystemBackMigration.Result(file.text, after, reason))
    }

    private fun activityKeys(file: PsiJavaFile, keys: List<PsiElement>): Plan? {
        val method = PsiTreeUtil.getParentOfType(keys.first(), PsiMethod::class.java) ?: return null
        val owner = method.containingClass ?: return null
        if (!InheritanceUtil.isInheritor(owner, "androidx.activity.ComponentActivity")) return null
        if (method.name != "onKeyDown" || method.parameterList.parametersCount != 2 || method.returnType != PsiTypes.booleanType()) return null
        if (keys.any { !PsiTreeUtil.isAncestor(method, it, false) }) return manual(file, keys, "Activity 中还有其他返回按键入口，需要分别确认处理顺序。")
        val body = method.body ?: return null
        val branch = body.statements.filterIsInstance<PsiIfStatement>().singleOrNull { statement ->
            val condition = statement.condition as? PsiBinaryExpression
            condition?.operationTokenType == JavaTokenType.EQEQ && keys.all { PsiTreeUtil.isAncestor(condition, it, false) }
        } ?: return manual(file, keys, "onKeyDown 的返回判断不是独立的顶层相等分支，无法安全提取。")
        if (branch.elseBranch != null) return manual(file, keys, "返回按键分支带有 else，需要确认其他按键行为。")
        val parameters = method.parameterList.parameters
        val comparison = branch.condition as PsiBinaryExpression
        if (listOfNotNull(comparison.lOperand, comparison.rOperand).none { (it as? PsiReferenceExpression)?.resolve() == parameters[0] })
            return manual(file, keys, "返回比较的操作数不是原始 keyCode 参数，无法安全提取。")
        val helper = name(file, "syntheticCompatHandleBackKey")
        val inner = branch.thenBranch ?: return null
        if (PsiTreeUtil.findChildrenOfType(inner, PsiReferenceExpression::class.java).any { reference ->
                val target = reference.resolve()
                (target is PsiLocalVariable || target is PsiParameter) && target !in parameters && !PsiTreeUtil.isAncestor(inner, target, false)
            } || PsiTreeUtil.findChildOfType(inner, PsiBreakStatement::class.java) != null ||
            PsiTreeUtil.findChildOfType(inner, PsiContinueStatement::class.java) != null)
            return manual(file, keys, "返回分支引用了外部局部变量或循环跳转，无法独立提取。")
        val content = if (inner is PsiBlockStatement) inner.codeBlock.text.removePrefix("{").removeSuffix("}") else inner.text
        // The old key path and toolbar callers retain their original behavior. Only the new
        // dispatcher callback invokes this extracted branch, before the original back override.
        val tail = if (ControlFlowUtils.statementMayCompleteNormally(inner)) "\n        return false;" else ""
        val member = "\n    $KEY_V2\n    private boolean $helper(int ${parameters[0].name}, android.view.KeyEvent ${parameters[1].name}) {\n        $content$tail\n    }\n"
        val existing = owner.methods.singleOrNull { it.name == "onBackPressed" && it.parameterList.parametersCount == 0 }
        val offset = owner.rBrace?.textOffset ?: return null
        val edits = mutableListOf(Triple(offset, offset, member))
        if (existing == null) edits += Triple(offset, offset, "\n    @Override public void onBackPressed() {\n        super.onBackPressed();\n    }\n")
        val intermediate = changed(file, edits, "提取返回按键分支。").result!!.after!!
        // Plan the ordinary Activity bridge on a nonphysical copy after extracting the branch.
        // The original KEYCODE_BACK method remains intentionally supported as a legacy entry.
        val copy = PsiFileFactory.getInstance(file.project).createFileFromText(file.name, file.fileType, intermediate) as PsiJavaFile
        val activity = SystemBackMigration.planActivityWithLegacyKeys(copy, helper)
        return if (activity?.after != null) Plan(true, SystemBackMigration.Result(file.text, activity.after,
            "保留其他按键及旧入口；将 BACK 分支接入带重入保护的 Activity 返回回调。请验证编辑焦点和未保存提示。"))
        else manual(file, keys, activity?.reason ?: "无法解析 Activity 返回回调。")
    }

    private fun javaWindows(file: PsiJavaFile, keys: List<PsiElement>): Plan {
        val helper = name(file, "SyntheticCompat${file.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_]"), "_")}JavaWindowBack")
        val edits = mutableListOf<Triple<Int, Int, String>>()
        val covered = mutableSetOf<PsiElement>()
        val methods = keys.mapNotNull { PsiTreeUtil.getParentOfType(it, PsiMethod::class.java) }.distinct()
        if (methods.filter { it.name in setOf("onKeyPreIme", "dispatchKeyEventPreIme") }.groupBy { it.containingClass }.any { it.value.size > 1 })
            return manual(file, keys, "同一 View 同时重写多个 PreIme 入口，需要确认调用关系以避免重复处理。")
        for (method in methods) {
            val owner = method.containingClass ?: continue
            if (method.name in setOf("onKeyPreIme", "dispatchKeyEventPreIme") && InheritanceUtil.isInheritor(owner, "android.view.View")) {
                if (owner.name == null) return manual(file, keys, "匿名 View 需要确认回调接收者。")
                val invoke = if (method.name == "onKeyPreIme" && method.parameterList.parametersCount == 2) "${owner.name}.this.onKeyPreIme(code, event)"
                    else if (method.name == "dispatchKeyEventPreIme" && method.parameterList.parametersCount == 1) "${owner.name}.this.dispatchKeyEventPreIme(event)"
                    else continue
                val registration = "\n        $MARKER\n        $helper.bind(this, (view, code, event) -> $invoke, true, null);"
                val attach = owner.methods.singleOrNull { it.name == "onAttachedToWindow" && it.parameterList.parametersCount == 0 }
                if (attach == null) {
                    val offset = owner.rBrace?.textOffset ?: continue
                    edits += Triple(offset, offset, "\n    @Override protected void onAttachedToWindow() {\n        super.onAttachedToWindow();$registration\n    }\n")
                } else {
                    val superCall = attach.body?.statements?.filterIsInstance<PsiExpressionStatement>()?.singleOrNull {
                        val call = it.expression as? PsiMethodCallExpression
                        call?.methodExpression?.referenceName == "onAttachedToWindow" && call.methodExpression.qualifierExpression is PsiSuperExpression
                    } ?: return manual(file, keys, "onAttachedToWindow 中没有唯一顶层 super 调用。")
                    edits += Triple(superCall.textRange.endOffset, superCall.textRange.endOffset, registration)
                }
                covered += keys.filter { PsiTreeUtil.isAncestor(method, it, false) }
            }
        }
        val calls = PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java).filter {
            it.methodExpression.referenceName in setOf("setOnKeyListener") && it.argumentList.expressions.size == 1 &&
                keys.any { key -> PsiTreeUtil.isAncestor(it, key, false) }
        }
        for (call in calls) {
            val expression = call.argumentList.expressions.single()
            val receiver = call.methodExpression.qualifierExpression ?: return manual(file, keys, "按键监听器没有明确接收者。")
            val statement = call.parent as? PsiExpressionStatement ?: return manual(file, keys, "按键监听器不在独立语句中。")
            val listenerType = call.resolveMethod()?.parameterList?.parameters?.singleOrNull()?.type?.canonicalText
            val dialog = listenerType == "android.content.DialogInterface.OnKeyListener"
            if (listenerType !in setOf("android.content.DialogInterface.OnKeyListener", "android.view.View.OnKeyListener"))
                return manual(file, keys, "无法解析按键监听器类型，请先完成 Gradle Sync。")
            val listener = name(file, "syntheticCompatKeyListener${edits.size}")
            val target = name(file, "syntheticCompatKeyTarget${edits.size}")
            val targetType = if (dialog) "android.app.Dialog" else "android.view.View"
            val bind = if (dialog) "$helper.bind($target.getWindow().getDecorView(), (view, code, event) -> $listener.onKey($target, code, event), false, () -> $target.onBackPressed());"
                else "$helper.bind($target, $listener, false, null);"
            edits += Triple(statement.textRange.startOffset, statement.textRange.endOffset,
                "$MARKER\n        final $targetType $target = ${receiver.text};\n        final $listenerType $listener = ${expression.text};\n        $target.setOnKeyListener($listener);\n        $bind")
            covered += keys.filter { PsiTreeUtil.isAncestor(call, it, false) }
        }
        if (!covered.containsAll(keys)) return manual(file, keys, "返回引用位于尚未支持的入口；支持 View PreIme、独立 setOnKeyListener 和 Activity onKeyDown。")
        return changedWindows(file, edits, javaHelper(helper), "按窗口和焦点注册新返回回调；PreIme 使用覆盖优先级，保留旧按键入口；脱离窗口时注销。Dialog 保留自己的返回处理和 Fragment 自定义接口。需要 compileSdk 33+。")
    }

    private fun kotlinListeners(file: KtFile, keys: List<PsiElement>): Plan {
        val calls = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).filter {
            (it.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "setOnKeyListener" &&
                keys.any { key -> PsiTreeUtil.isAncestor(it, key, false) }
        }
        val helper = name(file, "SyntheticCompat${file.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_]"), "_")}KotlinWindowBack")
        val edits = mutableListOf<Triple<Int, Int, String>>()
        val covered = mutableSetOf<PsiElement>()
        for (call in calls) {
            val qualified = call.parent as? KtDotQualifiedExpression ?: return manual(file, keys, "监听器没有明确 View 接收者。")
            if (qualified.parent !is KtBlockExpression) return manual(file, keys, "监听器不是独立的块语句。")
            val lambda = call.lambdaArguments.singleOrNull()?.getLambdaExpression() ?: return manual(file, keys, "仅支持尾随 lambda 形式的 View 按键监听器。")
            val binding = name(file, "syntheticCompatKeyListener${edits.size}")
            val target = name(file, "syntheticCompatKeyTarget${edits.size}")
            // Keep return@setOnKeyListener labels by introducing an explicit identical lambda label.
            val source = lambda.text.replace("return@setOnKeyListener", "return@$binding")
            edits += Triple(qualified.textRange.startOffset, qualified.textRange.endOffset,
                "$MARKER\n        val $target = ${qualified.receiverExpression.text}\n        val $binding = android.view.View.OnKeyListener $binding@$source\n        $target.setOnKeyListener($binding)\n        $helper.bind($target, $binding)")
            covered += keys.filter { PsiTreeUtil.isAncestor(call, it, false) }
        }
        if (!covered.containsAll(keys)) return manual(file, keys, "Kotlin 返回引用不在独立的 View.setOnKeyListener lambda 中，需确认回调所属窗口。")
        val alias = name(file, "syntheticCompatFindBackOwner")
        val imports = file.importList?.textRange?.endOffset ?: file.packageDirective?.textRange?.endOffset ?: 0
        edits += Triple(imports, imports, "\nimport androidx.activity.findViewTreeOnBackPressedDispatcherOwner as $alias\n")
        return changedWindows(file, edits, kotlinHelper(helper, alias), "View 按键监听器接入窗口返回回调；按焦点启停、脱离窗口注销，重复绑定替换旧监听器；保留非返回按键和原 lambda 的返回标签。需要 compileSdk 33+。")
    }

    private fun changedWindows(file: PsiFile, edits: List<Triple<Int, Int, String>>, helper: String, reason: String): Plan {
        val intermediate = changed(file, edits, reason).result!!.after!!
        // Plan the Activity on the same intermediate source before appending the helper's
        // platform registration implementation; never let a View migration hide an Activity hook.
        val copy = if (file is KtFile) KtPsiFactory(file.project).createAnalyzableFile(file.name, intermediate, file)
            else PsiFileFactory.getInstance(file.project).createFileFromText(file.name, file.fileType, intermediate)
        val hasActivity = PsiTreeUtil.findChildrenOfType(copy, PsiMethod::class.java).any {
            it.name == "onBackPressed" && it.parameterList.parametersCount == 0 &&
                it.containingClass?.let { owner -> InheritanceUtil.isInheritor(owner, "androidx.activity.ComponentActivity") } == true
        } || PsiTreeUtil.findChildrenOfType(copy, KtNamedFunction::class.java).any {
            it.name == "onBackPressed" && it.valueParameters.isEmpty() &&
                (it.parent?.parent as? KtClassOrObject)?.toLightClass()?.let { owner -> InheritanceUtil.isInheritor(owner, "androidx.activity.ComponentActivity") } == true
        }
        val activity = if (hasActivity) SystemBackMigration.planActivityWithWindowKeys(copy) else null
        if (activity != null && activity.after == null) return Plan(true, activity.copy(before = file.text))
        val after = (activity?.after ?: intermediate) + helper
        return Plan(true, SystemBackMigration.Result(file.text, after, reason + (if (activity?.after != null) " 同时迁移 Activity 返回重写。" else "")))
    }

    private fun javaHelper(name: String): String = """

        $WINDOW_V2
        final class $name {
            private static final java.util.WeakHashMap<android.view.View, java.lang.ref.WeakReference<Binding>> bindings = new java.util.WeakHashMap<>();
            static void bind(android.view.View view, android.view.View.OnKeyListener listener, boolean preIme, Runnable fallback) {
                java.lang.ref.WeakReference<Binding> ref = bindings.get(view);
                Binding old = ref == null ? null : ref.get();
                if (old != null) old.dispose();
                Binding binding = new Binding(view, listener, preIme, fallback);
                bindings.put(view, new java.lang.ref.WeakReference<>(binding));
                view.addOnAttachStateChangeListener(binding);
                if (view.isAttachedToWindow()) binding.onViewAttachedToWindow(view);
            }
            private static androidx.activity.OnBackPressedDispatcherOwner owner(android.view.View view) {
                androidx.activity.OnBackPressedDispatcherOwner owner = androidx.activity.ViewTreeOnBackPressedDispatcherOwner.get(view);
                if (owner != null) return owner;
                android.content.Context context = view.getContext();
                while (context instanceof android.content.ContextWrapper) {
                    if (context instanceof androidx.activity.OnBackPressedDispatcherOwner) return (androidx.activity.OnBackPressedDispatcherOwner) context;
                    android.content.Context base = ((android.content.ContextWrapper) context).getBaseContext();
                    if (base == context) break;
                    context = base;
                }
                return context instanceof androidx.activity.OnBackPressedDispatcherOwner ? (androidx.activity.OnBackPressedDispatcherOwner) context : null;
            }
            private static final class Binding implements android.view.View.OnAttachStateChangeListener, android.view.ViewTreeObserver.OnGlobalFocusChangeListener {
                private final android.view.View view;
                private final android.view.View.OnKeyListener listener;
                private final boolean preIme;
                private final Runnable fallback;
                private android.view.ViewTreeObserver observer;
                private Api33 registration;
                private boolean disposed;
                Binding(android.view.View view, android.view.View.OnKeyListener listener, boolean preIme, Runnable fallback) {
                    this.view = view; this.listener = listener; this.preIme = preIme; this.fallback = fallback;
                }
                public void onViewAttachedToWindow(android.view.View v) {
                    if (disposed || observer != null) return;
                    observer = view.getViewTreeObserver();
                    observer.addOnGlobalFocusChangeListener(this);
                    update();
                }
                public void onViewDetachedFromWindow(android.view.View v) {
                    if (observer != null && observer.isAlive()) observer.removeOnGlobalFocusChangeListener(this);
                    observer = null;
                    if (registration != null) { registration.remove(); registration = null; }
                }
                public void onGlobalFocusChanged(android.view.View oldFocus, android.view.View newFocus) { update(); }
                private void update() {
                    if (disposed || android.os.Build.VERSION.SDK_INT < 33) return;
                    boolean active = view.isAttachedToWindow() && view.hasFocus() && view.isShown() && (fallback != null || owner(view) != null);
                    if (active && registration == null) registration = Api33.install(view, this::back, preIme);
                    else if (!active && registration != null) { registration.remove(); registration = null; }
                }
                private void back() {
                    if (disposed) return;
                    if (registration != null) { registration.remove(); registration = null; }
                    android.view.WindowInsets insets = view.getRootWindowInsets();
                    boolean imeVisible = preIme && insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
                    try {
                        boolean handled = listener.onKey(view, android.view.KeyEvent.KEYCODE_BACK, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                        if (!disposed) handled |= listener.onKey(view, android.view.KeyEvent.KEYCODE_BACK, new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
                        if (disposed) return;
                        if (!handled) {
                            android.view.inputmethod.InputMethodManager ime = (android.view.inputmethod.InputMethodManager) view.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                            if (imeVisible) {
                                if (ime != null) ime.hideSoftInputFromWindow(view.getWindowToken(), 0);
                            } else if (fallback != null) fallback.run();
                            else {
                                androidx.activity.OnBackPressedDispatcherOwner owner = owner(view);
                                if (owner != null) owner.getOnBackPressedDispatcher().onBackPressed();
                            }
                        }
                    } finally { update(); }
                }
                void dispose() { disposed = true; onViewDetachedFromWindow(view); view.removeOnAttachStateChangeListener(this); }
            }
            @android.annotation.TargetApi(33)
            private static final class Api33 {
                private final android.window.OnBackInvokedDispatcher dispatcher;
                private final android.window.OnBackInvokedCallback callback;
                private Api33(android.window.OnBackInvokedDispatcher dispatcher, android.window.OnBackInvokedCallback callback) { this.dispatcher = dispatcher; this.callback = callback; }
                static Api33 install(android.view.View view, Runnable action, boolean preIme) {
                    android.window.OnBackInvokedDispatcher dispatcher = view.findOnBackInvokedDispatcher();
                    if (dispatcher == null) return null;
                    android.window.OnBackInvokedCallback callback = action::run;
                    dispatcher.registerOnBackInvokedCallback(preIme ? android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY : android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
                    return new Api33(dispatcher, callback);
                }
                void remove() { dispatcher.unregisterOnBackInvokedCallback(callback); }
            }
        }
    """.trimIndent() + "\n"

    private fun kotlinHelper(name: String, alias: String): String = """

        $WINDOW_V2
        private object $name {
            private val bindings = java.util.WeakHashMap<android.view.View, java.lang.ref.WeakReference<Binding>>()
            fun bind(view: android.view.View, listener: android.view.View.OnKeyListener) {
                bindings[view]?.get()?.dispose()
                val binding = Binding(view, listener)
                bindings[view] = java.lang.ref.WeakReference(binding)
                view.addOnAttachStateChangeListener(binding)
                if (view.isAttachedToWindow) binding.onViewAttachedToWindow(view)
            }
            private fun owner(view: android.view.View): androidx.activity.OnBackPressedDispatcherOwner? {
                view.$alias()?.let { return it }
                var context = view.context
                while (context is android.content.ContextWrapper) {
                    if (context is androidx.activity.OnBackPressedDispatcherOwner) return context
                    val base = context.baseContext
                    if (base === context) break
                    context = base
                }
                return context as? androidx.activity.OnBackPressedDispatcherOwner
            }
            private class Binding(val view: android.view.View, val listener: android.view.View.OnKeyListener) : android.view.View.OnAttachStateChangeListener, android.view.ViewTreeObserver.OnGlobalFocusChangeListener {
                private var observer: android.view.ViewTreeObserver? = null
                private var registration: Api33? = null
                private var disposed = false
                override fun onViewAttachedToWindow(v: android.view.View) {
                    if (disposed || observer != null) return
                    observer = view.viewTreeObserver
                    observer?.addOnGlobalFocusChangeListener(this)
                    update()
                }
                override fun onViewDetachedFromWindow(v: android.view.View) {
                    observer?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(this)
                    observer = null
                    registration?.remove()
                    registration = null
                }
                override fun onGlobalFocusChanged(oldFocus: android.view.View?, newFocus: android.view.View?) { update() }
                private fun update() {
                    if (disposed || android.os.Build.VERSION.SDK_INT < 33) return
                    val active = view.isAttachedToWindow && view.hasFocus() && view.isShown && owner(view) != null
                    if (active && registration == null) registration = Api33.install(view) { back() }
                    else if (!active) { registration?.remove(); registration = null }
                }
                private fun back() {
                    if (disposed) return
                    registration?.remove()
                    registration = null
                    try {
                        val down = listener.onKey(view, android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
                        if (disposed) return
                        val up = listener.onKey(view, android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
                        if (disposed) return
                        if (!down && !up) owner(view)?.onBackPressedDispatcher?.onBackPressed()
                    } finally { update() }
                }
                fun dispose() { disposed = true; onViewDetachedFromWindow(view); view.removeOnAttachStateChangeListener(this) }
            }
            @android.annotation.TargetApi(33)
            private class Api33(val dispatcher: android.window.OnBackInvokedDispatcher, val callback: android.window.OnBackInvokedCallback) {
                fun remove() { dispatcher.unregisterOnBackInvokedCallback(callback) }
                companion object {
                    fun install(view: android.view.View, action: () -> Unit): Api33? {
                        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
                        val callback = android.window.OnBackInvokedCallback { action() }
                        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                        return Api33(dispatcher, callback)
                    }
                }
            }
        }
    """.trimIndent() + "\n"
}
