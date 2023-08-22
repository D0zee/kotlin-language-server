package org.javacs.kt.gradleversions

import com.google.common.base.Charsets
import com.google.common.base.Function
import com.google.common.base.Optional
import com.google.common.base.Predicate
import com.google.common.collect.FluentIterable
import com.google.common.collect.ImmutableList
import com.google.common.io.CharSource
import com.google.common.io.CharStreams
import com.google.common.io.Closeables
import com.google.common.io.Files
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.javacs.kt.gradleversions.GradleVersion.Companion.version
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Provides information about the Gradle versions available from services.gradle.org. The version
 * information can optionally be cached on the local file system.
 */
class PublishedGradleVersions private constructor(versions: List<Map<String, String>>) {
    private val versions: List<Map<String, String>>

    init {
        this.versions = ImmutableList.copyOf(versions)
    }

    /**
     * Returns all final Gradle versions plus the latest active release candidate, if available.
     *
     * @return the matching versions
     */
    fun getVersions(): List<GradleVersion> {
        return FluentIterable.from(versions).filter { input ->
            (
                (input?.get(ACTIVE_RC)?.toBoolean() == true || input?.get(RC_FOR)
                    .isNullOrEmpty()) &&
                    input?.get(BROKEN)?.toBoolean() == false &&
                    input[SNAPSHOT]?.toBoolean() == false
                )
        }.transform {
            version(it?.get(VERSION))

        }.filter { input ->
            (((input?.compareTo(version(MINIMUM_SUPPORTED_GRADLE_VERSION)) ?: -1) >= 0))
        }.toList()
    }

    /**
     * Determines how Gradle versions are retrieved.
     *
     * @author Stefan Oehme
     */
    enum class LookupStrategy {
        /**
         * Look only in the local cache file. Fail if it does not exist or is unreadable.
         */
        CACHED_ONLY,

        /**
         * Look in the local cache file first. Try a remote call if it cannot be read. If the remote
         * call succeeds, store the result in the cache. Fail if the remote call fails.
         */
        REMOTE_IF_NOT_CACHED,

        /**
         * Disable caching, execute a remote call directly. Fail if the remote call fails.
         */
        REMOTE
    }

    companion object {
        // end-point that provides full version information
        private const val VERSIONS_URL = "https://services.gradle.org/versions/all"

        // the minimum Gradle version considered
        private const val MINIMUM_SUPPORTED_GRADLE_VERSION = "2.6"

        // JSON keys
        private const val VERSION = "version"
        private const val SNAPSHOT = "snapshot"
        private const val ACTIVE_RC = "activeRc"
        private const val RC_FOR = "rcFor"
        private const val BROKEN = "broken"

        /**
         * Creates a new instance based on the version information available on services.gradle.org.
         *
         * @param lookupStratgy the strategy to use when retrieving the versions
         * @return the new instance
         */
        fun create(lookupStratgy: LookupStrategy): PublishedGradleVersions {
            if (lookupStratgy == LookupStrategy.REMOTE) {
                val json = downloadVersionInformation()
                return create(json)
            }
            val cacheFile = cacheFile
            if (!cacheFile.isFile || !cacheFile.exists()) {
                return tryToDownloadAndCacheVersions(cacheFile, lookupStratgy)
            }
            return if (cacheFile.lastModified() > System.currentTimeMillis() - TimeUnit.DAYS.toMillis(
                    1
                )
            ) {
                tryToReadUpToDateVersionsFile(cacheFile, lookupStratgy)
            } else {
                tryToUpdateOutdatedVersionsFile(cacheFile, lookupStratgy)
            }
        }

        private fun tryToReadUpToDateVersionsFile(
            cacheFile: File,
            lookupStratgy: LookupStrategy
        ): PublishedGradleVersions {
            val cachedVersions = readCacheVersionsFile(cacheFile)
            return if (cachedVersions.isPresent) {
                create(cachedVersions.get())
            } else {
                tryToDownloadAndCacheVersions(cacheFile, lookupStratgy)
            }
        }

        private fun tryToUpdateOutdatedVersionsFile(
            cacheFile: File,
            lookupStratgy: LookupStrategy
        ): PublishedGradleVersions {
            return try {
                tryToDownloadAndCacheVersions(cacheFile, lookupStratgy)
            } catch (e: RuntimeException) {
                val cachedVersions = readCacheVersionsFile(cacheFile)
                if (cachedVersions.isPresent) {
                    create(cachedVersions.get())
                } else {
                    throw IllegalStateException(
                        "Cannot collect Gradle version information remotely nor locally.", e
                    )
                }
            }
        }

        private fun tryToDownloadAndCacheVersions(
            cacheFile: File,
            lookupStratgy: LookupStrategy
        ): PublishedGradleVersions {
            check(lookupStratgy != LookupStrategy.CACHED_ONLY) { "Could not get Gradle version information from cache and remote update was disabled" }
            val json = downloadVersionInformation()
            storeCacheVersionsFile(json, cacheFile)
            return create(json)
        }

        private fun downloadVersionInformation(): String {
            var connection: HttpURLConnection? = null
            var reader: InputStreamReader? = null
            return try {
                val url = createURL(VERSIONS_URL)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                reader = InputStreamReader(connection.inputStream, Charsets.UTF_8)
                CharStreams.toString(reader)
            } catch (e: IOException) {
                throw RuntimeException("Cannot download published Gradle versions.", e)
                // throw an exception if version information cannot be downloaded since we need this information
            } finally {
                try {
                    Closeables.close(reader, false)
                } catch (e: IOException) {
                }
                connection?.disconnect()
            }
        }

        private fun storeCacheVersionsFile(json: String, cacheFile: File) {
            cacheFile.parentFile.mkdirs()
            try {
                CharSource.wrap(json).copyTo(Files.asCharSink(cacheFile, Charsets.UTF_8))
            } catch (e: IOException) {
                // do not throw an exception if cache file cannot be written to be more robust against file system problems
            }
        }

        private fun readCacheVersionsFile(cacheFile: File): Optional<String> {
            return try {
                Optional.of(Files.toString(cacheFile, Charsets.UTF_8))
            } catch (e: IOException) {
                // do not throw an exception if cache file cannot be read to be more robust against file system problems
                Optional.absent()
            }
        }

        private fun create(json: String): PublishedGradleVersions {
            // convert versions from JSON String to JSON Map
            val gson = GsonBuilder().create()
            val typeToken =
                object : TypeToken<List<Map<String?, String?>?>?>() {}
            val versions = gson.fromJson<List<Map<String, String>>>(json, typeToken.type)

            // create instance
            return PublishedGradleVersions(versions)
        }

        private fun createURL(url: String): URL {
            return try {
                URL(url)
            } catch (e: MalformedURLException) {
                throw IllegalArgumentException("Invalid URL: $url", e)
            }
        }

        private val cacheFile: File
            get() {
                // ensures compliance with XDG base spec: https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
                var xdgCache = System.getenv("XDG_CACHE_HOME")
                if (xdgCache == null) {
                    xdgCache = System.getProperty("user.home") + "/.cache/"
                }
                return File(xdgCache, "tooling/gradle/versions.json")
            }
    }
}
