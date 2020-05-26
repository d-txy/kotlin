/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.checkers

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.contracts.description.CallsEffectDeclaration
import org.jetbrains.kotlin.contracts.description.ContractProviderKey
import org.jetbrains.kotlin.contracts.description.InvocationKind
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContext.CAPTURED_IN_CLOSURE
import org.jetbrains.kotlin.resolve.BindingContext.FIELD_CAPTURED_IN_EXACLY_ONCE_CLOSURE
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getValueArgumentForExpression
import org.jetbrains.kotlin.resolve.calls.model.ArgumentMatch
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionResolvedCall
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.types.expressions.CaptureKind

class CapturingInClosureChecker : CallChecker {
    override fun check(resolvedCall: ResolvedCall<*>, reportOn: PsiElement, context: CallCheckerContext) {
        val variableResolvedCall = (resolvedCall as? VariableAsFunctionResolvedCall)?.variableCall ?: resolvedCall
        val variableDescriptor = variableResolvedCall.resultingDescriptor as? VariableDescriptor
        if (variableDescriptor != null) {
            checkCapturingInClosure(variableDescriptor, context.trace, context.scope)
        }
    }

    private fun checkCapturingInClosure(variable: VariableDescriptor, trace: BindingTrace, scope: LexicalScope) {
        val variableParent = variable.containingDeclaration
        val scopeContainer = scope.ownerDescriptor
        if (isCapturedVariable(variableParent, scopeContainer)) {
            if (trace.get(CAPTURED_IN_CLOSURE, variable) != CaptureKind.NOT_INLINE) {
                trace.record(CAPTURED_IN_CLOSURE, variable, getCaptureKind(trace.bindingContext, scopeContainer, variableParent, variable))
                return
            }
        }
        // Check whether a field is captured in EXACTLY_ONCE contract
        if (variable !is PropertyDescriptor || scopeContainer !is AnonymousFunctionDescriptor) return
        val scopeDeclaration = DescriptorToSourceUtils.descriptorToDeclaration(scopeContainer) as? KtFunction ?: return
        if (scopeContainer.containingDeclaration !is ConstructorDescriptor) return
        if (isExactlyOnceContract(trace.bindingContext, scopeDeclaration)) {
            trace.record(FIELD_CAPTURED_IN_EXACLY_ONCE_CLOSURE, variable)
        }
    }

    private fun isCapturedVariable(variableParent: DeclarationDescriptor, scopeContainer: DeclarationDescriptor): Boolean {
        if (variableParent !is FunctionDescriptor || scopeContainer == variableParent) return false

        if (variableParent is ConstructorDescriptor) {
            val classDescriptor = variableParent.containingDeclaration

            if (scopeContainer == classDescriptor) return false
            if (scopeContainer is PropertyDescriptor && scopeContainer.containingDeclaration == classDescriptor) return false
        }
        return true
    }

    private fun getCaptureKind(
        context: BindingContext,
        scopeContainer: DeclarationDescriptor,
        variableParent: DeclarationDescriptor,
        variable: VariableDescriptor
    ): CaptureKind {
        val scopeDeclaration = DescriptorToSourceUtils.descriptorToDeclaration(scopeContainer)
        if (!InlineUtil.canBeInlineArgument(scopeDeclaration)) return CaptureKind.NOT_INLINE

        if (InlineUtil.isInlinedArgument(scopeDeclaration as KtFunction, context, false) &&
            !isCrossinlineParameter(context, scopeDeclaration)
        ) {
            val scopeContainerParent = scopeContainer.containingDeclaration ?: error("parent is null for $scopeContainer")
            return if (
                !isCapturedVariable(variableParent, scopeContainerParent) ||
                getCaptureKind(context, scopeContainerParent, variableParent, variable) == CaptureKind.INLINE_ONLY
            ) CaptureKind.INLINE_ONLY else CaptureKind.NOT_INLINE
        }
        val exactlyOnceContract = isExactlyOnceContract(context, scopeDeclaration)
        // We cannot box arguments.
        val isArgument = variable is ValueParameterDescriptor && variableParent is CallableDescriptor
                && variableParent.valueParameters.contains(variable)
        // and destructured variables of lambda parameter
        val isDestructedVariable = variable is LocalVariableDescriptor && variableParent is AnonymousFunctionDescriptor &&
                variableParent.valueParameters.any {
                    it is ValueParameterDescriptorImpl.WithDestructuringDeclaration && it.destructuringVariables.contains(variable)
                }
        // and for loop parameters
        val isForLoopParameter = isForLoopParameter(variable)
        // and exceptions inside catch block
        val isCatchBlockParameter = isCatchBlockParameter(variable)
        // and val in when
        val isValInWhen = isValInWhen(variable)
        return if (
            exactlyOnceContract &&
            !isArgument && !isDestructedVariable && !isForLoopParameter && !isCatchBlockParameter && !isValInWhen
        ) CaptureKind.EXACTLY_ONCE_EFFECT else CaptureKind.NOT_INLINE
    }

