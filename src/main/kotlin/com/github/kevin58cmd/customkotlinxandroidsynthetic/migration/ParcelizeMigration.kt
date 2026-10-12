package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.lexer.KtTokens
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiComment

/** Plans edits from Kotlin syntax, leaving comments and literal text untouched. */
object ParcelizeMigration {
    data class Result(val before: String, val after: String)

    fun hasModernReferences(file: KtFile): Boolean =
        file.importDirectives.any { it.importedFqName?.asString()?.startsWith("kotlinx.parcelize.") == true || it.importedFqName?.asString() == "kotlinx.parcelize" } ||
            PsiTreeUtil.collectElementsOfType(file, KtUserType::class.java).any { qualifiedTypeName(it)?.startsWith("kotlinx.parcelize.") == true } ||
            PsiTreeUtil.collectElementsOfType(file, KtDotQualifiedExpression::class.java).any { qualifiedName(it)?.startsWith("kotlinx.parcelize.") == true }

    fun plan(file: KtFile): Result? {
        val before = file.text
        val expressionRanges = PsiTreeUtil.collectElementsOfType(file, KtDotQualifiedExpression::class.java)
            .filter { expression ->
                qualifiedName(expression) == "kotlinx.android.parcel" &&
                    PsiTreeUtil.getParentOfType(expression, KtPackageDirective::class.java) == null &&
                    (expression.parent is KtDotQualifiedExpression ||
                        PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java)?.isAllUnder == true)
            }.map { it to (it.receiverExpression as KtDotQualifiedExpression).selectorExpression }
        // Kotlin represents annotation names and type references as user types, not expressions.
        val typeRanges = PsiTreeUtil.collectElementsOfType(file, KtUserType::class.java)
            .filter { qualifiedTypeName(it) == "kotlinx.android.parcel" && it.parent is KtUserType }
            .map { it to it.qualifier?.referenceExpression }
        val ranges = (expressionRanges + typeRanges).distinctBy { it.first.textRange.startOffset }
        val modernImports = file.importDirectives.filter { it.importedFqName?.asString()?.startsWith("kotlinx.parcelize") == true }
        val duplicateImports = file.importDirectives.filter { old ->
            val name = old.importedFqName?.asString() ?: return@filter false
            val migrated = name.replace("kotlinx.android.parcel.", "kotlinx.parcelize.")
                .let { if (it == "kotlinx.android.parcel") "kotlinx.parcelize" else it }
            name != migrated && modernImports.any { it.importedFqName?.asString() == migrated && it.aliasName == old.aliasName && it.isAllUnder == old.isAllUnder }
        }
        if (ranges.isEmpty() && duplicateImports.isEmpty()) return null
        val edits = ranges.filter { (element, _) -> duplicateImports.none { it.textRange.contains(element.textRange) } }
            .map { (element, android) -> element.textRange to migratedPrefix(element, android!!) }
            .plus(duplicateImports.map { directive ->
                directive.textRange to PsiTreeUtil.collectElementsOfType(directive, PsiComment::class.java).joinToString(" ") { it.text }
            })
        val after = StringBuilder(before)
        edits.sortedByDescending { it.first.startOffset }.forEach { (range, replacement) ->
            after.replace(range.startOffset, range.endOffset, replacement)
        }
        return Result(before, after.toString())
    }

    private fun migratedPrefix(element: PsiElement, android: PsiElement): String {
        val dot = element.node.findChildByType(KtTokens.DOT)!!.psi
        val parcel = when (element) {
            is KtDotQualifiedExpression -> element.selectorExpression!!
            is KtUserType -> element.referenceExpression!!
            else -> error("Unexpected qualified reference")
        }
        val offset = element.textRange.startOffset
        val result = StringBuilder(element.text)
        listOf(parcel to "parcelize", android to "", dot to "").sortedByDescending { it.first.textRange.startOffset }
            .forEach { (part, replacement) -> result.replace(part.textRange.startOffset - offset, part.textRange.endOffset - offset, replacement) }
        return result.toString()
    }

    private fun qualifiedTypeName(type: KtUserType): String? {
        val name = type.referencedName ?: return null
        val qualifier = type.qualifier ?: return name
        return qualifiedTypeName(qualifier)?.let { "$it.$name" }
    }

    private fun qualifiedName(expression: KtExpression?): String? = when (expression) {
        is KtNameReferenceExpression -> expression.getReferencedName()
        is KtDotQualifiedExpression -> {
            val receiver = qualifiedName(expression.receiverExpression)
            val selector = qualifiedName(expression.selectorExpression)
            if (receiver == null || selector == null) null else "$receiver.$selector"
        }
        else -> null
    }
}
