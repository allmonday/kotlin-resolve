package com.tangkikodo.kotlinresolve.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

// Referenced by name only — keeps the KSP processor free of the kotlin-resolve runtime
// at processing time. The generated code references the same symbols by FQN, and the
// user's compile classpath provides the implementations.
private val KOTLIN_RESOLVE_PKG = "com.tangkikodo.kotlinresolve"
private val ADAPTER_CLASS = ClassName(KOTLIN_RESOLVE_PKG, "KotlinResolveAdapter")
private val INVOKE_CTX_CLASS = ClassName(KOTLIN_RESOLVE_PKG, "InvokeCtx")
private val RESOLVE_META_CLASS = ClassName(KOTLIN_RESOLVE_PKG, "ResolveMeta")
private val SCAN_FUN = MemberName(KOTLIN_RESOLVE_PKG, "scan")

/**
 * Generates `_KotlinResolveAdapter_<ClassName>` per model class. Method invocations
 * become direct calls (`node.resolveOwner(ctx.loader)`); field reads/writes become
 * direct property access. `meta` delegates to the runtime `scan(kls)` so metadata
 * shape stays in one place.
 */
class AdapterGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(kls: KSClassDeclaration) {
        val packageName = kls.packageName.asString()
        val className = kls.simpleName.asString()
        val targetClass = kls.toClassName()
        val adapterName = "_KotlinResolveAdapter_$className"

        val resolveMethods = kls.functionsAnnotatedWith("Resolve")
        val postMethods = kls.functionsAnnotatedWith("Post")
        if (resolveMethods.isEmpty() && postMethods.isEmpty()) return

        val allFields = kls.getAllProperties().associateBy { it.simpleName.asString() }
        for (method in resolveMethods + postMethods) {
            val ann = method.annotations.first {
                val n = it.shortName.asString()
                n == "Resolve" || n == "Post"
            }
            val fieldName = ann.argumentValue("field") as? String
            if (fieldName == null) {
                logger.error("Missing 'field' on ${method.simpleName.asString()}", method)
                return
            }
            val prop = allFields[fieldName]
            if (prop == null) {
                logger.error("@Resolve/@Post target '$fieldName' not found on $className", method)
                return
            }
            if (prop.isVal()) {
                logger.error(
                    "@Resolve/@Post target '$fieldName' on $className must be 'var'",
                    prop,
                )
                return
            }
        }

        val typeSpec = TypeSpec.classBuilder(adapterName)
            .addSuperinterface(ADAPTER_CLASS.parameterizedBy(targetClass))
            .addProperty(buildMetaProperty(targetClass))
            .addFunction(buildInvoke("invokeResolveMethod", targetClass, resolveMethods, "resolve"))
            .addFunction(buildInvoke("invokePostMethod", targetClass, postMethods, "post"))
            .addFunction(buildReadField(targetClass, allFields))
            .addFunction(buildWriteField(targetClass, allFields))
            .addFunction(buildCollectObjectFields(targetClass, kls))
            .build()

