package org.javacs.kt.gradleversions

import org.javacs.kt.gradleversions.PublishedGradleVersions.LookupStrategy
import org.javacs.kt.LOG
import java.nio.file.Path
import kotlin.io.path.exists

object GradleVersionsManager {
    private val lastVersion = searchLastGradleVersion() ?: "8.2.1"
    private fun searchLastGradleVersion(): String? {
        try {
            val publishedVersions =
                PublishedGradleVersions.create(LookupStrategy.REMOTE).getVersions()
            if (publishedVersions.isNotEmpty()) {
                val latestVersion = publishedVersions.first().version
                LOG.info { "Last version of Gradle is $latestVersion" }
                return latestVersion
            }
        } catch (e: Exception) {
            // do nothing, because probably user hasn't internet connection
        }
        return null
    }

    fun getLastGradleVersion(): String {
        return lastVersion
    }

    fun getDistributionURLFromWrapper(pathToDir: Path): String? {
        val propertiesFile =
            pathToDir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties")
        if (!propertiesFile.exists()) {
            return null
        }
        var distributionURL: String? = null
        propertiesFile.toFile().forEachLine {
            if (it.startsWith("distributionUrl")) {
                distributionURL = it.substring(it.indexOf('=') + 1)
            }
        }
        return distributionURL?.trim()?.replace("\\", "")
    }
}