    private fun isValInWhen(variable: VariableDescriptor): Boolean {
        val psi = ((variable as? LocalVariableDescriptor)?.source as? KotlinSourceElement)?.psi ?: return false
        return (psi.parent as? KtWhenExpression)?.let { it.subjectVariable == psi } == true
    }

    private fun isCatchBlockParameter(variable: VariableDescriptor): Boolean {
        val psi = ((variable as? LocalVariableDescriptor)?.source as? KotlinSourceElement)?.psi ?: return false
        return psi.parent.parent.let { it is KtCatchClause && it.parameterList?.parameters?.contains(psi) == true }
    }

    private fun isForLoopParameter(variable: VariableDescriptor): Boolean {
        val psi = ((variable as? LocalVariableDescriptor)?.source as? KotlinSourceElement)?.psi ?: return false
        if (psi.parent is KtForExpression) {
            val forExpression = psi.parent as KtForExpression
            return forExpression.loopParameter == psi
        } else if (psi.parent is KtDestructuringDeclaration) {
            val parameter = psi.parent.parent as? KtParameter ?: return false
            val forExpression = parameter.parent as? KtForExpression ?: return false
            return forExpression.loopParameter == parameter
        }
        return false
    }

    private fun isExactlyOnceParameter(function: DeclarationDescriptor, parameter: VariableDescriptor): Boolean {
        if (function !is CallableDescriptor) return false
        if (parameter !is ValueParameterDescriptor) return false
        val contractDescription = function.getUserData(ContractProviderKey)?.getContractDescription() ?: return false
        val effect = contractDescription.effects.filterIsInstance<CallsEffectDeclaration>()
            .find { it.variableReference.descriptor == parameter.original } ?: return false
        return effect.kind == InvocationKind.EXACTLY_ONCE
    }

    private fun isExactlyOnceContract(bindingContext: BindingContext, argument: KtFunction): Boolean {
        val (descriptor, parameter) = getCalleeDescriptorAndParameter(bindingContext, argument) ?: return false
        return isExactlyOnceParameter(descriptor, parameter)
    }

    private fun getCalleeDescriptorAndParameter(
        bindingContext: BindingContext,
        argument: KtFunction
    ): Pair<CallableDescriptor, ValueParameterDescriptor>? {
        val call = KtPsiUtil.getParentCallIfPresent(argument) ?: return null
        val resolvedCall = call.getResolvedCall(bindingContext) ?: return null
        val descriptor = resolvedCall.resultingDescriptor
        val valueArgument = resolvedCall.call.getValueArgumentForExpression(argument) ?: return null
        val mapping = resolvedCall.getArgumentMapping(valueArgument) as? ArgumentMatch ?: return null
        val parameter = mapping.valueParameter
        return descriptor to parameter
    }

    private fun isCrossinlineParameter(bindingContext: BindingContext, argument: KtFunction): Boolean {
        return getCalleeDescriptorAndParameter(bindingContext, argument)?.second?.isCrossinline == true
    }
}
