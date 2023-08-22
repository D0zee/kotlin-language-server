package org.javacs.kt.gradleversions

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Represents a released version of Gradle
 */
class GradleVersion private constructor(val version: String?) : Comparable<GradleVersion> {
    private var snapshot: Long? = null
    private val versionPart: String?
    private var stage: Stage? = null

    init {
        val matcher = VERSION_PATTERN.matcher(
            version
        )
        require(matcher.matches()) {
            String.format(
                "'%s' is not a valid Gradle version string (examples: '1.0', '1.0-rc-1')",
                version
            )
        }
        versionPart = matcher.group(1)
        if (matcher.group(4) != null) {
            val stageNumber: Int
            stageNumber = if (matcher.group(5) == "milestone") {
                STAGE_MILESTONE
            } else if (matcher.group(5) == "preview") {
                2
            } else if (matcher.group(5) == "rc") {
                3
            } else {
                1
            }
            val stageString = matcher.group(6)
            stage = Stage(stageNumber, stageString)
        } else {
            stage = null
        }
        if ("snapshot" == matcher.group(5)) {
            snapshot = 0L
        } else if (matcher.group(8) == null) {
            snapshot = null
        } else if ("SNAPSHOT" == matcher.group(8)) {
            snapshot = 0L
        } else {
            try {
                if (matcher.group(9) != null) {
                    snapshot = SimpleDateFormat("yyyyMMddHHmmssZ").parse(matcher.group(8)).time
                } else {
                    val format = SimpleDateFormat("yyyyMMddHHmmss")
                    format.timeZone = TimeZone.getTimeZone("UTC")
                    snapshot = format.parse(matcher.group(8)).time
                }
            } catch (e: ParseException) {
                throw RuntimeException(e)
            }
        }
    }

    override fun toString(): String {
        return "Gradle " + version
    }

    fun isSnapshot(): Boolean {
        return snapshot != null
    }

    val baseVersion: GradleVersion
        /**
         * The base version of this version. For pre-release versions, this is the target version.
         *
         * For example, the version base of '1.2-rc-1' is '1.2'.
         *
         * @return The version base
         */
        get() = if (stage == null && snapshot == null) {
            this
        } else version(versionPart)

    override fun compareTo(other: GradleVersion): Int {
        val majorVersionParts = versionPart!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()
        val otherMajorVersionParts =
            other.versionPart!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
        var i = 0
        while (i < majorVersionParts.size && i < otherMajorVersionParts.size) {
            val part = majorVersionParts[i].toInt()
            val otherPart = otherMajorVersionParts[i].toInt()
            if (part > otherPart) {
                return 1
            }
            if (otherPart > part) {
                return -1
            }
            i++
        }
        if (majorVersionParts.size > otherMajorVersionParts.size) {
            return 1
        }
        if (majorVersionParts.size < otherMajorVersionParts.size) {
            return -1
        }
        if (stage != null && other.stage != null) {
            val diff = stage!!.compareTo(other.stage)
            if (diff != 0) {
                return diff
            }
        }
        if (stage == null && other.stage != null) {
            return 1
        }
        if (stage != null && other.stage == null) {
            return -1
        }
        if (snapshot != null && other.snapshot != null) {
            return snapshot!!.compareTo(other.snapshot!!)
        }
        if (snapshot == null && other.snapshot != null) {
            return 1
        }
        return if (snapshot != null && other.snapshot == null) {
            -1
        } else 0
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other == null || other.javaClass != javaClass) {
            return false
        }
        val o = other as GradleVersion
        return version == o.version
    }

    override fun hashCode(): Int {
        return version.hashCode()
    }

    val isValid: Boolean
        get() = versionPart != null

    fun supportsCompositeBuilds(): Boolean {
        return baseVersion.compareTo(version("3.3")) >= 0
    }

    fun supportsDashDashScan(): Boolean {
        return baseVersion.compareTo(version("3.5")) >= 0
    }

    fun supportsSyncTasksInEclipsePluginConfig(): Boolean {
        return baseVersion.compareTo(version("5.4")) >= 0
    }

    fun supportsSendingReservedProjects(): Boolean {
        return baseVersion.compareTo(version("5.5")) >= 0
    }

    fun supportsTestAttributes(): Boolean {
        return baseVersion.compareTo(version("5.6")) >= 0
    }

    fun supportsClosedProjectDependencySubstitution(): Boolean {
        return baseVersion.compareTo(version("5.6")) >= 0
    }

    fun supportsTestDebugging(): Boolean {
        return baseVersion.compareTo(version("5.6")) >= 0
    }

    fun supportsTaskExecutionInIncudedBuild(): Boolean {
        return baseVersion.compareTo(version("6.8")) >= 0
    }

    /**
     * Utility class to compare snapshot/milesone/rc releases.
     */
    internal class Stage(val stage: Int, number: String) : Comparable<Stage?> {
        var number_m = 0
        var patchNo: Char? = null

        init {
            val m = Pattern.compile("(\\d+)([a-z])?").matcher(number)
            try {
                m.matches()
                number_m = m.group(1).toInt()
            } catch (e: Exception) {
                throw RuntimeException("Invalid stage small number: $number", e)
            }
            if (m.groupCount() == 2 && m.group(2) != null) {
                patchNo = m.group(2)[0]
            } else {
                patchNo = '_'
            }
        }

        override fun compareTo(other: Stage?): Int {
            if (stage > other!!.stage) {
                return 1
            }
            if (stage < other.stage) {
                return -1
            }
            if (number_m > other.number_m) {
                return 1
            }
            if (number_m < other.number_m) {
                return -1
            }
            if (patchNo!! > other.patchNo!!) {
                return 1
            }
            return if (patchNo!! < other.patchNo!!) {
                -1
            } else 0
        }
    }

    companion object {
        private val VERSION_PATTERN =
            Pattern.compile("((\\d+)(\\.\\d+)+)(-(\\p{Alpha}+)-(\\d+[a-z]?))?(-(SNAPSHOT|\\d{14}([-+]\\d{4})?))?")
        private const val STAGE_MILESTONE = 0

        /**
         * Parses the given string into a GradleVersion.
         *
         * @throws IllegalArgumentException On unrecognized version string.
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun version(version: String?): GradleVersion {
            return GradleVersion(version)
        }
    }
}
