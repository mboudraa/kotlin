/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.fir.AbstractFirResolveWithSessionTestCase
import org.jetbrains.kotlin.fir.FirRenderer
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.java.declarations.FirJavaClass
import org.jetbrains.kotlin.fir.java.declarations.FirJavaMethod
import org.jetbrains.kotlin.fir.java.scopes.JavaClassEnhancementScope
import org.jetbrains.kotlin.fir.resolve.FirScopeProvider
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirCompositeSymbolProvider
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.impl.FirCompositeScope
import org.jetbrains.kotlin.fir.service
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import java.io.File

abstract class AbstractFirTypeEnhancementTest : AbstractFirResolveWithSessionTestCase() {
    override fun createEnvironment(): KotlinCoreEnvironment {
        return createEnvironmentWithMockJdk(ConfigurationKind.JDK_NO_RUNTIME)
    }

    fun doTest(path: String) {
        val scope = GlobalSearchScope.filesScope(project, emptyList())
            .uniteWith(TopDownAnalyzerFacadeForJVM.AllJavaSourcesInProjectScope(project))
        val session = createSession(scope)

        val javaFile = File(path)
        val javaLines = javaFile.readLines()
        val packageFqName =
            javaLines.firstOrNull { it.startsWith("package") }?.substringAfter("package")?.trim()?.substringBefore(";")?.let { name ->
                FqName(name)
            } ?: FqName.ROOT
        val classFqNames = listOf("class ", "interface ", "enum ", "@interface ").map { kind ->
            javaLines
                .filter { !it.startsWith(" ") && it.contains(kind) }
                .map { suffix -> suffix.substringAfter(kind).trim().substringBefore(" ").substringBefore("<").let { FqName(it) } }
        }.flatten().toSet()

        val javaFirDump = StringBuilder().also { builder ->
            val renderer = FirRenderer(builder)
            val symbolProvider = session.service<FirSymbolProvider>() as FirCompositeSymbolProvider
            val javaProvider = symbolProvider.providers.filterIsInstance<JavaSymbolProvider>().first()
            for (classFqName in classFqNames) {
                javaProvider.getClassLikeSymbolByFqName(ClassId(packageFqName, classFqName, false))
            }

            val processedJavaClasses = mutableSetOf<FirJavaClass>()
            for (javaClass in javaProvider.getJavaTopLevelClasses().sortedBy { it.name }) {
                if (javaClass !is FirJavaClass || javaClass in processedJavaClasses) continue
                val enhancementScope = session.service<FirScopeProvider>().getDeclaredMemberScope(javaClass, session).let {
                    when (it) {
                        is FirCompositeScope -> it.scopes.filterIsInstance<JavaClassEnhancementScope>().first()
                        is JavaClassEnhancementScope -> it
                        else -> null
                    }
                }
                if (enhancementScope == null) {
                    javaClass.accept(renderer, null)
                } else {
                    renderer.visitMemberDeclaration(javaClass)
                    renderer.renderSupertypes(javaClass)
                    renderer.renderInBraces {
                        val renderedDeclarations = mutableListOf<FirDeclaration>()
                        for (declaration in javaClass.declarations) {
                            if (declaration in renderedDeclarations) continue
                            if (declaration !is FirJavaMethod) {
                                declaration.accept(renderer, null)
                                renderer.newLine()
                                renderedDeclarations += declaration
                            } else {
                                enhancementScope.processFunctionsByName(declaration.name) { symbol ->
                                    val enhanced = (symbol as? FirFunctionSymbol)?.fir
                                    if (enhanced != null && enhanced !in renderedDeclarations) {
                                        enhanced.accept(renderer, null)
                                        renderer.newLine()
                                        renderedDeclarations += enhanced
                                    }
                                    ProcessorAction.NEXT
                                }
                            }
                        }
                    }
                }
                processedJavaClasses += javaClass
            }
        }.toString()

        val expectedFile = File(javaFile.absolutePath.replace(".java", ".txt"))
        KotlinTestUtils.assertEqualsToFile(expectedFile, javaFirDump)
    }
}