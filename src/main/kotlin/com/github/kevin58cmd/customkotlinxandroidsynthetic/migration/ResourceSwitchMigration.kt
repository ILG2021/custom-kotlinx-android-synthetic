package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/** Conservative, read-only planner. Never guesses unresolved resource references. */
object ResourceSwitchMigration {
    private fun resource(expression: PsiExpression): Boolean {
        val field = (expression as? PsiReferenceExpression)?.resolve() as? PsiField ?: return false
        val owner = field.containingClass ?: return false
        return owner.name == "id" && owner.containingClass?.name == "R" &&
            field.hasModifierProperty(PsiModifier.STATIC) && field.type == PsiType.INT
    }

    fun plan(file: PsiJavaFile): ButterKnifeMigration.Result? {
        val before = file.text
        val edits = mutableListOf<Pair<PsiElement, String>>()
        val reservedNames = mutableSetOf<String>()
        val skipped = mutableListOf<String>()
        for (statement in PsiTreeUtil.findChildrenOfType(file, PsiSwitchStatement::class.java)) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
            val labels = statement.body?.statements?.filterIsInstance<PsiSwitchLabelStatementBase>().orEmpty()
            if (labels.none { label -> label.caseValues?.expressions.orEmpty().any {
                    resource(it) && JavaPsiFacade.getInstance(file.project).constantEvaluationHelper.computeConstantExpression(it) == null
                } }) continue
            val replacement = convert(statement, before, reservedNames)
            if (replacement == null) skipped += "第 ${before.take(statement.textOffset).count { it == '\n' } + 1} 行：复杂控制流、跨分支或无法解析的变量引用、不支持的声明或标签，已跳过"
            else edits += statement to replacement
        }
        val converted = edits.size
        for (block in PsiTreeUtil.findChildrenOfType(file, PsiBlockStatement::class.java).sortedByDescending { it.textOffset }) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
            if (edits.any { (element, _) -> element.textRange.intersects(block.textRange) }) continue
            val replacement = flattenGeneratedBlock(block, before, reservedNames) ?: continue
            edits += block to replacement
        }
        val cleaned = edits.size - converted
        if (edits.isEmpty() && skipped.isEmpty()) return null
        val after = StringBuilder(before)
        edits.sortedByDescending { it.first.textOffset }.forEach { (node, text) ->
            after.replace(node.textRange.startOffset, node.textRange.endOffset, text)
        }
        return ButterKnifeMigration.Result(before, after.toString().takeIf { edits.isNotEmpty() },
            (listOf("可转换 $converted 处；清理多余代码块 $cleaned 处；跳过 ${skipped.size} 处。") + skipped).joinToString("\n"))
    }

    private fun nextVariable(source: String, reservedNames: MutableSet<String>): String {
        var suffix = 0
        var name: String
        do { name = "syntheticViewId${suffix++}" } while (name in reservedNames || Regex("\\b$name\\b").containsMatchIn(source))
        reservedNames += name
        return name
    }

    private fun indentation(source: String, offset: Int): String {
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        return source.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    }

    /** Shift the entire fragment uniformly, including text-block closing delimiters. */
    private fun indentFragment(text: String, indent: String): String =
        text.trimIndent().lines().joinToString("\n") { if (it.isBlank()) "" else indent + it }

    private fun convert(node: PsiSwitchStatement, source: String, reservedNames: MutableSet<String>): String? {
        val body = node.body ?: return null
        val selector = node.expression ?: return null
        if (selector.type != PsiType.INT || PsiTreeUtil.hasErrorElements(node)) return null
        if (PsiTreeUtil.findChildrenOfType(body, PsiSwitchStatement::class.java).isNotEmpty()) return null
        val statements = body.statements
        if (statements.firstOrNull() !is PsiSwitchLabelStatement) return null
        if (statements.any { it is PsiSwitchLabeledRuleStatement }) return null
        val declarations = statements.filterIsInstance<PsiDeclarationStatement>().flatMap { it.declaredElements.toList() }
        // An if branch introduces a narrower scope. Only narrow local variables whose
        // references all remain within the same branch; local classes need separate analysis.
        if (declarations.any { it !is PsiLocalVariable }) return null
        val references = if (declarations.isEmpty()) emptyList() else
            PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression::class.java).toList()
        val breaks = PsiTreeUtil.findChildrenOfType(body, PsiBreakStatement::class.java)
            .filter { it.findExitedStatement() == node }
        // Only a terminal, direct break can be removed without changing control flow.
        if (breaks.any { it.parent != body }) return null
        val variable = nextVariable(source, reservedNames)
        val branches = mutableListOf<Pair<List<String>, String>>()
        var values = mutableListOf<String>()
        var defaultSeen = false
        var start = -1
        for ((index, statement) in statements.withIndex()) {
            if (statement is PsiSwitchLabelStatement) {
                if (statement.isDefaultCase) {
                    if (values.isNotEmpty() || defaultSeen) return null
                    defaultSeen = true
                } else {
                    if (defaultSeen) return null
                    val expressions = statement.caseValues?.expressions ?: return null
                    if (expressions.isEmpty() || expressions.any { !resource(it) }) return null
                    values.addAll(expressions.map { it.text })
                }
                // PSI may attach the following comment to the label; start at the colon.
                val labelEnd = statement.children.filterIsInstance<PsiJavaToken>()
                    .lastOrNull { it.tokenType == JavaTokenType.COLON }?.textRange?.endOffset ?: return null
                if (start < 0) start = labelEnd
                else if (source.substring(start, statement.textOffset).isNotBlank()) return null
                else start = labelEnd
            } else {
                val last = index == statements.lastIndex || statements[index + 1] is PsiSwitchLabelStatement
                if (!last) {
                    if (statement in breaks) return null
                    continue
                }
                val terminalBreak = statement in breaks
                if (!terminalBreak && statement !is PsiReturnStatement && statement !is PsiThrowStatement && index != statements.lastIndex) return null
                val end = if (index == statements.lastIndex) body.rBrace?.textOffset ?: return null else statements[index + 1].textOffset
                for (declaration in declarations.filter { it.textOffset in start until end }) {
                    for (reference in references) {
                        if (reference.referenceName != (declaration as PsiLocalVariable).name) continue
                        val resolved = reference.resolve()
                        if (resolved == declaration && reference.textOffset !in start until end) return null
                        if (resolved == null && reference.qualifierExpression == null) return null
                    }
                }
                var text = source.substring(start, end)
                if (terminalBreak) {
                    val relative = statement.textOffset - start
                    val comments = PsiTreeUtil.findChildrenOfType(statement, PsiComment::class.java)
                        .joinToString("\n") { it.text }
                    text = text.replaceRange(relative, relative + statement.textLength, comments)
                }
                branches += values.toList() to text
                values = mutableListOf()
                start = -1
            }
        }
        // Empty trailing labels require their own branch; skip until supported.
        if (start >= 0 || branches.isEmpty()) return null
        val prefix = source.substring(body.lBrace!!.textRange.endOffset, statements.first().textOffset)
        val indent = indentation(source, node.textOffset)
        // A single-statement if/loop body or a label still needs a block for the cache
        // declaration and the if chain. A surrounding code block already supplies it.
        val wrapped = node.parent !is PsiCodeBlock
        val inner = if (wrapped) "$indent    " else indent
        return buildString {
            if (wrapped) append("{\n$inner")
            append("int $variable = ${selector.text};\n")
            if (prefix.isNotBlank()) append(indentFragment(prefix, inner)).append('\n')
            append(inner)
            branches.forEachIndexed { index, (cases, text) ->
                if (index > 0) append(" else ")
                if (cases.isNotEmpty()) append("if (${cases.joinToString(" || ") { "$variable == $it" }}) ")
                else if (index == 0) return null
                append("{\n").append(indentFragment(text, "$inner    ")).append("\n$inner}")
            }
            if (wrapped) append("\n$indent}")
        }
    }

    /** Also repair the extra scope produced by older versions, using the same preview. */
    private fun flattenGeneratedBlock(block: PsiBlockStatement, source: String, reservedNames: MutableSet<String>): String? {
        if (block.parent !is PsiCodeBlock) return null
        val body = block.codeBlock
        val statements = body.statements
        if (statements.size != 2) return null
        val declaration = statements[0] as? PsiDeclarationStatement ?: return null
        val variable = declaration.declaredElements.singleOrNull() as? PsiLocalVariable ?: return null
        val name = variable.name ?: return null
        if (!name.matches(Regex("syntheticViewId\\d+")) || variable.type != PsiType.INT || variable.initializer == null) return null
        var branch = statements[1] as? PsiIfStatement ?: return null
        val conditions = mutableSetOf<PsiReferenceExpression>()
        while (true) {
            val condition = branch.condition ?: return null
            if (!generatedCondition(condition, variable)) return null
            conditions += PsiTreeUtil.findChildrenOfType(condition, PsiReferenceExpression::class.java)
                .filter { it.resolve() == variable }
            if (branch.thenBranch !is PsiBlockStatement) return null
            when (val otherwise = branch.elseBranch) {
                null -> break
                is PsiIfStatement -> branch = otherwise
                is PsiBlockStatement -> break
                else -> return null
            }
        }
        val references = PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression::class.java).filter { it.resolve() == variable }
        if (references.any { it !in conditions }) return null
        val outside = source.removeRange(block.textRange.startOffset, block.textRange.endOffset)
        val replacementName = if (name in reservedNames || Regex("\\b$name\\b").containsMatchIn(outside))
            nextVariable(source, reservedNames) else name.also { reservedNames += it }
        val start = body.lBrace?.textRange?.endOffset ?: return null
        val end = body.rBrace?.textOffset ?: return null
        val text = StringBuilder(source.substring(start, end))
        if (replacementName != name) {
            val identifiers = listOfNotNull(variable.nameIdentifier) + references.mapNotNull { it.referenceNameElement }
            identifiers.sortedByDescending { it.textOffset }.forEach {
                text.replace(it.textRange.startOffset - start, it.textRange.endOffset - start, replacementName)
            }
        }
        val indent = indentation(source, block.textOffset)
        val result = indentFragment(text.toString(), indent).removePrefix(indent)
        val followingWhitespace = source.substring(block.textRange.endOffset).takeWhile { it.isWhitespace() }
        return result + if ('\n' in followingWhitespace) "" else "\n$indent"
    }

    private fun generatedCondition(expression: PsiExpression, variable: PsiLocalVariable): Boolean = when (expression) {
        is PsiParenthesizedExpression -> expression.expression?.let { generatedCondition(it, variable) } == true
        is PsiBinaryExpression -> when (expression.operationTokenType) {
            JavaTokenType.OROR -> generatedCondition(expression.lOperand, variable) &&
                expression.rOperand?.let { generatedCondition(it, variable) } == true
            JavaTokenType.EQEQ -> (expression.lOperand as? PsiReferenceExpression)?.resolve() == variable &&
                expression.rOperand?.let { resource(it) } == true
            else -> false
        }
        is PsiPolyadicExpression -> expression.operationTokenType == JavaTokenType.OROR &&
            expression.operands.all { generatedCondition(it, variable) }
        else -> false
    }
}
