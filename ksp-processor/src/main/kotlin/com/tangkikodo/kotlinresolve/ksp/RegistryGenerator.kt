package com.tangkikodo.kotlinresolve.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates a single KotlinResolveAdapterFactory implementation that knows about every
 * adapter produced in this compilation unit, plus the ServiceLoader registration file.
 *
 * The factory file is written as raw Kotlin text — KotlinPoet's `parameterizedBy`
 * was eating type arguments for the `Map<KClass<*>, KotlinResolveAdapter<*>>` return
 * type, and a direct string gives us full control.
 */
class RegistryGenerator(
    private val codeGenerator: CodeGenerator,
) {
    fun generate(allClasses: List<KSClassDeclaration>) {
        if (allClasses.isEmpty()) return

        val factoryName = "_KotlinResolveAdapterFactory"
        val pkg = "com.tangkikodo.kotlinresolve.generated"

        val sb = StringBuilder()
        sb.append("package $pkg\n\n")
        sb.append("import com.tangkikodo.kotlinresolve.KotlinResolveAdapter\n")
        sb.append("import com.tangkikodo.kotlinresolve.KotlinResolveAdapterFactory\n")
        sb.append("import kotlin.reflect.KClass\n")
        for (kls in allClasses) {
            val cn = kls.toClassName()
            sb.append("import ${cn.canonicalName}\n")
            sb.append("import ${kls.packageName.asString()}._KotlinResolveAdapter_${kls.simpleName.asString()}\n")
        }
        sb.append("\n")
        sb.append("@Suppress(\"UNCHECKED_CAST\")\n")
        sb.append("class $factoryName : KotlinResolveAdapterFactory {\n")
        sb.append("  override fun create(): Map<KClass<out Any>, KotlinResolveAdapter<out Any>> {\n")
        sb.append("    val map = hashMapOf<KClass<*>, KotlinResolveAdapter<*>>()\n")
        for (kls in allClasses) {
            val cn = kls.simpleName.asString()
            sb.append("    map[${cn}::class] = _KotlinResolveAdapter_${cn}()\n")
        }
        sb.append("    @Suppress(\"UNCHECKED_CAST\")\n")
        sb.append("    return map as Map<KClass<out Any>, KotlinResolveAdapter<out Any>>\n")
        sb.append("  }\n")
        sb.append("}\n")

        val newFile = codeGenerator.createNewFileByPath(
            dependencies = com.google.devtools.ksp.processing.Dependencies.ALL_FILES,
            path = "com/tangkikodo/kotlinresolve/generated/$factoryName",
            extensionName = "kt",
        )
        newFile.writer().use { w -> w.write(sb.toString()) }

        // ServiceLoader registration
        val serviceFile = codeGenerator.createNewFileByPath(
            dependencies = com.google.devtools.ksp.processing.Dependencies.ALL_FILES,
            path = "META-INF/services/com.tangkikodo.kotlinresolve.KotlinResolveAdapterFactory",
            extensionName = "",
        )
        serviceFile.writer().use { w ->
            w.write("$pkg.$factoryName\n")
        }
    }
}