        FileSpec.builder(packageName, adapterName)
            .addType(typeSpec)
            .build()
            .writeTo(codeGenerator, aggregating = false)
    }

    private fun buildMetaProperty(targetClass: ClassName): PropertySpec {
        val initializer = CodeBlock.builder()
            .add("lazy·{ ")
            .add(CodeBlock.of("%M(%T::class)", SCAN_FUN, targetClass))
            .add(" }")
            .build()
        return PropertySpec.builder("meta", RESOLVE_META_CLASS, KModifier.OVERRIDE)
            .delegate(initializer)
            .build()
    }

    private fun buildInvoke(
        fnName: String,
        targetClass: ClassName,
        methods: List<KSFunctionDeclaration>,
        kind: String,
    ): FunSpec {
        val builder = FunSpec.builder(fnName)
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter("node", targetClass)
            .addParameter("methodIndex", Int::class)
            .addParameter("ctx", INVOKE_CTX_CLASS)
            .returns(Any::class.asTypeName().copy(nullable = true))

        if (methods.isEmpty()) {
            builder.addStatement("error(%S)", "no @$kind methods on ${targetClass.simpleName}")
            return builder.build()
        }

        builder.beginControlFlow("return when (methodIndex)")
        for ((idx, method) in methods.withIndex()) {
            builder.addStatement("$idx -> ${buildMethodCall(method)}")
        }
        builder.addStatement(
            "else -> error(%S)",
            "invalid $kind method index \$methodIndex for ${targetClass.simpleName}",
        )
        builder.endControlFlow()
        return builder.build()
    }

    private fun buildMethodCall(method: KSFunctionDeclaration): String {
        val name = method.simpleName.asString()
        val params = method.parameters
        if (params.isEmpty()) return "node.$name()"

        var collectorIdx = 0
        val args = params.joinToString(", ") { p ->
            when {
                p.hasAnnotation("LoaderDep") -> {
                    // ctx.loader is DataLoader<*, *>?; cast to the parameter's declared type.
                    val typeName = p.type.resolve().toTypeName()
                    "ctx.loader as $typeName"
                }
                p.hasAnnotation("CollectorParam") -> "ctx.collectors[${collectorIdx++}]"
                else -> {
                    val pname = p.name?.asString()
                    when (pname) {
                        "loader" -> {
                            val typeName = p.type.resolve().toTypeName()
                            "ctx.loader as $typeName"
                        }
                        "parent" -> "ctx.parent"
                        "context" -> "ctx.context"
                        "ancestorContext" -> "ctx.ancestorContext"
                        else -> "null"
                    }
                }
            }
        }
        return "node.$name($args)"
    }

    private fun buildReadField(
        targetClass: ClassName,
        allFields: Map<String, KSPropertyDeclaration>,
    ): FunSpec {
        val builder = FunSpec.builder("readField")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("node", targetClass)
            .addParameter("fieldName", String::class)
            .returns(Any::class.asTypeName().copy(nullable = true))
            .beginControlFlow("return when (fieldName)")
        for (name in allFields.keys) {
            builder.addStatement("%S -> node.$name", name)
        }
        builder.addStatement("else -> null")
        builder.endControlFlow()
        return builder.build()
    }

    private fun buildWriteField(
        targetClass: ClassName,
        allFields: Map<String, KSPropertyDeclaration>,
    ): FunSpec {
        val builder = FunSpec.builder("writeField")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("node", targetClass)
            .addParameter("fieldName", String::class)
            .addParameter("value", Any::class.asTypeName().copy(nullable = true))
            .beginControlFlow("when (fieldName)")
        for ((name, prop) in allFields) {
            if (prop.isVal()) continue
            val typeName = prop.type.resolve().toTypeName()
            builder.addStatement("%S -> node.$name = (value as $typeName)", name)
        }
        builder.addStatement("else -> error(%S)", "cannot write field fieldName=\${fieldName} on ${targetClass.simpleName}")
        builder.endControlFlow()
        return builder.build()
    }

    private fun buildCollectObjectFields(
        targetClass: ClassName,
        kls: KSClassDeclaration,
    ): FunSpec {
        val pairType = Pair::class.asTypeName().parameterizedBy(
            String::class.asTypeName(),
            Any::class.asTypeName().copy(nullable = true),
        )
        val returnType = kotlin.collections.List::class.asTypeName().parameterizedBy(pairType)

        val builder = FunSpec.builder("collectObjectFields")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("node", targetClass)
            .returns(returnType)

        val objectFields = kls.getAllProperties()
            .filter { isUserTypeOrList(it.type.resolve()) }
            .map { it.simpleName.asString() }
            .toList()

        if (objectFields.isEmpty()) {
            builder.addStatement("return emptyList()")
            return builder.build()
        }

        val format = "return listOf(" + objectFields.joinToString(", ") { "%S to node.$it" } + ")"
        builder.addStatement(format, *objectFields.toTypedArray())
        return builder.build()
    }

    private fun isUserTypeOrList(type: KSType): Boolean {
        val pkg = type.declaration.packageName.asString()
        if (!pkg.startsWith("kotlin") && !pkg.startsWith("java")) return true
        // Recurse into List<T>, Map<K,V>, etc.
        return type.arguments.any { arg ->
            val t = arg.type?.resolve() ?: return@any false
            isUserTypeOrList(t)
        }
    }

    private fun KSClassDeclaration.functionsAnnotatedWith(shortName: String): List<KSFunctionDeclaration> =
        declarations.filterIsInstance<KSFunctionDeclaration>()
            .filter { fn -> fn.annotations.any { it.shortName.asString() == shortName } }
            .toList()

    private fun KSValueParameter.hasAnnotation(shortName: String): Boolean =
        annotations.any { it.shortName.asString() == shortName }

    /**
     * Detect val vs var. KSP's KSPropertyDeclaration has `isMutable: Boolean` as a
     * member property — true means var, false means val.
     */
    private fun KSPropertyDeclaration.isVal(): Boolean = !this.isMutable

    private fun KSAnnotation.argumentValue(name: String): Any? =
        arguments.firstOrNull { it.name?.asString() == name }?.value
}
