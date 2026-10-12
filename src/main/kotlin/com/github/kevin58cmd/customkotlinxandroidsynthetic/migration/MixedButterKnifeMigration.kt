package com.github.kevin58cmd.customkotlinxandroidsynthetic.migration

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.*

/** One dependency graph and one rejection loop for Java and Kotlin source files. */
object MixedButterKnifeMigration {
    fun classes(file: PsiFile): List<PsiClass> = when (file) {
        is PsiJavaFile -> PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java).toList()
        is KtFile -> PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java).mapNotNull { it.toLightClass() }
        else -> emptyList()
    }
    fun source(owner: PsiClass): PsiFile = owner.navigationElement.containingFile ?: owner.containingFile
    fun hasBindings(owner: PsiClass): Boolean {
        val kotlin = owner.navigationElement as? KtClassOrObject
        if (kotlin != null) return kotlin.declarations.filterIsInstance<KtModifierListOwner>().any { declaration ->
            declaration.annotationEntries.any { KotlinButterKnifeMigration.annotationName(it) != null }
        }
        return (owner.fields.flatMap { it.annotations.toList() } + owner.methods.flatMap { it.annotations.toList() })
            .any { it.qualifiedName?.startsWith("butterknife.") == true }
    }
    fun targets(file: PsiFile): List<PsiClass> = when (file) {
        is PsiJavaFile -> PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == "bind" && it.resolveMethod()?.containingClass?.qualifiedName == "butterknife.ButterKnife" }
            .mapNotNull { (it.argumentList.expressions.firstOrNull()?.type as? PsiClassType)?.resolve() }
        is KtFile -> KotlinButterKnifeMigration.bindingCalls(file).mapNotNull { KotlinButterKnifeMigration.targetClass(it) }
        else -> emptyList()
    }
    fun relatedFiles(selected: PsiFile, candidates: List<PsiFile>): List<PsiFile> = ReadAction.compute<List<PsiFile>, RuntimeException> {
        val files = (listOf(selected) + candidates).distinct()
        val targets = files.associateWith { file ->
            ProgressManager.checkCanceled()
            (classes(file).filter(::hasBindings) + targets(file)).toSet()
        }
        val included = linkedSetOf(selected)
        val connected = targets.getValue(selected).toMutableSet()
        var changed: Boolean
        do {
            changed = false
            for (file in files) {
                ProgressManager.checkCanceled()
                if (file in included) continue
                val fileTargets = targets.getValue(file)
                if (fileTargets.any { candidate -> connected.any { existing ->
                    candidate.qualifiedName == existing.qualifiedName || candidate.isInheritor(existing, true) || existing.isInheritor(candidate, true)
                } }) {
                    included += file
                    connected += fileTargets
                    changed = true
                }
            }
        } while (changed)
        included.toList()
    }
    fun planBatch(files: List<PsiFile>): Map<PsiFile, ButterKnifeMigration.Result> {
        var eligible = files.toSet()
        val rejected = linkedMapOf<PsiFile, ButterKnifeMigration.Result>()
        while (eligible.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val results = ReadAction.compute<Map<PsiFile, ButterKnifeMigration.Result>, RuntimeException> {
                val targets = eligible.flatMap(::targets)
                val context = eligible.flatMap { classes(it).filter(::hasBindings) }.plus(targets)
                    .filter { source(it) in eligible }.mapNotNull { it.qualifiedName }.toSet()
                val bindingTargets = targets.mapNotNull { it.qualifiedName }.toSet()
                eligible.associateWith { file -> when (file) {
                    is PsiJavaFile -> ButterKnifeMigration.planInContext(file, context, bindingTargets)
                    is KtFile -> KotlinButterKnifeMigration.planInContext(file, context, bindingTargets)
                    else -> ButterKnifeMigration.Result(file.text, null, "仅支持 Java 和 Kotlin 源码")
                } }
            }
            val failed = results.filterValues { it.after == null }
            if (failed.isEmpty()) return rejected + results
            rejected.putAll(failed)
            eligible -= failed.keys
        }
        return rejected
    }
    fun bindingParent(owner: PsiClass, context: Set<String>): PsiClass? {
        val visited = mutableSetOf<String>()
        var parent = owner.superClass
        while (parent != null) {
            require(parent != owner && visited.add(parent.qualifiedName ?: parent.name.orEmpty())) { "父类存在循环继承" }
            if (parent.qualifiedName in context) return parent
            require(!hasBindings(parent)) { "父类 ${parent.name} 未参与迁移；请检查它的跳过原因" }
            val binder = parent.qualifiedName?.let { "${it}_ViewBinding" }
            require(binder == null || JavaPsiFacade.getInstance(owner.project).findClass(binder, owner.resolveScope) == null) { "父类绑定只有编译产物，需包含父类源码" }
            parent = parent.superClass
        }
        return null
    }
    fun validateOwner(owner: PsiClass, context: Set<String>, bindingTargets: Set<String>) {
        require(bindingTargets.any { name ->
            val target = JavaPsiFacade.getInstance(owner.project).findClass(name, owner.resolveScope)
            owner.qualifiedName == name || (target != null && (owner.isInheritor(target, true) || target.isInheritor(owner, true)))
        }) { "类 ${owner.name} 没有可解析的绑定入口；已扫描 Java/Kotlin 源码，请检查基类的跳过原因、Gradle Sync 和绑定调用是否可解析" }
        ClassInheritorsSearch.search(owner, owner.useScope, true).findAll().forEach { child ->
            if (hasBindings(child) || targets(source(child)).any { it.qualifiedName == child.qualifiedName })
                require(child.qualifiedName in context) { "子类 ${child.name} 不在可迁移范围，请一起迁移继承链" }
        }
    }
}