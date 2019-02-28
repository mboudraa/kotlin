/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.scopes

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationContainer
import org.jetbrains.kotlin.fir.java.declarations.FirJavaField
import org.jetbrains.kotlin.fir.java.declarations.FirJavaMethod
import org.jetbrains.kotlin.fir.java.declarations.FirJavaValueParameter
import org.jetbrains.kotlin.fir.java.enhancement.EnhancementSignatureParts
import org.jetbrains.kotlin.fir.java.enhancement.FirAnnotationTypeQualifierResolver
import org.jetbrains.kotlin.fir.java.enhancement.FirJavaEnhancementContext
import org.jetbrains.kotlin.fir.java.enhancement.copyWithNewDefaultTypeQualifiers
import org.jetbrains.kotlin.fir.java.types.FirJavaTypeRef
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.symbols.ConeCallableSymbol
import org.jetbrains.kotlin.fir.symbols.ConeFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.ConePropertySymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.load.java.AnnotationTypeQualifierResolver
import org.jetbrains.kotlin.load.java.structure.JavaPrimitiveType
import org.jetbrains.kotlin.load.java.typeEnhancement.PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE
import org.jetbrains.kotlin.load.java.typeEnhancement.PredefinedFunctionEnhancementInfo
import org.jetbrains.kotlin.load.kotlin.SignatureBuildingComponents
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.Jsr305State

