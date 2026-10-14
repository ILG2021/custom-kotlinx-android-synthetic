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

    fun plan(original: PsiFile, legacy: SystemBackMigration.Result?, handlers: Map<String, String> = emptyMap()): SystemBackMigration.Result? {
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
            is PsiJavaFile -> java(original, file, legacy, handlers)
            is KtFile -> kotlin(original, file, legacy, handlers)
            else -> legacy
        }
    }

    private fun apply(source: String, edits: List<Edit>): String {
        var result = source
        edits.sortedByDescending { it.start }.forEach { edit -> result = result.substring(0, edit.start) + edit.text + result.substring(edit.end) }
        return result
    }

    internal fun name(file: PsiFile, owner: PsiClass): String {
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

    internal fun owner(file: PsiFile): PsiClass? = (when (file) {
        is PsiJavaFile -> PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).singleOrNull {
            it.name == "onBackPressed" && it.parameterList.parametersCount == 0 && it.returnType == PsiTypes.voidType()
        }?.containingClass
        is KtFile -> PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).singleOrNull {
            it.name == "onBackPressed" && it.valueParameters.isEmpty() && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        }?.let { (it.parent?.parent as? KtClassOrObject)?.toLightClass() }
        else -> null
    })?.takeIf { it.qualifiedName != ACTIVITY && InheritanceUtil.isInheritor(it, ACTIVITY) }

    internal fun activities(file: PsiFile): List<PsiClass> = (when (file) {
        is PsiJavaFile -> PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java).toList()
        is KtFile -> PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java).mapNotNull { it.toLightClass() }
        else -> emptyList()
    }).filter { it.qualifiedName != ACTIVITY && InheritanceUtil.isInheritor(it, ACTIVITY) }

    internal fun family(owner: PsiClass): List<PsiClass> = listOf(owner) +
        com.intellij.psi.search.searches.ClassInheritorsSearch.search(owner, owner.useScope, true).findAll()

    private fun uniqueWrapper(file: PsiFile, owner: PsiClass): String {
        val base = "syntheticCompatCallSuperSystemBack"
        var name = base
        var suffix = 1
        while (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(file.text) || owner.findMethodsByName(name, true).isNotEmpty()) name = base + suffix++
        return name
    }

    internal fun parentHandler(owner: PsiClass, handlers: Map<String, String>): String? {
        var parent = owner.superClass
        while (parent != null && parent.qualifiedName != ACTIVITY) {
            handlers[parent.qualifiedName]?.let { return it }
            val existing = parent.methods.singleOrNull { method ->
                method.name.startsWith("syntheticCompatHandleSystemBack") &&
                    method.hasModifierProperty(PsiModifier.PROTECTED) &&
                    method.containingFile.text.take(method.textOffset).takeLast(300).contains(HANDLER)
            }
            if (existing != null) return existing.name
            // An unmigrated project override must be included in the same preview.
            if (parent.methods.any { it.name == "onBackPressed" && it.parameterList.parametersCount == 0 } &&
                !parent.qualifiedName.orEmpty().startsWith("androidx.") &&
                !parent.qualifiedName.orEmpty().startsWith("android.")) return null
            parent = parent.superClass
        }
        return null
    }

    private fun missingParent(original: PsiFile) = SystemBackMigration.Result(original.text, null,
        "父类返回重写需要一起提取；请使用全项目迁移并处理继承链中无法自动迁移的文件。")

    private fun hasChildren(owner: PsiClass) =
        com.intellij.psi.search.searches.ClassInheritorsSearch.search(owner, owner.useScope, true).findFirst() != null

    private fun java(original: PsiFile, file: PsiJavaFile, legacy: SystemBackMigration.Result?, handlers: Map<String, String>): SystemBackMigration.Result? {
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).singleOrNull {
            it.name == "onBackPressed" && it.parameterList.parametersCount == 0 && it.returnType == PsiTypes.voidType() &&
                it.containingClass?.let { owner -> InheritanceUtil.isInheritor(owner, ACTIVITY) } == true
        } ?: return legacy
        val owner = method.containingClass!!
        val physical = PsiTreeUtil.findChildrenOfType(original, PsiClass::class.java).singleOrNull { it.qualifiedName == owner.qualifiedName } ?: owner
        val parentHelper = parentHandler(physical, handlers)
        if (physical.findMethodsByName("onBackPressed", true).any {
                it.containingClass?.qualifiedName != physical.qualifiedName && it.containingClass?.qualifiedName?.let { n -> !n.startsWith("android.") && !n.startsWith("androidx.") } == true
            } && parentHelper == null) return missingParent(original)
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
        val helper = handlers[physical.qualifiedName] ?: parentHelper ?: name(file, physical)
        if (physical.methods.any { it.name == helper }) return SystemBackMigration.Result(original.text, null,
            "继承的返回业务方法名称与本类已有方法冲突，请先处理重名方法。")
        val edits = mutableListOf<Edit>()
        val outsideSuper = PsiTreeUtil.findChildrenOfType(owner, PsiReferenceExpression::class.java).filter {
            it.referenceName == "onBackPressed" && it.qualifierExpression is PsiSuperExpression &&
                !PsiTreeUtil.isAncestor(method, it, false) &&
                (PsiTreeUtil.getParentOfType(it, PsiClass::class.java) == owner ||
                    it.qualifierExpression?.text == "${owner.name}.super")
        }.toSet()
        val wrapper = uniqueWrapper(file, physical)
        if (outsideSuper.isNotEmpty()) {
            val field = owner.fields.singleOrNull { it.type.canonicalText == "androidx.activity.OnBackPressedCallback" &&
                method.text.contains("this.${it.name}") }?.name ?: return edited(original)
            val target = parentHelper ?: "onBackPressed"
            val offset = owner.rBrace!!.textOffset
            edits += Edit(offset, offset, "\n    private void $wrapper() {\n        androidx.activity.OnBackPressedCallback callback = this.$field;\n        boolean enabled = callback != null && callback.isEnabled();\n        if (callback != null) callback.setEnabled(false);\n        try { super.$target(); } finally {\n            if (callback != null) callback.setEnabled(enabled);\n        }\n    }\n")
        }
        val modifiers = method.modifierList
        val modifierEdits = modifiers.annotations.filter { it.qualifiedName in setOf("java.lang.Override", "Override") }
            .map { Edit(it.textOffset - modifiers.textOffset, it.textRange.endOffset - modifiers.textOffset, "") } +
            modifiers.node.getChildren(null).filter { it.elementType in setOf(JavaTokenType.PUBLIC_KEYWORD, JavaTokenType.PROTECTED_KEYWORD, JavaTokenType.PRIVATE_KEYWORD, JavaTokenType.FINAL_KEYWORD) }
                .map { Edit(it.startOffset - modifiers.textOffset, it.startOffset + it.textLength - modifiers.textOffset, "") }
        val modifierText = apply(modifiers.text, modifierEdits).trim()
        val visibility = if (hasChildren(physical) || parentHelper != null) "protected" else "private"
        edits += Edit(modifiers.textRange.startOffset, modifiers.textRange.endOffset, "$HANDLER\n    ${if (modifierText.isEmpty()) "" else "$modifierText "}$visibility${if (modifiers.textLength == 0) " " else ""}")
        edits += Edit(method.nameIdentifier!!.textRange.startOffset, method.nameIdentifier!!.textRange.endOffset, helper)
        // Rename only references to this Activity's own method. Other receivers and super
        // fallback calls keep their meaning; external callers retain the inherited dispatcher API.
        for (reference in PsiTreeUtil.findChildrenOfType(owner, PsiReferenceExpression::class.java)) {
            if (reference.referenceName != "onBackPressed") continue
            if (reference.qualifierExpression is PsiSuperExpression) {
                if (reference in outsideSuper) {
                    val qualifier = reference.qualifierExpression!!
                    edits += Edit(qualifier.textOffset, qualifier.textRange.endOffset, "${owner.name}.this")
                    reference.referenceNameElement?.let { edits += Edit(it.textOffset, it.textRange.endOffset, wrapper) }
                } else if (parentHelper != null && PsiTreeUtil.isAncestor(method, reference, false)) reference.referenceNameElement?.let {
                    edits += Edit(it.textOffset, it.textRange.endOffset, parentHelper)
                }
                continue
            }
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

    private fun kotlin(original: PsiFile, file: KtFile, legacy: SystemBackMigration.Result?, handlers: Map<String, String>): SystemBackMigration.Result? {
        val method = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).singleOrNull {
            it.name == "onBackPressed" && it.valueParameters.isEmpty() && it.hasModifier(KtTokens.OVERRIDE_KEYWORD) &&
                (it.parent?.parent as? KtClassOrObject)?.toLightClass()?.let { owner -> InheritanceUtil.isInheritor(owner, ACTIVITY) } == true
        } ?: return legacy
        val owner = method.parent.parent as KtClassOrObject
        val physical = PsiTreeUtil.findChildrenOfType(original, KtClassOrObject::class.java).singleOrNull { it.fqName == owner.fqName }?.toLightClass() ?: owner.toLightClass()!!
        val parentHelper = parentHandler(physical, handlers)
        if (physical.findMethodsByName("onBackPressed", true).any {
                it.containingClass?.qualifiedName != physical.qualifiedName && it.containingClass?.qualifiedName?.let { n -> !n.startsWith("android.") && !n.startsWith("androidx.") } == true
            } && parentHelper == null) return missingParent(original)
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
        val helper = handlers[physical.qualifiedName] ?: parentHelper ?: name(file, physical)
        if (physical.methods.any { it.name == helper }) return SystemBackMigration.Result(original.text, null,
            "继承的返回业务方法名称与本类已有方法冲突，请先处理重名方法。")
        val modifiers = method.modifierList!!
        val modifierEdits = modifiers.node.getChildren(null).filter {
            it.elementType in setOf(KtTokens.OVERRIDE_KEYWORD, KtTokens.OPEN_KEYWORD, KtTokens.PUBLIC_KEYWORD,
                KtTokens.PROTECTED_KEYWORD, KtTokens.PRIVATE_KEYWORD, KtTokens.INTERNAL_KEYWORD, KtTokens.FINAL_KEYWORD)
        }.map { Edit(it.startOffset - modifiers.textOffset, it.startOffset + it.textLength - modifiers.textOffset, "") }
        val modifierText = apply(modifiers.text, modifierEdits).trim()
        val visibility = when {
            parentHelper != null -> "protected override"
            hasChildren(physical) -> "protected open"
            else -> "private"
        }
        val edits = mutableListOf(Edit(modifiers.textOffset, modifiers.textRange.endOffset,
            "$HANDLER\n    ${if (modifierText.isEmpty()) "" else "$modifierText "}$visibility"),
            Edit(method.nameIdentifier!!.textOffset, method.nameIdentifier!!.textRange.endOffset, helper))
        val outsideSuper = PsiTreeUtil.findChildrenOfType(owner, KtNameReferenceExpression::class.java).filter {
            val receiver = ((it.parent as? KtCallExpression)?.parent as? KtDotQualifiedExpression)?.receiverExpression
            it.getReferencedName() == "onBackPressed" && receiver is KtSuperExpression &&
                !PsiTreeUtil.isAncestor(method, it, false) &&
                (PsiTreeUtil.getParentOfType(it, KtClassOrObject::class.java) == owner || receiver.text == "super@${owner.name}")
        }.toSet()
        val wrapper = uniqueWrapper(file, physical)
        if (outsideSuper.isNotEmpty()) {
            val field = owner.declarations.filterIsInstance<KtProperty>().singleOrNull {
                it.typeReference?.text == "androidx.activity.OnBackPressedCallback?" && method.text.contains("this.${it.name}")
            }?.name ?: return edited(original)
            val target = parentHelper ?: "onBackPressed"
            val offset = owner.body!!.rBrace!!.textOffset
            edits += Edit(offset, offset, "\n    private fun $wrapper() {\n        val callback = this.$field\n        val enabled = callback?.isEnabled ?: false\n        if (callback != null) callback.isEnabled = false\n        try { super.$target() } finally {\n            if (callback != null) callback.isEnabled = enabled\n        }\n    }\n")
        }
        for (reference in PsiTreeUtil.findChildrenOfType(owner, KtNameReferenceExpression::class.java)) {
            if (reference.getReferencedName() != "onBackPressed") continue
            val receiver = ((reference.parent as? KtCallExpression)?.parent as? KtDotQualifiedExpression)?.receiverExpression
            if (receiver is KtSuperExpression) {
                if (reference in outsideSuper) {
                    edits += Edit(receiver.textOffset, receiver.textRange.endOffset, "this@${owner.name}")
                    edits += Edit(reference.textOffset, reference.textRange.endOffset, wrapper)
                } else if (parentHelper != null && PsiTreeUtil.isAncestor(method, reference, false))
                    edits += Edit(reference.textOffset, reference.textRange.endOffset, parentHelper)
                continue
            }
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
