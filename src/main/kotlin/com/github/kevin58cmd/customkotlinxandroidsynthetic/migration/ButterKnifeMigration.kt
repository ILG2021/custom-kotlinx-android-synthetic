package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil

/** Plans source changes only. Related classes are accepted or rejected together. */
object ButterKnifeMigration {
    data class Result(val before: String, val after: String?, val reason: String? = null)
    private data class Edit(val start: Int, val end: Int, val text: String)
    private class Unsupported(message: String) : RuntimeException(message)
    private fun supported(value: Boolean, reason: String) { if (!value) throw Unsupported(reason) }
    private const val PREFIX = "syntheticCompat"
    internal data class Callback(val key: String, val name: String, val params: List<String>, val returns: String = "void", val defaultValue: String = "")
    internal data class Listener(val target: String, val type: String, val setter: String, val callbacks: List<Callback>, val remover: String? = null)
    private fun callback(name: String, vararg params: String, returns: String = "void", default: String = "") = Callback("", name, params.toList(), returns, default)
    internal val listeners = mapOf(
        "OnClick" to Listener("android.view.View", "android.view.View.OnClickListener", "setOnClickListener", listOf(callback("onClick", "android.view.View"))),
        "OnLongClick" to Listener("android.view.View", "android.view.View.OnLongClickListener", "setOnLongClickListener", listOf(callback("onLongClick", "android.view.View", returns = "boolean", default = "true"))),
        "OnTouch" to Listener("android.view.View", "android.view.View.OnTouchListener", "setOnTouchListener", listOf(callback("onTouch", "android.view.View", "android.view.MotionEvent", returns = "boolean", default = "true"))),
        "OnFocusChange" to Listener("android.view.View", "android.view.View.OnFocusChangeListener", "setOnFocusChangeListener", listOf(callback("onFocusChange", "android.view.View", "boolean"))),
        "OnCheckedChanged" to Listener("android.widget.CompoundButton", "android.widget.CompoundButton.OnCheckedChangeListener", "setOnCheckedChangeListener", listOf(callback("onCheckedChanged", "android.widget.CompoundButton", "boolean"))),
        "OnEditorAction" to Listener("android.widget.TextView", "android.widget.TextView.OnEditorActionListener", "setOnEditorActionListener", listOf(callback("onEditorAction", "android.widget.TextView", "int", "android.view.KeyEvent", returns = "boolean", default = "true"))),
        "OnItemClick" to Listener("android.widget.AdapterView<?>", "android.widget.AdapterView.OnItemClickListener", "setOnItemClickListener", listOf(callback("onItemClick", "android.widget.AdapterView<?>", "android.view.View", "int", "long"))),
        "OnItemLongClick" to Listener("android.widget.AdapterView<?>", "android.widget.AdapterView.OnItemLongClickListener", "setOnItemLongClickListener", listOf(callback("onItemLongClick", "android.widget.AdapterView<?>", "android.view.View", "int", "long", returns = "boolean", default = "true"))),
        "OnItemSelected" to Listener("android.widget.AdapterView<?>", "android.widget.AdapterView.OnItemSelectedListener", "setOnItemSelectedListener", listOf(
            Callback("ITEM_SELECTED", "onItemSelected", listOf("android.widget.AdapterView<?>", "android.view.View", "int", "long")),
            Callback("NOTHING_SELECTED", "onNothingSelected", listOf("android.widget.AdapterView<?>")))),
        "OnTextChanged" to Listener("android.widget.TextView", "android.text.TextWatcher", "addTextChangedListener", listOf(
            Callback("BEFORE_TEXT_CHANGED", "beforeTextChanged", listOf("java.lang.CharSequence", "int", "int", "int")),
            Callback("TEXT_CHANGED", "onTextChanged", listOf("java.lang.CharSequence", "int", "int", "int")),
            Callback("AFTER_TEXT_CHANGED", "afterTextChanged", listOf("android.text.Editable"))), "removeTextChangedListener"),
        "OnPageChange" to Listener("androidx.viewpager.widget.ViewPager", "androidx.viewpager.widget.ViewPager.OnPageChangeListener", "addOnPageChangeListener", listOf(
            Callback("PAGE_SELECTED", "onPageSelected", listOf("int")),
            Callback("PAGE_SCROLLED", "onPageScrolled", listOf("int", "float", "int")),
            Callback("PAGE_SCROLL_STATE_CHANGED", "onPageScrollStateChanged", listOf("int"))), "removeOnPageChangeListener")
    )
    internal val resources = setOf("BindString", "BindBool", "BindInt", "BindArray", "BindDimen", "BindFloat", "BindColor", "BindDrawable", "BindBitmap", "BindAnim", "BindFont")
    private val annotationNames = resources + listeners.keys + setOf("BindView", "BindViews", "Optional")
    private fun annotationName(a: PsiAnnotation) = a.qualifiedName?.takeIf { it.startsWith("butterknife.") }?.substringAfterLast('.')
    private fun classes(file: PsiJavaFile) = PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java).toList()
    private fun owns(owner: PsiClass, element: PsiElement) = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) == owner
    private fun calls(file: PsiJavaFile) = PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
    private fun isBind(call: PsiMethodCallExpression) = call.methodExpression.referenceName == "bind" && call.resolveMethod()?.containingClass?.qualifiedName == "butterknife.ButterKnife"
    private fun targetClass(call: PsiMethodCallExpression) = (call.argumentList.expressions.firstOrNull()?.type as? PsiClassType)?.resolve()
    private fun hasBindings(owner: PsiClass) = owner.fields.any { it.annotations.any { a -> annotationName(a) != null } } || owner.methods.any { it.annotations.any { a -> annotationName(a) != null } }

    fun relatedFiles(selected: PsiJavaFile, candidates: List<PsiJavaFile>): List<PsiJavaFile> =
        MixedButterKnifeMigration.relatedFiles(selected, candidates).map { it as PsiJavaFile }

    fun plan(file: PsiJavaFile): Result = planBatch(listOf(file)).getValue(file)

    fun planBatch(files: List<PsiJavaFile>): Map<PsiJavaFile, Result> {
        val results = MixedButterKnifeMigration.planBatch(files)
        return files.associateWith { results.getValue(it) }
    }
    internal fun planInContext(file: PsiJavaFile, context: Set<String>, bindingTargets: Set<String>): Result =
        try { transform(file, context, bindingTargets) }
        catch (e: Unsupported) { Result(file.text, null, e.message) }
        catch (e: IllegalArgumentException) { Result(file.text, null, e.message) }
    private fun transform(file: PsiJavaFile, context: Set<String>, bindingTargets: Set<String>): Result {
        val before = file.text
        if (!before.contains("butterknife") && !before.contains("@Bind") && !before.contains("@On")) return Result(before, null)
        supported(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) == null, "Java 文件存在语法错误")
        val edits = mutableListOf<Edit>()
        fun replace(element: PsiElement, text: String = "") { edits += Edit(element.textRange.startOffset, element.textRange.endOffset, text) }
        val annotations = PsiTreeUtil.findChildrenOfType(file, PsiAnnotation::class.java).filter { annotationName(it) != null }
        val unsupported = annotations.mapNotNull(::annotationName).filter { it !in annotationNames }.distinct()
        supported(unsupported.isEmpty(), "暂不支持的注解：${unsupported.joinToString { "@$it" }}")
        val bindingCalls = calls(file).filter(::isBind)

        val helperName = "SyntheticCompatUnbinder_" + java.security.MessageDigest.getInstance("SHA-256").digest((file.virtualFile?.path ?: file.name).toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        supported(classes(file).none { it.name == helperName }, "生成的解绑类名称冲突")
        val owners = classes(file).filter { it.qualifiedName in context }
        owners.forEach { owner -> generateOwner(file, owner, context, bindingTargets, helperName, edits) }
        bindingCalls.forEach { call ->
            val args = call.argumentList.expressions
            supported(args.size in 1..2, "不支持此 bind 参数数量")
            val target = args[0]
            val targetOwner = targetClass(call)
            supported(targetOwner?.qualifiedName in context, "绑定目标不在本次可迁移范围，请选择包含基类和目标类的项目根目录")
            val root = if (args.size == 2) args[1] else target

            val rootExpression = when {
                InheritanceUtil.isInheritor(root.type, "android.view.View") -> root.text
                InheritanceUtil.isInheritor(root.type, "android.app.Activity") || InheritanceUtil.isInheritor(root.type, "android.app.Dialog") -> "(${root.text}).getWindow().getDecorView()"
                else -> throw Unsupported("绑定根节点未解析或不是 View、Activity、Dialog")
            }
            replace(call, "(${target.text}).${PREFIX}BindViews(${if (args.size == 1) "" else rootExpression})")
        }
        // Runnable preserves local variables, fields, null guards and independent binding handles.
        val variables = PsiTreeUtil.findChildrenOfType(file, PsiVariable::class.java).filter { it.type.canonicalText == "butterknife.Unbinder" }
        variables.forEach { variable ->
            supported(variable !is PsiParameter, "Unbinder 用于方法参数，需先迁移该 API")
            if (variable is PsiField) {
                supported(!variable.hasModifierProperty(PsiModifier.STATIC), "静态 Unbinder 需人工处理跨实例绑定")
                supported(ReferencesSearch.search(variable).findAll().all { it.element.containingFile == file }, "Unbinder 被其他文件引用，需一起迁移该 API")
            }
            replace(variable.typeElement ?: throw Unsupported("Unbinder 类型声明不完整"), "java.lang.Runnable")
            PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java).filter { it.resolve() == variable }.forEach { ref ->
                val allowed = when (val use = ref.parent) {
                    is PsiReferenceExpression -> use.referenceName == "unbind" && (use.parent as? PsiMethodCallExpression)?.argumentList?.expressions?.isEmpty() == true
                    is PsiBinaryExpression -> use.operationTokenType in setOf(JavaTokenType.EQEQ, JavaTokenType.NE) && (use.lOperand.text == "null" || use.rOperand?.text == "null")
                    is PsiAssignmentExpression -> {
                        fun bindingValue(value: PsiExpression?): Boolean = value?.text == "null" || (value is PsiMethodCallExpression && isBind(value)) ||
                            (value is PsiReferenceExpression && ((value.resolve() as? PsiVariable) in variables || (value.resolve() as? PsiField)?.let { it.name == "EMPTY" && it.containingClass?.qualifiedName == "butterknife.Unbinder" } == true))
                        if (use.lExpression == ref) bindingValue(use.rExpression) else (use.lExpression as? PsiReferenceExpression)?.resolve() in variables
                    }
                    is PsiVariable -> use in variables
                    else -> false
                }
                supported(allowed, "Unbinder 被传递、返回或用于其他表达式，需先迁移该 API：${ref.text}")
                val access = ref.parent as? PsiReferenceExpression
                val invocation = access?.parent as? PsiMethodCallExpression
                if (invocation != null && access.referenceName == "unbind") replace(access.referenceNameElement!!, "run")
            }
        }
        PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java).filter { ref ->
            val resolved = ref.resolve() as? PsiField
            resolved?.name == "EMPTY" && resolved.containingClass?.qualifiedName == "butterknife.Unbinder"
        }.forEach { replace(it, "new java.lang.Runnable() { @Override public void run() {} }") }
        val references = PsiTreeUtil.findChildrenOfType(file, PsiJavaCodeReferenceElement::class.java).filter { !PsiTreeUtil.isAncestor(file.importList, it, false) }
        references.forEach { ref ->
            ProgressManager.checkCanceled()
            val target = ref.resolve()
            val name = when (target) { is PsiClass -> target.qualifiedName; is PsiMember -> target.containingClass?.qualifiedName; else -> null }
            if (name?.startsWith("butterknife.") == true) supported(edits.any { it.start <= ref.textRange.startOffset && it.end >= ref.textRange.endOffset } || (ref is PsiReferenceExpression && ref.referenceName == "unbind" && ref.referenceNameElement?.let { token -> edits.any { it.start == token.textRange.startOffset && it.end == token.textRange.endOffset } } == true), "仍有未转换的 ButterKnife 引用：${ref.text}")
        }
        file.importList?.allImportStatements?.filter { it.importReference?.qualifiedName?.let { name -> name == "butterknife" || name.startsWith("butterknife.") } == true }?.forEach { replace(it) }
        val r2References = references.filter { ref ->
            val resolved = ref.resolve()
            var ownerClass = when (resolved) { is PsiClass -> resolved; is PsiMember -> resolved.containingClass; else -> null }
            var isR2 = false
            while (ownerClass != null) { if (ownerClass.name == "R2") isR2 = true; ownerClass = ownerClass.containingClass }
            isR2
        }
        supported(r2References.all { ref -> edits.any { it.start <= ref.textRange.startOffset && it.end >= ref.textRange.endOffset } }, "R2 在绑定注解之外仍有使用，需先迁移该业务代码")
        file.importList?.allImportStatements?.filter { it.importReference?.qualifiedName?.let { name -> name.endsWith(".R2") || name.contains(".R2.") } == true }?.forEach { replace(it) }
        if (owners.isNotEmpty()) {
            val cleanupClass = """

final class $helperName implements java.lang.Runnable {
    private java.util.List<java.lang.Runnable> cleanups;
    $helperName(java.util.List<java.lang.Runnable> cleanups) { this.cleanups = cleanups; }
    @Override public void run() {
        if (cleanups == null) throw new IllegalStateException("Bindings already cleared.");
        if (cleanups.isEmpty()) return;
        java.util.List<java.lang.Runnable> pending = cleanups;
        cleanups = null;
        for (int i = pending.size() - 1; i >= 0; i--) pending.get(i).run();
        pending.clear();
    }
}
"""
            edits += Edit(before.length, before.length, cleanupClass)
        }
        val sorted = edits.distinct().sortedBy { it.start }
        supported(sorted.zipWithNext().all { (a, b) -> a.end <= b.start }, "改动范围重叠，需人工处理")
        val after = StringBuilder(before)
        sorted.asReversed().forEach { after.replace(it.start, it.end, it.text) }
        return Result(before, after.toString())
    }

    private fun resourceId(value: PsiAnnotationMemberValue?): String {
        if (value?.text == "-1") return "@root"
        val ref = value as? PsiReferenceExpression ?: throw Unsupported("资源 ID 未解析：${value?.text}")
        val field = ref.resolve() as? PsiField ?: throw Unsupported("资源 ID 未解析：${ref.text}")
        if (field.name == "NO_ID" && field.containingClass?.qualifiedName == "android.view.View") return "@root"
        val parent = field.containingClass
        supported(parent?.containingClass?.name in setOf("R", "R2"), "资源不是 R 或 R2：${ref.text}")
        val name = "${parent!!.qualifiedName}.${field.name}"
        return name.replace(".R2.", ".R.")
    }
    private fun ids(a: PsiAnnotation): List<String> {
        val value = a.findAttributeValue("value")
        val values = if (value is PsiArrayInitializerMemberValue) value.initializers.toList() else listOf(value)
        supported(values.isNotEmpty(), "@${annotationName(a)} 没有资源 ID")
        return values.map(::resourceId)
    }
    private fun nullable(field: PsiField) = field.annotations.any { it.qualifiedName?.substringAfterLast('.') == "Nullable" || it.nameReferenceElement?.referenceName == "Nullable" }
    private fun generateOwner(file: PsiJavaFile, owner: PsiClass, context: Set<String>, bindingTargets: Set<String>, helperName: String, edits: MutableList<Edit>) {
        supported(owner.name != null && owner.rBrace != null && !owner.isInterface && !owner.isEnum && !owner.isAnnotationType, "匿名类或此类声明不支持自动生成绑定")
        supported(owner.extendsListTypes.all { it.resolve() != null }, "父类未解析，请完成 Sync")
        supported(owner.allFields.none { it.name.startsWith(PREFIX) } && owner.allMethods.none { it.name.startsWith(PREFIX) }, "生成成员名称冲突；已有迁移代码需要人工确认")
        MixedButterKnifeMigration.validateOwner(owner, context, bindingTargets)
        val parent = MixedButterKnifeMigration.bindingParent(owner, context)
        fun replace(element: PsiElement, text: String = "") { edits += Edit(element.textRange.startOffset, element.textRange.endOffset, text) }
        val init = StringBuilder()
        val clear = StringBuilder()
        fun compatClass(androidX: String, legacy: String): String = listOf(androidX, legacy).firstOrNull {
            JavaPsiFacade.getInstance(file.project).findClass(it, file.resolveScope) != null
        } ?: throw Unsupported("字体或 Drawable tint 需要项目已有 AndroidX Core / support-compat")
        var number = 0
        fun findView(id: String) = if (id == "@root") "root" else "root.findViewById($id)"
        fun view(id: String, type: String, required: Boolean): String {
            val local = "${PREFIX}View${number++}"
            init.append("        final $type $local = ($type) ${findView(id)};\n")
            if (required) init.append("        if ($local == null) throw new IllegalStateException(\"Required view not found: $id\");\n")
            return local
        }
        owner.fields.forEach { field ->
            val annotations = field.annotations.filter { annotationName(it) != null }
            if (annotations.isEmpty()) return@forEach
            supported(annotations.size == 1, "字段 ${field.name} 有多个绑定注解")
            val a = annotations.single()
            val kind = annotationName(a)!!
            supported(kind == "BindView" || kind == "BindViews" || kind in resources, "字段注解位置不支持：@$kind")
            supported(!field.hasModifierProperty(PsiModifier.STATIC) && !field.hasModifierProperty(PsiModifier.FINAL), "绑定字段 ${field.name} 不能为 static 或 final")
            replace(a)
            val type = field.type.canonicalText
            if (kind == "BindView") {
                supported(InheritanceUtil.isInheritor(field.type, "android.view.View"), "${field.name} 类型不是可解析的 View")
                val value = view(ids(a).single(), type, !nullable(field))
                init.append("        this.${field.name} = $value;\n")
                clear.append("                ${owner.name}.this.${field.name} = null;\n")
            } else if (kind == "BindViews") {
                val element = when (val t = field.type) {
                    is PsiArrayType -> t.componentType
                    is PsiClassType -> { supported(t.rawType().canonicalText == "java.util.List" && t.parameters.size == 1, "@BindViews 仅支持 View 数组或 List<View>"); t.parameters.single() }
                    else -> throw Unsupported("@BindViews 类型不支持")
                }
                supported(InheritanceUtil.isInheritor(element, "android.view.View"), "@BindViews 元素必须是 View")
                val found = ids(a).map { view(it, element.canonicalText, !nullable(field)) }
                if (field.type is PsiArrayType && !nullable(field)) {
                    supported(!element.canonicalText.contains("<"), "泛型 View 数组需人工处理")
                    init.append("        this.${field.name} = new ${element.canonicalText}[] { ${found.joinToString()} };\n")
                } else {
                    val local = "${PREFIX}List${number++}"
                    init.append("        java.util.List<${element.canonicalText}> $local = new java.util.ArrayList<${element.canonicalText}>();\n")
                    found.forEach { init.append("        if ($it != null) $local.add($it);\n") }
                    val assignment = if (field.type is PsiArrayType) { supported(!element.canonicalText.contains("<"), "泛型 View 数组需人工处理"); "$local.toArray(new ${element.canonicalText}[$local.size()])" } else "java.util.Collections.unmodifiableList($local)"
                    init.append("        this.${field.name} = $assignment;\n")
                }
                clear.append("                ${owner.name}.this.${field.name} = null;\n")
            } else {
                val id = ids(a).single()
                supported(id != "@root", "资源绑定不能使用 View.NO_ID")
                val expression = when (kind) {
                    "BindString" -> "root.getResources().getString($id)"
                    "BindBool" -> "root.getResources().getBoolean($id)"
                    "BindInt" -> "root.getResources().getInteger($id)"
                    "BindDimen" -> if (type == "int") "root.getResources().getDimensionPixelSize($id)" else "root.getResources().getDimension($id)"
                    "BindArray" -> when (type) { "java.lang.String[]" -> "root.getResources().getStringArray($id)"; "int[]" -> "root.getResources().getIntArray($id)"; "java.lang.CharSequence[]" -> "root.getResources().getTextArray($id)"; "android.content.res.TypedArray" -> "root.getResources().obtainTypedArray($id)"; else -> throw Unsupported("@BindArray 字段类型不支持：$type") }
                    "BindColor" -> if (type == "int") "(android.os.Build.VERSION.SDK_INT >= 23 ? root.getContext().getColor($id) : root.getResources().getColor($id))" else "(android.os.Build.VERSION.SDK_INT >= 23 ? root.getContext().getColorStateList($id) : root.getResources().getColorStateList($id))"
                    "BindDrawable" -> {
                        val base = "(android.os.Build.VERSION.SDK_INT >= 21 ? root.getContext().getDrawable($id) : root.getResources().getDrawable($id))"
                        val tint = a.findAttributeValue("tint")
                        if (tint == null || tint.text == "0") base else {
                            val drawableCompat = compatClass("androidx.core.graphics.drawable.DrawableCompat", "android.support.v4.graphics.drawable.DrawableCompat")
                            val tintId = resourceId(tint)
                            val local = "${PREFIX}Drawable${number++}"
                            init.append("        android.graphics.drawable.Drawable $local = $drawableCompat.wrap($base).mutate();\n")
                            init.append("        $drawableCompat.setTint($local, android.os.Build.VERSION.SDK_INT >= 23 ? root.getContext().getColor($tintId) : root.getResources().getColor($tintId));\n")
                            local
                        }
                    }
                    "BindFont" -> {
                        val resourcesCompat = compatClass("androidx.core.content.res.ResourcesCompat", "android.support.v4.content.res.ResourcesCompat")
                        val style = (a.findAttributeValue("style") as? PsiExpression)?.let { JavaPsiFacade.getInstance(file.project).constantEvaluationHelper.computeConstantExpression(it) } as? Int ?: 0
                        val font = "$resourcesCompat.getFont(root.getContext(), $id)"
                        if (style == 0) font else "android.graphics.Typeface.create($font, $style)"
                    }
                    "BindBitmap" -> "android.graphics.BitmapFactory.decodeResource(root.getResources(), $id)"
                    "BindAnim" -> "android.view.animation.AnimationUtils.loadAnimation(root.getContext(), $id)"
                    "BindFloat" -> {
                        val local = "${PREFIX}Float${number++}"
                        init.append("        android.util.TypedValue $local = new android.util.TypedValue();\n        root.getResources().getValue($id, $local, true);\n")
                        "$local.getFloat()"
                    }
                    else -> throw Unsupported("不支持 @$kind")
                }
                init.append("        this.${field.name} = $expression;\n")
            }
        }
        data class Handler(val method: PsiMethod, val annotation: PsiAnnotation, val callback: Callback)
        data class Group(val id: String, val spec: Listener, val handlers: MutableList<Handler>)
        val groups = linkedMapOf<Pair<String, String>, Group>()
        owner.methods.forEach { method ->
            val eventAnnotations = method.annotations.filter { annotationName(it) in listeners.keys }
            val optional = method.getAnnotation("butterknife.Optional")
            if (optional != null) { supported(eventAnnotations.isNotEmpty(), "@Optional 必须用于事件方法"); replace(optional) }
            eventAnnotations.forEach { a ->
                val kind = annotationName(a)!!
                supported(!method.hasModifierProperty(PsiModifier.STATIC) && method.throwsList.referenceElements.isEmpty(), "事件方法不能为 static 或声明 throws")
                var spec = listeners.getValue(kind)
                if (kind == "OnPageChange" && JavaPsiFacade.getInstance(file.project).findClass(spec.target, file.resolveScope) == null)
                    spec = spec.copy(target = spec.target.replace("androidx.viewpager.widget", "android.support.v4.view"), type = spec.type.replace("androidx.viewpager.widget", "android.support.v4.view"))
                val selected = (a.findAttributeValue("callback") as? PsiReferenceExpression)?.referenceName
                val callback = if (spec.callbacks.size == 1) spec.callbacks.single() else spec.callbacks.firstOrNull { it.key == (selected ?: when (kind) { "OnTextChanged" -> "TEXT_CHANGED"; "OnPageChange" -> "PAGE_SELECTED"; else -> "ITEM_SELECTED" }) }
                    ?: throw Unsupported("不支持的 @$kind callback")
                supported(method.returnType?.canonicalText in setOf("void", callback.returns), "事件方法返回类型不匹配：${method.name}")
                replace(a)
                ids(a).forEach { id -> groups.getOrPut(kind to id) { Group(id, spec, mutableListOf()) }.handlers += Handler(method, a, callback) }
            }
        }
        groups.values.forEach { group ->
            val required = group.handlers.any { it.method.getAnnotation("butterknife.Optional") == null }
            val target = view(group.id, group.spec.target, required)
            val listener = "${PREFIX}Listener${number++}"
            init.append("        final ${group.spec.type} $listener = new ${group.spec.type}() {\n")
            group.spec.callbacks.forEach { callback ->
                init.append("            @Override public ${callback.returns} ${callback.name}(${callback.params.mapIndexed { i, type -> "$type p$i" }.joinToString()}) {\n")
                val handlers = group.handlers.filter { it.callback == callback }
                supported(callback.returns == "void" || (handlers.none { it.method.returnType != PsiTypes.voidType() } || handlers.size == 1), "多个返回值事件方法绑定同一控件")
                handlers.forEach { handler ->
                    val method = handler.method
                    val available = callback.params.indices.toMutableList()
                    val args = method.parameterList.parameters.map { parameter ->
                        val chosen = available.firstOrNull { i ->
                            val sourceType = JavaPsiFacade.getElementFactory(file.project).createTypeFromText(callback.params[i], owner)
                            TypeConversionUtil.erasure(parameter.type) == TypeConversionUtil.erasure(sourceType) ||
                                (InheritanceUtil.isInheritor(parameter.type, "android.view.View") && TypeConversionUtil.isAssignable(sourceType, parameter.type)) ||
                                (parameter.type as? PsiClassType)?.resolve()?.isInterface == true
                        } ?: throw Unsupported("无法匹配 ${method.name} 的参数 ${parameter.name}：${parameter.type.canonicalText}")
                        available.remove(chosen)
                        if ((parameter.type as? PsiClassType)?.resolve()?.isInterface == true) "(${parameter.type.canonicalText}) (java.lang.Object) p$chosen" else "(${parameter.type.canonicalText}) p$chosen"
                    }
                    val invocation = "${owner.name}.this.${method.name}(${args.joinToString()})"
                    if (method.returnType == PsiTypes.voidType()) init.append("                $invocation;\n") else init.append("                return $invocation;\n")
                }
                if (callback.returns != "void" && handlers.none { it.method.returnType != PsiTypes.voidType() }) init.append("                return ${callback.defaultValue};\n")
                init.append("            }\n")
            }
            init.append("        };\n        if ($target != null) $target.${group.spec.setter}($listener);\n")
            clear.append("                if ($target != null) $target.${group.spec.remover ?: group.spec.setter}(${if (group.spec.remover == null) "null" else listener});\n")
        }
        val inherited = if (parent != null) "        super.${PREFIX}BindOwnViews(root, cleanups);\n" else ""
        val cleanup = if (clear.isNotEmpty()) "        cleanups.add(new java.lang.Runnable() { @Override public void run() {\n$clear        } });\n" else ""
        val ownerType = JavaPsiFacade.getElementFactory(file.project).createType(owner)
        val implicitRoot = when {
            InheritanceUtil.isInheritor(ownerType, "android.view.View") -> "this"
            InheritanceUtil.isInheritor(ownerType, "android.app.Activity") || InheritanceUtil.isInheritor(ownerType, "android.app.Dialog") -> "getWindow().getDecorView()"
            else -> null
        }
        val noArgs = implicitRoot?.let { "    public java.lang.Runnable ${PREFIX}BindViews() { return ${PREFIX}BindViews($it); }\n" }.orEmpty()
        val generated = """

$noArgs
    public java.lang.Runnable ${PREFIX}BindViews(android.view.View root) {
        final java.util.List<java.lang.Runnable> cleanups = new java.util.ArrayList<java.lang.Runnable>();
        ${PREFIX}BindOwnViews(root, cleanups);
        return new $helperName(cleanups);
    }

    ${if (parent != null) "@Override\n    " else ""}protected void ${PREFIX}BindOwnViews(android.view.View root, java.util.List<java.lang.Runnable> cleanups) {
$inherited$init$cleanup    }
"""
        val brace = owner.rBrace!!
        edits += Edit(brace.textRange.startOffset, brace.textRange.startOffset, generated)
    }
}