class JavaClassEnhancementScope(
    session: FirSession,
    private val useSiteScope: JavaClassUseSiteScope
) : FirScope {
    private val owner: FirRegularClass get() = useSiteScope.symbol.fir

    private val jsr305State: Jsr305State = session.jsr305State ?: Jsr305State.DISABLED

    private val typeQualifierResolver = FirAnnotationTypeQualifierResolver(jsr305State)

    private val context: FirJavaEnhancementContext =
        FirJavaEnhancementContext(session) { null }

    private val enhancements = mutableMapOf<ConeCallableSymbol, ConeCallableSymbol>()

    override fun processPropertiesByName(name: Name, processor: (ConePropertySymbol) -> ProcessorAction): ProcessorAction {
        useSiteScope.processPropertiesByName(name) process@{ original ->

            val field = enhancements.getOrPut(original) { enhance(original, name) }
            processor(field as ConePropertySymbol)
        }

        return super.processPropertiesByName(name, processor)
    }

    override fun processFunctionsByName(name: Name, processor: (ConeFunctionSymbol) -> ProcessorAction): ProcessorAction {
        useSiteScope.processFunctionsByName(name) process@{ original ->

            val function = enhancements.getOrPut(original) { enhance(original, name) }
            processor(function as ConeFunctionSymbol)
        }

        return super.processFunctionsByName(name, processor)
    }

    private fun enhance(
        original: ConePropertySymbol,
        name: Name
    ): FirPropertySymbol {
        val firField = (original as FirBasedSymbol<*>).fir as? FirJavaField ?: error("Can't make enhancement for $original")

        val memberContext = context.copyWithNewDefaultTypeQualifiers(typeQualifierResolver, jsr305State, firField.annotations)
        val newReturnTypeRef = enhanceReturnType(firField, memberContext, null)

        val symbol = FirPropertySymbol(original.callableId)
        with(firField) {
            FirMemberPropertyImpl(
                session, null, symbol, name,
                visibility, modality, isExpect, isActual, isOverride,
                isConst = false, isLateInit = false,
                receiverTypeRef = null,
                returnTypeRef = newReturnTypeRef,
                isVar = isVar, initializer = null,
                getter = FirDefaultPropertyGetter(session, null, newReturnTypeRef, visibility),
                setter = FirDefaultPropertySetter(session, null, newReturnTypeRef, visibility),
                delegate = null
            ).apply {
                annotations += firField.annotations
            }
        }
        return symbol
    }

    private fun enhance(
        original: ConeFunctionSymbol,
        name: Name
    ): FirFunctionSymbol {
        val firMethod = (original as FirBasedSymbol<*>).fir as? FirJavaMethod ?: error("Can't make enhancement for $original")

        val memberContext = context.copyWithNewDefaultTypeQualifiers(typeQualifierResolver, jsr305State, firMethod.annotations)

        val predefinedEnhancementInfo =
            SignatureBuildingComponents.signature(owner.symbol.classId, firMethod.computeJvmDescriptor()).let { signature ->
                PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE[signature]
            }

        predefinedEnhancementInfo?.let {
            assert(it.parametersInfo.size == firMethod.valueParameters.size) {
                "Predefined enhancement info for $this has ${it.parametersInfo.size}, but ${firMethod.valueParameters.size} expected"
            }
        }

        val newReceiverTypeRef = if (firMethod.receiverTypeRef != null) enhanceReceiverType(firMethod, memberContext) else null
        val newReturnTypeRef = enhanceReturnType(firMethod, memberContext, predefinedEnhancementInfo)

        val newValueParameterTypeRefs = mutableListOf<FirResolvedTypeRef>()

        for ((index, valueParameter) in firMethod.valueParameters.withIndex()) {
            newValueParameterTypeRefs += enhanceValueParameterType(
                firMethod, memberContext, predefinedEnhancementInfo, valueParameter as FirJavaValueParameter, index
            )
        }

        val symbol = FirFunctionSymbol(original.callableId)
        with(firMethod) {
            FirMemberFunctionImpl(
                session, null, symbol, name,
                newReceiverTypeRef, newReturnTypeRef
            ).apply {
                status = firMethod.status
                annotations += firMethod.annotations
                valueParameters += firMethod.valueParameters.zip(newValueParameterTypeRefs) { valueParameter, newTypeRef ->
                    with(valueParameter) {
                        FirValueParameterImpl(
                            session, psi,
                            this.name, newTypeRef,
                            defaultValue, isCrossinline, isNoinline, isVararg
                        ).apply {
                            annotations += valueParameter.annotations
                        }
                    }
                }
            }
        }
        return symbol
    }

    private fun FirJavaMethod.computeJvmDescriptor(): String = buildString {
        append(name.asString()) // TODO: Java constructors

        append("(")
        for (parameter in valueParameters) {
            // TODO: appendErasedType(parameter.returnTypeRef)
        }
        append(")")

        if ((returnTypeRef as FirJavaTypeRef).isVoid()) {
            append("V")
        } else {
            // TODO: appendErasedType(returnTypeRef)
        }
    }

    private fun FirJavaTypeRef.isVoid(): Boolean {
        return type is JavaPrimitiveType && type.type == null
    }

    // ================================================================================================

    private fun enhanceReceiverType(
        ownerFunction: FirJavaMethod,
        memberContext: FirJavaEnhancementContext
    ): FirResolvedTypeRef {
        val signatureParts = ownerFunction.partsForValueParameter(
            typeQualifierResolver,
            // TODO: check me
            parameterContainer = ownerFunction,
            methodContext = memberContext,
            typeInSignature = TypeInSignature.Receiver
        ).enhance(jsr305State)
        return signatureParts.type
    }

    private fun enhanceValueParameterType(
        ownerFunction: FirJavaMethod,
        memberContext: FirJavaEnhancementContext,
        predefinedEnhancementInfo: PredefinedFunctionEnhancementInfo?,
        ownerParameter: FirJavaValueParameter,
        index: Int
    ): FirResolvedTypeRef {
        val signatureParts = ownerFunction.partsForValueParameter(
            typeQualifierResolver,
            parameterContainer = ownerParameter,
            methodContext = memberContext,
            typeInSignature = TypeInSignature.ValueParameter(index)
        ).enhance(jsr305State, predefinedEnhancementInfo?.parametersInfo?.getOrNull(index))
        return signatureParts.type
    }

    private fun enhanceReturnType(
        owner: FirCallableMember,
        memberContext: FirJavaEnhancementContext,
        predefinedEnhancementInfo: PredefinedFunctionEnhancementInfo?
    ): FirResolvedTypeRef {
        val signatureParts = owner.parts(
            typeQualifierResolver,
            typeContainer = owner, isCovariant = true,
            containerContext = memberContext,
            containerApplicabilityType =
            if (owner is FirJavaField) AnnotationTypeQualifierResolver.QualifierApplicabilityType.FIELD
            else AnnotationTypeQualifierResolver.QualifierApplicabilityType.METHOD_RETURN_TYPE,
            typeInSignature = TypeInSignature.Return
        ).enhance(jsr305State, predefinedEnhancementInfo?.returnTypeInfo)
        return signatureParts.type
    }

    private val overriddenMemberCache = mutableMapOf<FirCallableMember, List<FirCallableMember>>()

    private fun FirCallableMember.overriddenMembers(): List<FirCallableMember> {
        return overriddenMemberCache.getOrPut(this) {
            val result = mutableListOf<FirCallableMember>()
            if (this is FirNamedFunction) {
                val superTypesScope = useSiteScope.superTypesScope
                superTypesScope.processFunctionsByName(this.name) { basicFunctionSymbol ->
                    val overriddenBy = with(useSiteScope) {
                        basicFunctionSymbol.getOverridden(setOf(this@overriddenMembers.symbol as ConeFunctionSymbol))
                    }
                    val overriddenByFir = (overriddenBy as? FirFunctionSymbol)?.fir
                    if (overriddenByFir === this@overriddenMembers) {
                        result += (basicFunctionSymbol as FirFunctionSymbol).fir
                    }
                    ProcessorAction.NEXT
                }
            }
            result
        }
    }

    private sealed class TypeInSignature {
        abstract fun getTypeRef(member: FirCallableMember): FirTypeRef

        object Return : TypeInSignature() {
            override fun getTypeRef(member: FirCallableMember): FirTypeRef = member.returnTypeRef
        }

        object Receiver : TypeInSignature() {
            override fun getTypeRef(member: FirCallableMember): FirTypeRef = member.receiverTypeRef!!
        }

        class ValueParameter(val index: Int) : TypeInSignature() {
            override fun getTypeRef(member: FirCallableMember): FirTypeRef = (member as FirFunction).valueParameters[index].returnTypeRef
        }
    }

    private fun FirCallableMember.partsForValueParameter(
        typeQualifierResolver: FirAnnotationTypeQualifierResolver,
        // TODO: investigate if it's really can be a null (check properties' with extension overrides in Java)
        parameterContainer: FirAnnotationContainer?,
        methodContext: FirJavaEnhancementContext,
        typeInSignature: TypeInSignature
    ) = parts(
        typeQualifierResolver,
        parameterContainer, false,
        parameterContainer?.let {
            methodContext.copyWithNewDefaultTypeQualifiers(typeQualifierResolver, jsr305State, it.annotations)
        } ?: methodContext,
        AnnotationTypeQualifierResolver.QualifierApplicabilityType.VALUE_PARAMETER,
        typeInSignature
    )

    private fun FirCallableMember.parts(
        typeQualifierResolver: FirAnnotationTypeQualifierResolver,
        typeContainer: FirAnnotationContainer?,
        isCovariant: Boolean,
        containerContext: FirJavaEnhancementContext,
        containerApplicabilityType: AnnotationTypeQualifierResolver.QualifierApplicabilityType,
        typeInSignature: TypeInSignature
    ): EnhancementSignatureParts {
        val typeRef = typeInSignature.getTypeRef(this)
        return EnhancementSignatureParts(
            typeQualifierResolver,
            typeContainer,
            typeRef as FirJavaTypeRef,
            this.overriddenMembers().map {
                typeInSignature.getTypeRef(it)
            },
            isCovariant,
            // recompute default type qualifiers using type annotations
            containerContext.copyWithNewDefaultTypeQualifiers(
                typeQualifierResolver, jsr305State, typeRef.annotations
            ),
            containerApplicabilityType
        )
    }

}