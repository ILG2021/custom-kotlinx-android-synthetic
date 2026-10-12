package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/** Kotlin edits are planned against PSI and shown in the same preview as Java edits. */
object KotlinButterKnifeMigration {
    private data class Edit(val start: Int, val end: Int, val text: String)
    private const val PREFIX = "syntheticCompat"
    private fun requireSupported(value: Boolean, reason: String) { require(value) { reason } }
    private fun imports(file: KtFile, name: String): String? {
        val directive = file.importDirectives.firstOrNull { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == name && !it.isAllUnder }
        return directive?.importedFqName?.asString()
    }
    private fun isJvmField(entry: KtAnnotationEntry): Boolean {
        val text = entry.typeReference?.text ?: return false
        imports(entry.containingKtFile, text)?.let { return it == "kotlin.jvm.JvmField" }
        if (text == "kotlin.jvm.JvmField") return true
        val reference = (entry.typeReference?.typeElement as? KtUserType)?.referenceExpression
        val resolved = reference?.mainReference?.resolve()
        val name = when (resolved) {
            is PsiClass -> resolved.qualifiedName
            is PsiMethod -> resolved.containingClass?.qualifiedName
            is KtClassOrObject -> resolved.fqName?.asString()
            is KtConstructor<*> -> PsiTreeUtil.getParentOfType(resolved, KtClassOrObject::class.java)?.fqName?.asString()
            null -> entry.typeReference?.text?.let { imports(entry.containingKtFile, it) ?: if (it == "JvmField") "kotlin.jvm.JvmField" else it }
            else -> null
        }
        return name == "kotlin.jvm.JvmField"
    }
    fun annotationName(entry: KtAnnotationEntry): String? {
        val reference = entry.typeReference?.typeElement as? KtUserType
        val resolved = reference?.referenceExpression?.mainReference?.resolve() as? PsiClass
        val text = entry.typeReference?.text ?: return null
        val full = resolved?.qualifiedName ?: imports(entry.containingKtFile, text) ?: text.takeIf { it.startsWith("butterknife.") }
            ?: if (entry.containingKtFile.importDirectives.any { it.isAllUnder && it.importedFqName?.asString() == "butterknife" } && JavaPsiFacade.getInstance(entry.project).findClass("butterknife.$text", entry.resolveScope) != null) "butterknife.$text" else null
        return full?.takeIf { it.startsWith("butterknife.") }?.substringAfterLast('.')
    }
    private fun classType(file: KtFile, text: String): PsiType {
        val raw = text.trim().removeSuffix("?")
        val primitives = mapOf("Int" to "int", "Boolean" to "boolean", "Long" to "long", "Float" to "float", "Double" to "double", "Short" to "short", "Byte" to "byte", "Char" to "char", "Unit" to "void")
        primitives[raw]?.let { return PsiType.getTypeByName(it, file.project, file.resolveScope).let { _ -> when (it) {
            "int" -> PsiTypes.intType(); "boolean" -> PsiTypes.booleanType(); "long" -> PsiTypes.longType(); "float" -> PsiTypes.floatType(); "double" -> PsiTypes.doubleType(); "short" -> PsiTypes.shortType(); "byte" -> PsiTypes.byteType(); "char" -> PsiTypes.charType(); else -> PsiTypes.voidType()
        } } }
        val base = raw.substringBefore('<')
        val full = imports(file, base) ?: when (base) { "String" -> "java.lang.String"; "CharSequence" -> "java.lang.CharSequence"; "Any" -> "java.lang.Object"; else -> base }
        val facade = JavaPsiFacade.getInstance(file.project)
        val names = listOf(full, "${file.packageFqName.asString()}.$base") + file.importDirectives.filter { it.isAllUnder }.mapNotNull { it.importedFqName?.asString()?.let { pkg -> "$pkg.$base" } }
        val owner = names.firstNotNullOfOrNull { facade.findClass(it, file.resolveScope) }
        if (owner == null && full in setOf("java.lang.String", "java.lang.CharSequence", "java.lang.Object")) return PsiType.getTypeByName(full, file.project, file.resolveScope)
        requireSupported(owner != null, "Kotlin 类型未解析：$text，请完成 Gradle Sync")
        return facade.elementFactory.createType(owner!!)
    }
    private fun expressionType(expression: KtExpression): PsiType? = when (expression) {
        is KtThisExpression -> {
            val label = expression.getLabelName()
            val owner = generateSequence(expression.parent) { it.parent }.filterIsInstance<KtClassOrObject>().firstOrNull { label == null || it.name == label }
            owner?.toLightClass()?.let { JavaPsiFacade.getElementFactory(expression.project).createType(it) }
        }
        is KtParenthesizedExpression -> expression.expression?.let(::expressionType)
        is KtNameReferenceExpression -> when (val target = expression.mainReference.resolve()) {
            is PsiVariable -> target.type
            is PsiMethod -> target.returnType
            is KtProperty -> target.typeReference?.text?.let { classType(target.containingKtFile, it) } ?: target.initializer?.let(::expressionType)
            is KtParameter -> target.typeReference?.text?.let { classType(target.containingKtFile, it) }
            else -> null
        }
        else -> null
    }
    fun bindingCalls(file: KtFile): List<KtCallExpression> = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).filter { call ->
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return@filter false
        val symbol = callee.mainReference.resolve()
        if (symbol != null) return@filter (symbol as? PsiMethod)?.let {
            it.name == "bind" && it.containingClass?.qualifiedName == "butterknife.ButterKnife"
        } == true
        // Kotlin IDE resolution can fail on a deprecated Java overload even when its
        // imported class and this/root arguments remain available. Only accept an
        // explicitly identified ButterKnife symbol; never match arbitrary bind calls.
        val qualified = (call.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression == call }
        if (qualified != null) {
            if (callee.getReferencedName() != "bind") return@filter false
            val receiver = qualified.receiverExpression
            val receiverReference = when (receiver) {
                is KtNameReferenceExpression -> receiver
                is KtDotQualifiedExpression -> receiver.selectorExpression as? KtNameReferenceExpression
                else -> null
            } ?: return@filter false
            val receiverTarget = receiverReference.mainReference.resolve()
            if (receiverTarget != null) return@filter (receiverTarget as? PsiClass)?.qualifiedName == "butterknife.ButterKnife"
            val name = receiverReference.getReferencedName()
            if (hasShadowingDeclaration(call, name)) return@filter false
            receiver.text == "butterknife.ButterKnife" || receiver is KtNameReferenceExpression &&
                (imports(file, name) == "butterknife.ButterKnife" || name == "ButterKnife" &&
                    file.importDirectives.any { it.isAllUnder && it.importedFqName?.asString() == "butterknife" })
        } else {
            val name = callee.getReferencedName()
            !hasShadowingDeclaration(call, name) && imports(file, name) == "butterknife.ButterKnife.bind"
        }
    }
    private fun hasShadowingDeclaration(call: KtCallExpression, name: String): Boolean {
        val file = call.containingKtFile
        // An unresolved local/member with the same name must not be mistaken for the import.
        return PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java).any { declaration ->
            declaration.name == name &&
                (declaration.parent == file || generateSequence(call.parent) { it.parent }.any { ancestor ->
                    ancestor == declaration.parent || ancestor is KtNamedFunction && declaration is KtParameter && declaration.ownerFunction == ancestor
                })
        }
    }
    fun targetClass(call: KtCallExpression): PsiClass? = (call.valueArguments.firstOrNull()?.getArgumentExpression()?.let(::expressionType) as? PsiClassType)?.resolve()
    private fun fullCall(call: KtCallExpression): PsiElement = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call } ?: call
    private fun id(expression: KtExpression?): String {
        requireSupported(expression != null, "绑定注解缺少资源 ID")
        if (expression!!.text in setOf("-1", "View.NO_ID", "android.view.View.NO_ID")) return "@root"
        val reference = (expression as? KtNameReferenceExpression) ?: (expression as? KtDotQualifiedExpression)?.selectorExpression as? KtNameReferenceExpression
        val field = reference?.mainReference?.resolve() as? PsiField
        requireSupported(field?.containingClass?.containingClass?.name in setOf("R", "R2"), "资源 ID 未解析或不是 R/R2：${expression.text}")
        return "${field!!.containingClass!!.qualifiedName}.${field.name}".replace(".R2.", ".R.")
    }
    private fun argument(entry: KtAnnotationEntry, name: String, position: Int = 0): KtExpression? =
        entry.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == name }?.getArgumentExpression()
            ?: if (name == "value") entry.valueArguments.filter { !it.isNamed() }.getOrNull(position)?.getArgumentExpression() else null
    private fun ids(entry: KtAnnotationEntry): List<String> {
        val positional = entry.valueArguments.filter { !it.isNamed() }.mapNotNull { it.getArgumentExpression() }
        if (positional.size > 1) return positional.map(::id)
        val value = argument(entry, "value") ?: return listOf("@root")
        val items = when (value) { is KtCollectionLiteralExpression -> value.getInnerExpressions().toList(); else -> listOf(value) }
        return items.map(::id)
    }
    private fun kotlinType(java: String): String = when (java) {
        "void" -> "Unit"; "boolean" -> "Boolean"; "int" -> "Int"; "long" -> "Long"; "float" -> "Float"; "double" -> "Double"; "java.lang.CharSequence" -> "CharSequence"; "android.widget.AdapterView<?>" -> "android.widget.AdapterView<*>"; else -> java
    }
    fun planInContext(file: KtFile, context: Set<String>, bindingTargets: Set<String>): ButterKnifeMigration.Result = try {
        transform(file, context, bindingTargets)
    } catch (e: IllegalArgumentException) { ButterKnifeMigration.Result(file.text, null, e.message) }

    private fun transform(file: KtFile, context: Set<String>, bindingTargets: Set<String>): ButterKnifeMigration.Result {
        requireSupported(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) == null, "Kotlin 文件存在语法错误")
        val edits = mutableListOf<Edit>()
        fun replace(element: PsiElement, text: String = "") { edits += Edit(element.textRange.startOffset, element.textRange.endOffset, text) }
        val bindingCalls = bindingCalls(file)
        val owners = PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java).filter { it.toLightClass()?.qualifiedName in context }
        val supportedAnnotations = ButterKnifeMigration.resources + ButterKnifeMigration.listeners.keys + setOf("BindView", "BindViews", "Optional")
        PsiTreeUtil.findChildrenOfType(file, KtAnnotationEntry::class.java).forEach { annotation ->
            val kind = annotationName(annotation)
            requireSupported(kind == null || kind in supportedAnnotations, "暂不支持的注解：@$kind")
        }
        owners.forEach { owner ->
            ProgressManager.checkCanceled()
            val light = owner.toLightClass() ?: throw IllegalArgumentException("Kotlin 类未解析：${owner.name}")
            requireSupported(owner is KtClass && !owner.isInterface() && !owner.isEnum() && !owner.isAnnotation(), "对象、接口或匿名类暂不支持绑定迁移")
            requireSupported(owner.declarations.none { (it as? KtNamedDeclaration)?.name?.startsWith(PREFIX) == true } && light.allMethods.none { it.name.startsWith(PREFIX) }, "生成成员名称冲突；已有迁移代码需要人工确认")
            MixedButterKnifeMigration.validateOwner(light, context, bindingTargets)
            val parent = MixedButterKnifeMigration.bindingParent(light, context)
            val init = StringBuilder()
            val clear = StringBuilder()
            val backing = StringBuilder()
            var number = 0
            fun lookup(resource: String, type: String, required: Boolean): String {
                val local = "${PREFIX}View${number++}"
                val expression = if (resource == "@root") "root as $type?" else "root.findViewById<android.view.View>($resource) as $type?"
                init.append("        val $local = $expression${if (required) " ?: throw IllegalStateException(\"Required view not found: $resource\")" else ""}\n")
                return local
            }
            owner.declarations.filterIsInstance<KtProperty>().forEach { property ->
                val annotations = property.annotationEntries.filter { annotationName(it) != null }
                if (annotations.isEmpty()) return@forEach
                requireSupported(annotations.size == 1, "属性 ${property.name} 有多个绑定注解")
                val annotation = annotations.single()
                val kind = annotationName(annotation)!!
                requireSupported(property.isVar && property.delegate == null && property.getter == null && property.setter == null && !property.hasModifier(KtTokens.CONST_KEYWORD), "绑定属性 ${property.name} 必须是无自定义访问器的 var")
                requireSupported(annotation.useSiteTarget?.text !in setOf("get", "set", "setparam", "param"), "绑定注解必须作用于属性或字段：${property.name}")
                val type = property.typeReference?.text ?: throw IllegalArgumentException("绑定属性 ${property.name} 需要显式类型")
                val nullable = type.endsWith("?")
                val bareType = type.removeSuffix("?")
                val name = property.nameIdentifier?.text ?: throw IllegalArgumentException("绑定属性没有名称")
                replace(annotation)
                property.annotationEntries.filter(::isJvmField).forEach { replace(it) }
                val resource = ids(annotation)
                when (kind) {
                    "BindView" -> {
                        val psiType = classType(file, bareType)
                        requireSupported(com.intellij.psi.util.InheritanceUtil.isInheritor(psiType, "android.view.View"), "${property.name} 类型不是可解析的 View")
                        init.append("        this.$name = ${lookup(resource.single(), bareType, !nullable)}\n")
                    }
                    "BindViews" -> {
                        val elementType = bareType.substringAfter('<', "").substringBeforeLast('>', "").trim()
                        requireSupported(bareType.substringBefore('<') in setOf("List", "MutableList", "Array", "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.Array") && elementType.isNotEmpty(), "@BindViews 仅支持 View 数组或列表")
                        requireSupported(com.intellij.psi.util.InheritanceUtil.isInheritor(classType(file, elementType), "android.view.View"), "@BindViews 元素必须是 View")
                        val found = resource.map { lookup(it, elementType, !nullable) }
                        val list = if (nullable) "listOfNotNull(${found.joinToString()})" else "listOf(${found.joinToString()})"
                        val value = if (bareType.substringBefore('<').endsWith("Array")) "$list.toTypedArray()" else if (bareType.contains("MutableList")) "$list.toMutableList()" else list
                        init.append("        this.$name = $value\n")
                    }
                    in ButterKnifeMigration.resources -> {
                        val resourceId = resource.single()
                        requireSupported(resourceId != "@root", "资源绑定缺少有效资源 ID")
                        val expression = when (kind) {
                            "BindString" -> "root.resources.getString($resourceId)"
                            "BindBool" -> "root.resources.getBoolean($resourceId)"
                            "BindInt" -> "root.resources.getInteger($resourceId)"
                            "BindDimen" -> if (bareType == "Int") "root.resources.getDimensionPixelSize($resourceId)" else "root.resources.getDimension($resourceId)"
                            "BindArray" -> when (bareType) { "Array<String>" -> "root.resources.getStringArray($resourceId)"; "IntArray" -> "root.resources.getIntArray($resourceId)"; "Array<CharSequence>" -> "root.resources.getTextArray($resourceId)"; "TypedArray", "android.content.res.TypedArray" -> "root.resources.obtainTypedArray($resourceId)"; else -> throw IllegalArgumentException("@BindArray 类型不支持：$type") }
                            "BindColor" -> if (bareType == "Int") "if (android.os.Build.VERSION.SDK_INT >= 23) root.context.getColor($resourceId) else root.resources.getColor($resourceId)" else "if (android.os.Build.VERSION.SDK_INT >= 23) root.context.getColorStateList($resourceId) else root.resources.getColorStateList($resourceId)"
                            "BindDrawable" -> {
                                val base = "(if (android.os.Build.VERSION.SDK_INT >= 21) root.context.getDrawable($resourceId) else root.resources.getDrawable($resourceId))"
                                val tint = argument(annotation, "tint")
                                if (tint == null || tint.text == "0") "$base!!" else {
                                    val compat = compatClass(file, "androidx.core.graphics.drawable.DrawableCompat", "android.support.v4.graphics.drawable.DrawableCompat")
                                    val tintId = id(tint)
                                    "$compat.wrap($base!!).mutate().also { $compat.setTint(it, if (android.os.Build.VERSION.SDK_INT >= 23) root.context.getColor($tintId) else root.resources.getColor($tintId)) }"
                                }
                            }
                            "BindFont" -> {
                                val compat = compatClass(file, "androidx.core.content.res.ResourcesCompat", "android.support.v4.content.res.ResourcesCompat")
                                val font = "$compat.getFont(root.context, $resourceId)!!"
                                val style = argument(annotation, "style")
                                if (style == null || style.text == "0") font else "android.graphics.Typeface.create($font, ${style.text})"
                            }
                            "BindBitmap" -> "android.graphics.BitmapFactory.decodeResource(root.resources, $resourceId)!!"
                            "BindAnim" -> "android.view.animation.AnimationUtils.loadAnimation(root.context, $resourceId)"
                            "BindFloat" -> "android.util.TypedValue().also { root.resources.getValue($resourceId, it, true) }.float"
                            else -> throw IllegalArgumentException("不支持 @$kind")
                        }
                        init.append("        this.$name = $expression\n")
                    }
                    else -> throw IllegalArgumentException("属性注解位置不支持：@$kind")
                }
                if (kind in setOf("BindView", "BindViews")) {
                    if (nullable) clear.append("                this@${owner.name}.$name = null\n")
                    else if (property.hasModifier(KtTokens.LATEINIT_KEYWORD)) {
                        val local = "${PREFIX}Backing${number++}"
                        backing.append("    private var $local: $bareType? = null\n")
                        property.modifierList?.getModifier(KtTokens.LATEINIT_KEYWORD)?.let { replace(it) }
                        edits += Edit(property.textRange.endOffset, property.textRange.endOffset, "\n        get() = $local ?: throw UninitializedPropertyAccessException(\"${property.name}\")\n        set(value) { $local = value }\n")
                        clear.append("                $local = null\n")
                    } else throw IllegalArgumentException("非空控件属性 $name 需使用 lateinit var，或可空 var，以保留解绑行为")
                }
            }
            data class Handler(val method: KtNamedFunction, val annotation: KtAnnotationEntry, val callback: ButterKnifeMigration.Callback)
            data class Group(val id: String, val spec: ButterKnifeMigration.Listener, val handlers: MutableList<Handler>)
            val groups = linkedMapOf<Pair<String, String>, Group>()
            owner.declarations.filterIsInstance<KtNamedFunction>().forEach { method ->
                val annotations = method.annotationEntries.filter { annotationName(it) in ButterKnifeMigration.listeners.keys }
                method.annotationEntries.firstOrNull { annotationName(it) == "Optional" }?.let { optional ->
                    requireSupported(annotations.isNotEmpty(), "@Optional 必须用于事件方法")
                    replace(optional)
                }
                annotations.forEach { annotation ->
                    val kind = annotationName(annotation)!!
                    requireSupported(!method.hasModifier(KtTokens.SUSPEND_KEYWORD) && method.receiverTypeReference == null, "事件方法不能是 suspend 或扩展函数")
                    var spec = ButterKnifeMigration.listeners.getValue(kind)
                    if (kind == "OnPageChange" && JavaPsiFacade.getInstance(file.project).findClass(spec.target, file.resolveScope) == null)
                        spec = spec.copy(target = spec.target.replace("androidx.viewpager.widget", "android.support.v4.view"), type = spec.type.replace("androidx.viewpager.widget", "android.support.v4.view"))
                    val selected = argument(annotation, "callback")?.text?.substringAfterLast('.')
                    val callback = if (spec.callbacks.size == 1) spec.callbacks.single() else spec.callbacks.firstOrNull { it.key == (selected ?: when (kind) { "OnTextChanged" -> "TEXT_CHANGED"; "OnPageChange" -> "PAGE_SELECTED"; else -> "ITEM_SELECTED" }) }
                        ?: throw IllegalArgumentException("不支持的 @$kind callback")
                    requireSupported(method.typeReference != null || method.hasBlockBody(), "表达式事件方法 ${method.name} 需要显式返回类型")
                    val returnType = method.typeReference?.text ?: "Unit"
                    requireSupported(returnType in setOf("Unit", kotlinType(callback.returns)), "事件方法返回类型不匹配：${method.name}")
                    replace(annotation)
                    ids(annotation).forEach { resource -> groups.getOrPut(kind to resource) { Group(resource, spec, mutableListOf()) }.handlers += Handler(method, annotation, callback) }
                }
            }
            groups.values.forEach { group ->
                val required = group.handlers.any { it.method.annotationEntries.none { a -> annotationName(a) == "Optional" } }
                val target = lookup(group.id, kotlinType(group.spec.target), required)
                val listener = "${PREFIX}Listener${number++}"
                init.append("        val $listener = object : ${group.spec.type} {\n")
                group.spec.callbacks.forEach { callback ->
                    val params = callback.params.mapIndexed { index, type -> "p$index: ${kotlinType(type)}${if (type !in setOf("int", "long", "float", "boolean") && group.spec.type != "android.view.View.OnClickListener") "?" else ""}" }
                    init.append("            override fun ${callback.name}(${params.joinToString()}): ${kotlinType(callback.returns)} {\n")
                    val handlers = group.handlers.filter { it.callback == callback }
                    requireSupported(callback.returns == "void" || handlers.none { it.method.typeReference?.text == "Boolean" } || handlers.size == 1, "多个返回值事件绑定同一控件")
                    handlers.forEach { handler ->
                        val available = callback.params.indices.toMutableList()
                        val args = handler.method.valueParameters.map { parameter ->
                            val parameterText = parameter.typeReference?.text ?: throw IllegalArgumentException("事件参数需要显式类型")
                            val parameterType = classType(file, parameterText)
                            val slot = available.firstOrNull { index ->
                                val sourceType = JavaPsiFacade.getElementFactory(file.project).createTypeFromText(callback.params[index], light)
                                TypeConversionUtil.erasure(parameterType) == TypeConversionUtil.erasure(sourceType) ||
                                    (com.intellij.psi.util.InheritanceUtil.isInheritor(parameterType, "android.view.View") && TypeConversionUtil.isAssignable(sourceType, parameterType)) ||
                                    (parameterType as? PsiClassType)?.resolve()?.isInterface == true
                            } ?: throw IllegalArgumentException("事件参数类型不匹配：${handler.method.name}($parameterText)")
                            available.remove(slot)
                            if (callback.params[slot] in setOf("int", "long", "float", "boolean")) "p$slot" else "p$slot as $parameterText"
                        }
                        val methodName = handler.method.nameIdentifier!!.text
                        val invocation = "this@${owner.name}.$methodName(${args.joinToString()})"
                        init.append("                ${if (handler.method.typeReference?.text == "Boolean") "return " else ""}$invocation\n")
                    }
                    if (callback.returns != "void" && handlers.none { it.method.typeReference?.text == "Boolean" }) init.append("                return ${callback.defaultValue}\n")
                    init.append("            }\n")
                }
                init.append("        }\n        $target?.${group.spec.setter}($listener)\n")
                clear.append("                $target?.${group.spec.remover ?: group.spec.setter}(${if (group.spec.remover == null) "null" else listener})\n")
            }
            val noArgs = if (light.isInheritor(JavaPsiFacade.getInstance(file.project).findClass("android.app.Activity", file.resolveScope) ?: light, true) || light.isInheritor(JavaPsiFacade.getInstance(file.project).findClass("android.app.Dialog", file.resolveScope) ?: light, true)) "    ${if (parent != null) "override" else "open"} fun ${PREFIX}BindViews(): java.lang.Runnable = ${PREFIX}BindViews(window.decorView)\n" else if (com.intellij.psi.util.InheritanceUtil.isInheritor(light, "android.view.View")) "    ${if (parent != null) "override" else "open"} fun ${PREFIX}BindViews(): java.lang.Runnable = ${PREFIX}BindViews(this)\n" else ""
            val generated = """

$backing$noArgs
    ${if (parent != null) "override" else "open"} fun ${PREFIX}BindViews(root: android.view.View): java.lang.Runnable {
        val cleanups = java.util.ArrayList<java.lang.Runnable>()
        ${PREFIX}BindOwnViews(root, cleanups)
        return object : java.lang.Runnable {
            private var pending: MutableList<java.lang.Runnable>? = cleanups
            override fun run() {
                val actions = pending ?: throw IllegalStateException("Bindings already cleared.")
                if (actions.isEmpty()) return
                pending = null
                for (i in actions.indices.reversed()) actions[i].run()
                actions.clear()
            }
        }
    }

    protected ${if (parent != null) "override" else "open"} fun ${PREFIX}BindOwnViews(root: android.view.View, cleanups: MutableList<java.lang.Runnable>) {
${if (parent != null) "        super.${PREFIX}BindOwnViews(root, cleanups)\n" else ""}$init${if (clear.isNotEmpty()) "        cleanups.add(java.lang.Runnable {\n$clear        })\n" else ""}    }
"""
            val body = owner.body
            if (body?.rBrace != null) edits += Edit(body.rBrace!!.textRange.startOffset, body.rBrace!!.textRange.startOffset, generated)
            else edits += Edit(owner.textRange.endOffset, owner.textRange.endOffset, " {$generated\n}")
        }
        bindingCalls.forEach { call ->
            val args = call.valueArguments.map { it.getArgumentExpression() ?: throw IllegalArgumentException("不完整的 bind 参数") }
            requireSupported(args.size in 1..2 && call.lambdaArguments.isEmpty(), "不支持此 bind 参数数量")
            val target = targetClass(call)
            requireSupported(target?.qualifiedName in context, "绑定目标未参与迁移，请检查关联类的跳过原因")
            val root = if (args.size == 2) args[1].text else ""
            replace(fullCall(call), "(${args[0].text}).${PREFIX}BindViews($root)")
        }
        val unbinders = PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java).filter { property ->
            val type = property.typeReference?.text?.removeSuffix("?")
            val full = type?.let { imports(file, it) ?: it }
            full == "butterknife.Unbinder" || (type == "Unbinder" && file.importDirectives.any { it.isAllUnder && it.importedFqName?.asString() == "butterknife" }) || property.initializer?.let { expression -> bindingCalls.any { fullCall(it) == expression } } == true
        }
        unbinders.forEach { property ->
            property.typeReference?.let { replace(it, "java.lang.Runnable${if (it.text.endsWith("?")) "?" else ""}") }
            val uses = PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java).filter { it.mainReference.resolve() == property }
            uses.forEach { ref ->
                val qualified = (if (ref.parent is KtPostfixExpression) ref.parent.parent else ref.parent) as? KtQualifiedExpression
                val selector = qualified?.selectorExpression as? KtCallExpression
                val parent = ref.parent
                val allowed = (selector?.calleeExpression?.text == "unbind" && selector.valueArguments.isEmpty()) ||
                    (parent is KtBinaryExpression && (parent.operationToken in setOf(KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQ))) ||
                    (qualified?.selectorExpression?.text == "let" || (qualified?.selectorExpression as? KtCallExpression)?.calleeExpression?.text in setOf("let", "also"))
                requireSupported(allowed, "Unbinder 被传递、返回或用于其他表达式，需先迁移该 API：${ref.text}")
            }
        }
        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).forEach { call ->
            val callee = call.calleeExpression as? KtNameReferenceExpression ?: return@forEach
            val method = callee.mainReference.resolve() as? PsiMethod
            if (method?.name == "unbind" && method.containingClass?.qualifiedName == "butterknife.Unbinder") {
                val receiver = (call.parent as? KtQualifiedExpression)?.receiverExpression
                val reference = (if (receiver is KtPostfixExpression) receiver.baseExpression else receiver) as? KtNameReferenceExpression
                val declaration = reference?.mainReference?.resolve()
                requireSupported(declaration in unbinders || receiver?.text?.contains("Unbinder.EMPTY") == true, "解绑对象不在本次可迁移范围：${receiver?.text}")
                replace(callee, "run")
            }
        }
        PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java).forEach { ref ->
            if (PsiTreeUtil.getParentOfType(ref, KtImportDirective::class.java) != null) return@forEach
            val target = ref.mainReference.resolve()
            if (target is PsiField && target.name == "EMPTY" && target.containingClass?.qualifiedName == "butterknife.Unbinder") {
                val qualified = (ref.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == ref }
                replace(qualified ?: ref, "java.lang.Runnable {}")
            }
        }
        val removedImports = file.importDirectives.filter { it.importedFqName?.asString()?.let { name -> name == "butterknife" || name.startsWith("butterknife.") } == true }
        removedImports.forEach { replace(it) }
        file.importDirectives.filter { it.importedFqName?.asString() == "kotlin.jvm.JvmField" }.forEach { directive ->
            val name = directive.aliasName ?: "JvmField"
            val remaining = PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java).any { ref ->
                ref.getReferencedName() == name && PsiTreeUtil.getParentOfType(ref, KtImportDirective::class.java) == null &&
                    edits.none { it.start <= ref.textRange.startOffset && it.end >= ref.textRange.endOffset }
            }
            if (!remaining) replace(directive)
        }
        // Refuse unknown ButterKnife APIs and R2 business references rather than deleting their imports.
        PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java).forEach { ref ->
            if (PsiTreeUtil.getParentOfType(ref, KtImportDirective::class.java) != null) return@forEach
            val target = ref.mainReference.resolve()
            val owner = when (target) { is PsiClass -> target; is PsiMember -> target.containingClass; else -> null }
            val name = owner?.qualifiedName.orEmpty()
            if (name.startsWith("butterknife.") && edits.none { it.start <= ref.textRange.startOffset && it.end >= ref.textRange.endOffset })
                throw IllegalArgumentException("仍有未转换的 ButterKnife 引用：${ref.text}")
            if ((owner?.name == "R2" || owner?.containingClass?.name == "R2") && edits.none { it.start <= ref.textRange.startOffset && it.end >= ref.textRange.endOffset })
                throw IllegalArgumentException("R2 在绑定注解之外仍有使用，需先迁移该业务代码")
        }
        file.importDirectives.filter { it.importedFqName?.asString()?.let { name -> name.endsWith(".R2") || name.contains(".R2.") } == true }.forEach { replace(it) }
        val sorted = edits.distinct().sortedBy { it.start }
        requireSupported(sorted.zipWithNext().all { (a,b) -> a.end <= b.start }, "改动范围重叠，需人工处理")
        if (sorted.isEmpty()) return ButterKnifeMigration.Result(file.text, null, "没有找到可转换的 ButterKnife 用法")
        val after = StringBuilder(file.text)
        sorted.asReversed().forEach { after.replace(it.start, it.end, it.text) }
        return ButterKnifeMigration.Result(file.text, after.toString())
    }
    private fun compatClass(file: KtFile, modern: String, legacy: String): String = listOf(modern,legacy).firstOrNull { JavaPsiFacade.getInstance(file.project).findClass(it, file.resolveScope) != null }
        ?: throw IllegalArgumentException("字体或 Drawable tint 需要项目已有 AndroidX Core / support-compat")
}
