package org.javacs.kt.gradleversions

import org.javacs.kt.gradleversions.PublishedGradleVersions.LookupStrategy
import org.javacs.kt.LOG
object GradleVersionsManager {
    private val lastVersion = searchLastGradleVersion() ?: "8.2.1"
    private fun searchLastGradleVersion(): String? {
        try {
            val publishedVersions = PublishedGradleVersions.create(LookupStrategy.REMOTE).getVersions()
            if (publishedVersions.isNotEmpty()) {
                val latestVersion = publishedVersions.first().version
                LOG.info {"Last version of Gradle is $latestVersion"}
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
}

