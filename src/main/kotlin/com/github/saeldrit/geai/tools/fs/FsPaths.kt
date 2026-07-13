package com.github.saeldrit.geai.tools.fs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

internal object FsPaths {

    fun resolve(project: Project, path: String): VirtualFile? {
        val normalized = path.trim().replace('\\', '/')
        val lfs = LocalFileSystem.getInstance()
        lfs.findFileByPath(normalized)?.let { return it }
        val base = project.basePath ?: return null
        return lfs.findFileByPath("$base/${normalized.trimStart('/')}")
    }

    fun projectRoot(project: Project): VirtualFile? {
        val base = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath(base)
    }

    fun relativize(project: Project, file: VirtualFile): String {
        val base = project.basePath?.replace('\\', '/') ?: return file.path
        val path = file.path
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }

    fun isInsideProject(project: Project, file: VirtualFile): Boolean {
        val base = project.basePath?.replace('\\', '/') ?: return false
        val path = file.path
        return path == base || path.startsWith("$base/")
    }
}
