// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.completion

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.ui.RowIcon
import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.KotlinDescriptorIconProvider
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.completion.handlers.indexOfSkippingSpace
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.core.completion.DeclarationLookupObject
import org.jetbrains.kotlin.idea.core.moveCaret
import org.jetbrains.kotlin.idea.core.moveCaretIntoGeneratedElement
import org.jetbrains.kotlin.idea.core.overrideImplement.OverrideMembersHandler
import org.jetbrains.kotlin.idea.core.overrideImplement.generateMember
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

class OverridesCompletion(
    private val collector: LookupElementsCollector,
    private val lookupElementFactory: BasicLookupElementFactory
) {
    private val PRESENTATION_RENDERER = IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_NO_ANNOTATIONS.withOptions {
        modifiers = emptySet()
        includeAdditionalModifiers = false
    }

    fun complete(position: PsiElement, declaration: KtCallableDeclaration?) {
        val isConstructorParameter = position.getNonStrictParentOfType<KtPrimaryConstructor>() != null

        val classOrObject = position.getNonStrictParentOfType<KtClassOrObject>() ?: return

        val members = OverrideMembersHandler(isConstructorParameter).collectMembersToGenerate(classOrObject)

        for (memberObject in members) {
            val descriptor = memberObject.descriptor
            if (declaration != null && !canOverride(descriptor, declaration)) continue
            if (isConstructorParameter && descriptor !is PropertyDescriptor) continue

            var lookupElement = lookupElementFactory.createLookupElement(descriptor)

            var text = "override " + PRESENTATION_RENDERER.render(descriptor)
            if (descriptor is FunctionDescriptor) {
                text += " {...}"
            }

            val baseClass = descriptor.containingDeclaration as ClassDescriptor
            val baseClassName = baseClass.name.asString()

            val baseIcon = (lookupElement.`object` as DeclarationLookupObject).getIcon(0)
            val isImplement = descriptor.modality == Modality.ABSTRACT
            val additionalIcon = if (isImplement)
                AllIcons.Gutter.ImplementingMethod
            else
                AllIcons.Gutter.OverridingMethod
            val icon = RowIcon(baseIcon, additionalIcon)

            val baseClassDeclaration = DescriptorToSourceUtilsIde.getAnyDeclaration(position.project, baseClass)
            val baseClassIcon = KotlinDescriptorIconProvider.getIcon(baseClass, baseClassDeclaration, 0)

            lookupElement = object : LookupElementDecorator<LookupElement>(lookupElement) {
                override fun getLookupString() =
                    if (declaration == null) "override" else delegate.lookupString // don't use "override" as lookup string when already in the name of declaration

                override fun getAllLookupStrings() = setOf(lookupString, delegate.lookupString)

                override fun renderElement(presentation: LookupElementPresentation) {
                    super.renderElement(presentation)

                    presentation.itemText = text
                    presentation.isItemTextBold = isImplement
                    presentation.icon = icon
                    presentation.clearTail()
                    presentation.setTypeText(baseClassName, baseClassIcon)
                }

                override fun getDelegateInsertHandler(): InsertHandler<LookupElement> = InsertHandler { context, _ ->
                    val dummyMemberHead = when {
                        declaration != null -> ""
                        isConstructorParameter -> "override val "
                        else -> "override fun "
                    }
                    val dummyMemberTail = when {
                        isConstructorParameter || declaration is KtProperty -> "dummy: Dummy ,@"
                        else -> "dummy() {}"
                    }
                    val dummyMemberText = dummyMemberHead + dummyMemberTail
                    val override = KtTokens.OVERRIDE_KEYWORD.value

                    tailrec fun calcStartOffset(startOffset: Int, diff: Int = 0): Int {
                        return when {
                            context.document.text[startOffset - 1].isWhitespace() -> calcStartOffset(startOffset - 1, diff + 1)
                            context.document.text.substring(startOffset - override.length, startOffset) == override -> {
                                startOffset - override.length
                            }
                            else -> diff + startOffset
                        }
                    }

                    val startOffset = calcStartOffset(context.startOffset)
                    val tailOffset = context.tailOffset
                    context.document.replaceString(startOffset, tailOffset, dummyMemberText)

                    val psiDocumentManager = PsiDocumentManager.getInstance(context.project)
                    psiDocumentManager.commitDocument(context.document)

                    val dummyMember = context.file.findElementAt(startOffset)!!.getStrictParentOfType<KtNamedDeclaration>()!!

                    // keep original modifiers
                    val psiFactory = KtPsiFactory(context.project)
                    val modifierList = psiFactory.createModifierList(dummyMember.modifierList!!.text)

                    fun isCommentOrWhiteSpace(e: PsiElement) = e is PsiComment || e is PsiWhiteSpace
                    fun createCommentOrWhiteSpace(e: PsiElement) =
                        if (e is PsiComment) psiFactory.createComment(e.text) else psiFactory.createWhiteSpace(e.text)

                    val dummyMemberChildren = dummyMember.allChildren
                    val headComments = dummyMemberChildren.takeWhile(::isCommentOrWhiteSpace).map(::createCommentOrWhiteSpace).toList()
                    val tailComments =
                        dummyMemberChildren.toList().takeLastWhile(::isCommentOrWhiteSpace).map(::createCommentOrWhiteSpace)

                    val prototype = memberObject.generateMember(classOrObject, false)
                    prototype.modifierList!!.replace(modifierList)
                    val insertedMember = dummyMember.replaced(prototype)
                    if (memberObject.descriptor.isSuspend) insertedMember.addModifier(KtTokens.SUSPEND_KEYWORD)

                    val insertedMemberParent = insertedMember.parent
                    headComments.forEach { insertedMemberParent.addBefore(it, insertedMember) }
                    tailComments.reversed().forEach { insertedMemberParent.addAfter(it, insertedMember) }

                    ShortenReferences.DEFAULT.process(insertedMember)

                    if (isConstructorParameter) {
                        psiDocumentManager.doPostponedOperationsAndUnblockDocument(context.document)

                        val offset = insertedMember.endOffset
                        val chars = context.document.charsSequence
                        val commaOffset = chars.indexOfSkippingSpace(',', offset)!!
                        val atCharOffset = chars.indexOfSkippingSpace('@', commaOffset + 1)!!
                        context.document.deleteString(offset, atCharOffset + 1)

                        context.editor.moveCaret(offset)
                    } else {
                        moveCaretIntoGeneratedElement(context.editor, insertedMember)
                    }
                }
            }

            lookupElement.assignPriority(if (isImplement) ItemPriority.IMPLEMENT else ItemPriority.OVERRIDE)

            collector.addElement(lookupElement)
        }
    }

    private fun canOverride(descriptorToOverride: CallableMemberDescriptor, declaration: KtCallableDeclaration): Boolean {
        when (declaration) {
            is KtFunction -> return descriptorToOverride is FunctionDescriptor

            is KtValVarKeywordOwner -> {
                if (descriptorToOverride !is PropertyDescriptor) return false
                return if (declaration.valOrVarKeyword?.node?.elementType == KtTokens.VAL_KEYWORD) {
                    !descriptorToOverride.isVar
                } else {
                    true // var can override either var or val
                }
            }

            else -> return false
        }
    }
}