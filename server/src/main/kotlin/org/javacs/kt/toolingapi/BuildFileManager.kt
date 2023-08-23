package org.javacs.kt.toolingapi

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptModel
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptsModel

import org.javacs.kt.LOG
import org.javacs.kt.compiler.CompilationEnvironment
import org.javacs.kt.gradleversions.GradleVersionsManager
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

object BuildFileManager {
    var buildEnvByFile: MutableMap<Path, CompilationEnvironment> = mutableMapOf()

    // workspaces which TAPI was invoked for at least once
    private var invokedWorkspaces = mutableSetOf<Path>()

    private var TAPICallFailed = false

    private val error = Diagnostic(Range(Position(0, 0), Position(0, 0)), String())

    private var commonBuildClasspath = emptySet<Path>()

    private fun createDefaultModel(): KotlinDslScriptModel {
        // KLS takes build classpath from temporary settings build file
        // to provide correct default classpath and correct default implicit imports in CompilationEnvironent
        val tempDir: Path = Files.createTempDirectory("temp-dir").toAbsolutePath()
        val settingsBuildFile = tempDir.resolve("settings.gradle.kts")
        Files.write(settingsBuildFile, "".toByteArray())

        val models = getKotlinDSLScriptsModels(tempDir.toFile()).second
        LOG.info { "Default build model has been created" }
        return models.entries.first().value
    }

    val defaultModel: KotlinDslScriptModel = createDefaultModel()

    fun getError(): Diagnostic = error

    fun buildConfContainsError(): Boolean = TAPICallFailed

    fun updateBuildEnvironment(pathToFile: Path, updatedPluginBlock: Boolean = false) {
        val workspace = pathToFile.parent
        LOG.info { "UPDATE build env $workspace" }

        val workspaceForCall = WorkspaceForCallFinder.getWorkspaceForCall(workspace) ?: return

        if (invokedWorkspaces.contains(workspace) && !updatedPluginBlock) return
        invokedWorkspaces.add(workspaceForCall)

        createEnvForEachFile(workspaceForCall.toFile())
    }

    fun updateAllBuildEnvironments() {
        LOG.info {
            "UPDATE all build environments"
        }

        for (workspace in invokedWorkspaces) {
            createEnvForEachFile(workspace.toFile())
        }
        TAPICallFailed = false
    }

    fun isBuildGradleKTS(file: Path): Boolean =
        file.fileName.toString().let { it == "build.gradle.kts" }

    fun isGradleKTS(file: Path): Boolean = file.fileName.toString().endsWith(".gradle.kts")

    fun getCommonBuildClasspath(): Set<Path> {
        if (commonBuildClasspath.isNotEmpty()) {
            return commonBuildClasspath
        }
        return defaultModel.classPath.map { it.toPath() }.toSet()
    }

    fun removeWorkspace(pathToWorkspace: Path) {
        buildEnvByFile =
            buildEnvByFile.filter { !it.key.startsWith(pathToWorkspace) }.toMutableMap()
        invokedWorkspaces =
            invokedWorkspaces.filter { !it.startsWith(pathToWorkspace) }.toMutableSet()
    }

    private fun createEnvForEachFile(workspace: File) {
        val (success, scriptModelByFile) = getKotlinDSLScriptsModels(workspace)
        LOG.info { "[TAPI success=$success] workspace=$workspace" }
        if (!success) {
            TAPICallFailed = true
            return
        }

        for ((file, model) in scriptModelByFile) {
            val classpath = model.classPath.map { it.toPath() }.let { HashSet(it) }
            val implicitImports = model.implicitImports
            commonBuildClasspath += classpath
            buildEnvByFile[file.toPath()] =
                CompilationEnvironment(emptySet(), classpath, implicitImports)
        }
    }

    private fun getKotlinDSLScriptsModels(pathToDirs: File): Pair<Boolean, Map<File, KotlinDslScriptModel>> {
        // TODO: use a gradle version from a gradle wrapper of each project
        var gradleConnector = GradleConnector.newConnector().forProjectDirectory(pathToDirs)
        val gradleDistributionURL =
            GradleVersionsManager.getDistributionURLFromWrapper(pathToDirs.toPath())
        gradleConnector = if (gradleDistributionURL != null) {
            LOG.info { "used distribution $gradleDistributionURL" }
            gradleConnector.useDistribution(URI.create(gradleDistributionURL))
        } else {
            val lastGradleVersion = GradleVersionsManager.getLastGradleVersion()
            gradleConnector.useGradleVersion(lastGradleVersion)
        }


        gradleConnector
            .connect().use {
                return try {
                    val action = CompositeModelQuery(KotlinDslScriptsModel::class.java)
                    val result = it.action(action).run()
                    val models = LinkedHashMap<File, KotlinDslScriptModel>()
                    result?.values?.forEach { kotlinDslScriptsModel ->
                        run {
                            val model = kotlinDslScriptsModel.scriptModels
                            models.putAll(model)
                        }
                    }

                    LOG.debug { "models : ${models.keys}" }
                    Pair(true, models)
                } catch (e: Exception) {
                    initializeErrorMessage(e)
//                val stackTrace = e.stackTraceToString()
                    Pair(false, emptyMap())
                }
            }
    }

    private fun initializeErrorMessage(e: Exception) {
        // take message before last because it's full of information about file and it's errors
        var lastMessage = e.message
        var previousMessage = e.message
        var cause = e.cause
        while (cause != null) {
            previousMessage = lastMessage
            lastMessage = cause.message
            cause = cause.cause
        }
        error.message = "Fix errors and save the file \n\n $previousMessage"
    }
}
