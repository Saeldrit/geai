package com.github.saeldrit.geai.anchor.resolvers

import com.github.saeldrit.geai.anchor.AnchorException
import com.github.saeldrit.geai.anchor.AnchorResolver
import com.github.saeldrit.geai.anchor.ResolvedAnchor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

object PsiAnchorResolver : AnchorResolver {

    override val scheme = "psi"

    private const val MAX_CONTENT = 2000

    override fun resolve(locator: String, project: Project): ResolvedAnchor {
        val hash = locator.indexOf('#')
        val fqName = (if (hash >= 0) locator.substring(0, hash) else locator).trim()
        val member = if (hash >= 0) locator.substring(hash + 1).trim().ifBlank { null } else null
        if (fqName.isEmpty()) throw AnchorException("psi: empty class name in '$locator'")

        if (DumbService.isDumb(project))
            throw AnchorException("psi: IDE is still indexing — retry after indexing completes.")

        return ReadAction.compute<ResolvedAnchor, RuntimeException> {
            val psiClass = try {
                JavaPsiFacade.getInstance(project).findClass(fqName, GlobalSearchScope.allScope(project))
            } catch (_: NoClassDefFoundError) {
                throw AnchorException("psi: Java plugin (com.intellij.modules.java) is not available in this IDE; use file: or openapi: anchors.")
            } catch (_: ClassNotFoundException) {
                throw AnchorException("psi: Java plugin (com.intellij.modules.java) is not available in this IDE; use file: or openapi: anchors.")
            } ?: throw AnchorException("psi: class '$fqName' not found — check the fully-qualified name and that the project is indexed.")
            val element: PsiElement = if (member == null) psiClass else findMember(psiClass, member, fqName)
            val fullText = element.text ?: signatureOf(element).orEmpty()
            ResolvedAnchor.of(
                ref = "psi:$locator",
                kind = "symbol",
                location = locationOf(element),
                signature = signatureOf(element),
                content = fullText.take(MAX_CONTENT),
                hashSource = fullText,
            )
        }
    }

    private fun findMember(psiClass: PsiClass, member: String, fqName: String): PsiElement {
        psiClass.findMethodsByName(member, true).firstOrNull()?.let { return it }
        psiClass.findFieldByName(member, true)?.let { return it }
        psiClass.innerClasses.firstOrNull { it.name == member }?.let { return it }
        throw AnchorException("psi: member '$member' not found on $fqName")
    }

    private fun signatureOf(element: PsiElement): String? =
        element.text?.substringBefore("{")?.trim()?.lines()?.joinToString(" ") { it.trim() }?.take(300)

    private fun locationOf(element: PsiElement): String? {
        val virtualFile = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return virtualFile.path
        val line = document.getLineNumber(element.textOffset) + 1
        return "${virtualFile.path}:$line"
    }
}
