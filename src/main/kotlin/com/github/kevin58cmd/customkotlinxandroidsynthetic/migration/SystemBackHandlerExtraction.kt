package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/** Retains the legacy planner's validation and fallback guards, then removes the framework override. */
internal object SystemBackHandlerExtraction {
    private const val BRIDGE = "// SyntheticCompat system back bridge"
    private const val HANDLER = "// SyntheticCompat extracted system back handler"
    private const val ACTIVITY = "androidx.activity.ComponentActivity"
    private data class Edit(val start: Int, val end: Int, val text: String)

    fun plan(original: PsiFile, legacy: SystemBackMigration.Result?): SystemBackMigration.Result? {
        val source = legacy?.after ?: original.text
        if (!source.contains(BRIDGE) || source.contains(HANDLER)) return legacy
        // The legacy key path still invokes Activity.onBackPressed after onKeyDown. Removing
        // that override would feed it into the extracted key branch for a second time.
        if (source.contains("// SyntheticCompat Activity key bridge")) return legacy
        val file = if (source == original.text) original else when (original) {
            is KtFile -> KtPsiFactory(original.project).createAnalyzableFile(original.name, source, original)
            else -> PsiFileFactory.getInstance(original.project).createFileFromText(original.name, original.fileType, source)
        }
        return when (file) {
            is PsiJavaFile -> java(original, file, legacy)
            is KtFile -> kotlin(original, file, legacy)
            else -> legacy
        }
    }

    private fun apply(source: String, edits: List<Edit>): String {
        var result = source
        edits.sortedByDescending { it.start }.forEach { edit -> result = result.substring(0, edit.start) + edit.text + result.substring(edit.end) }
        return result
    }

    private fun name(file: PsiFile, owner: PsiClass): String {
        val base = "syntheticCompatHandleSystemBack"
        var result = base
        var suffix = 1
        while (Regex("\\b${Regex.escape(result)}\\b").containsMatchIn(file.text) ||
            owner.findMethodsByName(result, true).isNotEmpty()) result = base + suffix++
        return result
    }

    private fun done(original: PsiFile, file: PsiFile, edits: List<Edit>): SystemBackMigration.Result =
        SystemBackMigration.Result(original.text, apply(file.text, edits),
            "将 Activity 返回业务提取为独立方法，移除 onBackPressed() 重写；由生命周期回调统一处理新旧系统返回，保留原业务、类内直接调用及默认返回的重入保护。")

    private fun edited(original: PsiFile): SystemBackMigration.Result = SystemBackMigration.Result(original.text, null,
        "已有返回桥接已被编辑，无法安全提取业务方法；请检查 handleOnBackPressed() 的转发及重入保护。")

