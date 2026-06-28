package com.tangkikodo.kotlinresolve.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

class KotlinResolveSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KotlinResolveSymbolProcessor(environment.codeGenerator, environment.logger)
}

class KotlinResolveSymbolProcessor(
    private val codeGenerator: com.google.devtools.ksp.processing.CodeGenerator,
    private val logger: com.google.devtools.ksp.processing.KSPLogger,
) : SymbolProcessor {
    // KSP calls process() in multiple rounds; track what we've already emitted so we
    // don't try to recreate files (which throws FileAlreadyExistsException).
    private val processed = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val candidates = resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == com.google.devtools.ksp.symbol.ClassKind.CLASS }
            .filter { it.hasResolveOrPostMethod() }
            .distinctBy { it.qualifiedName?.asString() }
            .filter { it.qualifiedName?.asString() !in processed }
            .toList()

        if (candidates.isEmpty()) return emptyList()

        for (kls in candidates) {
            AdapterGenerator(codeGenerator, logger).generate(kls)
            processed += kls.qualifiedName!!.asString()
        }
        RegistryGenerator(codeGenerator).generate(candidates)
        return emptyList()
    }
}

private fun KSClassDeclaration.hasResolveOrPostMethod(): Boolean =
    declarations.any { decl ->
        decl is KSFunctionDeclaration && decl.annotations.any { ann ->
            val name = ann.shortName.asString()
            name == "Resolve" || name == "Post"
        }
    }
