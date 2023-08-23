package org.javacs.kt.toolingapi

import org.javacs.kt.LOG
import java.io.FileInputStream
import java.nio.file.Path
import kotlin.io.path.exists
import java.util.Properties


object WorkspaceForCallFinder {

    fun getWorkspaceForCall(workspace: Path): Path? {
        val parent = findParent(workspace)
        if (parent != null) {
            LOG.debug { "parent for $workspace is $parent" }
            return parent
        }

        if (containsSettingsFile(workspace)) {
            LOG.debug { "$workspace contains settings file" }
            return workspace
        }
        return null
    }

    private fun findParent(pathToWorkspace: Path): Path? {
        val prefsFile =
            pathToWorkspace.resolve(".settings").resolve("org.eclipse.buildship.core.prefs")

        val corePrefs = Properties()
        try{
            corePrefs.load(FileInputStream(prefsFile.toFile()))
        }
        catch (e: Exception){
            return null
        }

        val relativePathToParent = corePrefs.getProperty("connection.project.dir") ?: return null
        return pathToWorkspace.resolve(relativePathToParent).normalize()
    }

    private fun containsSettingsFile(path: Path): Boolean {
        val directory = path.toFile()
        if (directory.isDirectory) {
            return directory.listFiles().any { it.name == "settings.gradle.kts" }
        }
        return false
    }
}