    private fun java(original: PsiFile, file: PsiJavaFile, legacy: SystemBackMigration.Result?): SystemBackMigration.Result? {
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).singleOrNull {
            it.name == "onBackPressed" && it.parameterList.parametersCount == 0 && it.returnType == PsiTypes.voidType() &&
                it.containingClass?.let { owner -> InheritanceUtil.isInheritor(owner, ACTIVITY) } == true
        } ?: return legacy
        val owner = method.containingClass!!
        val physical = PsiTreeUtil.findChildrenOfType(original, PsiClass::class.java).singleOrNull { it.qualifiedName == owner.qualifiedName } ?: owner
        if (SystemBackMigration.hasBackInheritance(physical)) return legacy
        val forward = PsiTreeUtil.findChildrenOfType(owner, PsiMethodCallExpression::class.java).singleOrNull {
            it.methodExpression.text == "${owner.name}.this.onBackPressed" &&
                PsiTreeUtil.getParentOfType(it, PsiMethod::class.java)?.name == "handleOnBackPressed"
        } ?: return edited(original)
        val callback = PsiTreeUtil.getParentOfType(forward, PsiMethod::class.java)!!
        val expectedCallback = "{setEnabled(false);try{${owner.name}.this.onBackPressed();}finally{setEnabled(true);}}"
        if (callback.body!!.text.filterNot(Char::isWhitespace) != expectedCallback) return edited(original)
        // Old unguarded default-return overrides need the legacy planner's field wrapper first.
        if (PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression::class.java).any {
                it.methodExpression.referenceName == "onBackPressed" && it.methodExpression.qualifierExpression is PsiSuperExpression
            } && !method.text.contains("// SyntheticCompat guarded system back override")) return edited(original)
        val helper = name(file, physical)
        val edits = mutableListOf<Edit>()
        val modifiers = method.modifierList
        val modifierEdits = modifiers.annotations.filter { it.qualifiedName in setOf("java.lang.Override", "Override") }
            .map { Edit(it.textOffset - modifiers.textOffset, it.textRange.endOffset - modifiers.textOffset, "") } +
            modifiers.node.getChildren(null).filter { it.elementType in setOf(JavaTokenType.PUBLIC_KEYWORD, JavaTokenType.PROTECTED_KEYWORD, JavaTokenType.PRIVATE_KEYWORD) }
                .map { Edit(it.startOffset - modifiers.textOffset, it.startOffset + it.textLength - modifiers.textOffset, "") }
        val modifierText = apply(modifiers.text, modifierEdits).trim()
        edits += Edit(modifiers.textRange.startOffset, modifiers.textRange.endOffset, "$HANDLER\n    ${if (modifierText.isEmpty()) "" else "$modifierText "}private${if (modifiers.textLength == 0) " " else ""}")
        edits += Edit(method.nameIdentifier!!.textRange.startOffset, method.nameIdentifier!!.textRange.endOffset, helper)
        // Rename only references to this Activity's own method. Other receivers and super
        // fallback calls keep their meaning; external callers retain the inherited dispatcher API.
        for (reference in PsiTreeUtil.findChildrenOfType(owner, PsiReferenceExpression::class.java)) {
            if (reference.referenceName != "onBackPressed" || reference.qualifierExpression is PsiSuperExpression) continue
            // A preview copy may lack the functional-interface context required for full
            // method-reference resolution. Exact references still expose their candidate.
            val target = (reference.resolve() ?: (reference as? PsiMethodReferenceExpression)
                ?.takeIf { it.isExact }?.potentiallyApplicableMember) as? PsiMethod
            if (reference == forward.methodExpression || (target?.name == method.name && target.parameterList.parametersCount == 0 && target.containingClass?.qualifiedName == owner.qualifiedName)) {
                val identifier = reference.referenceNameElement ?: continue
                edits += Edit(identifier.textRange.startOffset, identifier.textRange.endOffset, helper)
            }
        }
        return done(original, file, edits)
    }

    private fun kotlin(original: PsiFile, file: KtFile, legacy: SystemBackMigration.Result?): SystemBackMigration.Result? {
        val method = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).singleOrNull {
            it.name == "onBackPressed" && it.valueParameters.isEmpty() && it.hasModifier(KtTokens.OVERRIDE_KEYWORD) &&
                (it.parent?.parent as? KtClassOrObject)?.toLightClass()?.let { owner -> InheritanceUtil.isInheritor(owner, ACTIVITY) } == true
        } ?: return legacy
        val owner = method.parent.parent as KtClassOrObject
        val physical = PsiTreeUtil.findChildrenOfType(original, KtClassOrObject::class.java).singleOrNull { it.fqName == owner.fqName }?.toLightClass() ?: owner.toLightClass()!!
        if (SystemBackMigration.hasBackInheritance(physical)) return legacy
        val forward = PsiTreeUtil.findChildrenOfType(owner, KtNameReferenceExpression::class.java).singleOrNull {
            it.getReferencedName() == "onBackPressed" &&
                ((it.parent as? KtCallExpression)?.parent as? KtDotQualifiedExpression)?.receiverExpression?.text == "this@${owner.name}" &&
                PsiTreeUtil.getParentOfType(it, KtNamedFunction::class.java)?.name == "handleOnBackPressed"
        } ?: return edited(original)
        val callback = PsiTreeUtil.getParentOfType(forward, KtNamedFunction::class.java)!!
        val expectedCallback = "{isEnabled=falsetry{this@${owner.name}.onBackPressed()}finally{isEnabled=true}}"
        if (callback.bodyExpression!!.text.filterNot(Char::isWhitespace) != expectedCallback) return edited(original)
        if (PsiTreeUtil.findChildrenOfType(method, KtNameReferenceExpression::class.java).any {
                it.getReferencedName() == "onBackPressed" &&
                    ((it.parent as? KtCallExpression)?.parent as? KtDotQualifiedExpression)?.receiverExpression is KtSuperExpression
            } && !method.text.contains("// SyntheticCompat guarded system back override")) return edited(original)
        val helper = name(file, physical)
        val modifiers = method.modifierList!!
        val modifierEdits = modifiers.node.getChildren(null).filter {
            it.elementType in setOf(KtTokens.OVERRIDE_KEYWORD, KtTokens.OPEN_KEYWORD, KtTokens.PUBLIC_KEYWORD,
                KtTokens.PROTECTED_KEYWORD, KtTokens.PRIVATE_KEYWORD, KtTokens.INTERNAL_KEYWORD)
        }.map { Edit(it.startOffset - modifiers.textOffset, it.startOffset + it.textLength - modifiers.textOffset, "") }
        val modifierText = apply(modifiers.text, modifierEdits).trim()
        val edits = mutableListOf(Edit(modifiers.textOffset, modifiers.textRange.endOffset,
            "$HANDLER\n    ${if (modifierText.isEmpty()) "" else "$modifierText "}private"),
            Edit(method.nameIdentifier!!.textOffset, method.nameIdentifier!!.textRange.endOffset, helper))
        for (reference in PsiTreeUtil.findChildrenOfType(owner, KtNameReferenceExpression::class.java)) {
            if (reference.getReferencedName() != "onBackPressed") continue
            val receiver = ((reference.parent as? KtCallExpression)?.parent as? KtDotQualifiedExpression)?.receiverExpression
            if (receiver is KtSuperExpression) continue
            val target = reference.mainReference.resolve()?.navigationElement as? KtNamedFunction
            if (reference == forward || (target != null && target.name == method.name && target.valueParameters.isEmpty() &&
                    (target.parent?.parent as? KtClassOrObject)?.fqName == owner.fqName))
                edits += Edit(reference.textOffset, reference.textRange.endOffset, helper)
        }
        for (expression in PsiTreeUtil.findChildrenOfType(method, KtReturnExpression::class.java)) {
            val label = expression.getTargetLabel()
            if (label != null && label.text.removePrefix("@") == "onBackPressed" &&
                label.references.any { it.resolve()?.navigationElement == method })
                edits += Edit(label.textOffset, label.textRange.endOffset, (if (label.text.startsWith("@")) "@" else "") + helper)
        }
        return done(original, file, edits)
    }
}
