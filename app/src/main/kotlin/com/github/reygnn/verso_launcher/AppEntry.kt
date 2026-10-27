package com.github.reygnn.verso_launcher

/**
 * One launchable app row. Plain Kotlin (no ComponentName) so the state layer stays JVM-testable;
 * the Activity builds the ComponentName only when it launches.
 */
internal data class AppEntry(
    /** The custom name if one is set, else [systemLabel]. */
    val label: String,
    override val packageName: String,
    val className: String,
    /** The system's label without any custom name; for another profile it carries the badge ("Gmail (Work)"). */
    val systemLabel: String,
    /** The profile's serial number (stable across reboots), or null for the personal profile. */
    val profile: Long? = null,
    /** Not part of [key]: a paused or archived app keeps its favorite/name state. */
    val status: AppStatus = AppStatus.READY,
) : Ordered {
    /** Per launcher activity, not per package. A body val, so it stays out of equals/hashCode/copy. */
    override val key: String = keyOf(packageName, className, profile)

    override val labelLower: String = LauncherLogic.foldLabel(label)

    fun withLabel(newLabel: String): AppEntry =
        copy(label = newLabel)

    companion object {
        /**
         * "package/class" (ComponentName.flattenToString()) for the personal profile,
         * "package/class#<serial>" for another one (hard rule 8). "#" cannot occur in either name.
         */
        fun keyOf(packageName: String, className: String, profile: Long?): String =
            if (profile == null) "$packageName/$className" else "$packageName/$className#$profile"

        /**
         * [key] taken apart, or null when [keyOf] could not have built it (e.g. "#+10", "#010",
         * "#-1"); such a key can never match an app.
         */
        fun parseKey(key: String): KeyParts? {
            val hash = key.lastIndexOf('#')
            val component = if (hash < 0) key else key.substring(0, hash)
            val profile = if (hash < 0) null else key.substring(hash + 1).toLongOrNull() ?: return null
            if (profile != null && profile < 0) return null
            val packageName = component.substringBefore('/', "")
            val className = component.substringAfter('/', "")
            if (!PACKAGE.matches(packageName) || !CLASS.matches(className)) return null
            return KeyParts(packageName, className, profile).takeIf { keyOf(packageName, className, profile) == key }
        }

        // Class names stay loose: dex allows far more than Java identifiers (an obfuscated "é-1").
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
        private val CLASS = Regex("[^/#\\s]+")
    }
}

/**
 * Whether a listed app can start right away. Non-ready apps stay listed, dimmed: a tap on a
 * PAUSED one (work profile off) turns the profile on, on an ARCHIVED one lets the system restore it.
 */
internal enum class AppStatus {
    READY, PAUSED, ARCHIVED;

    companion object {
        /** A paused profile wins: an archived app in it cannot be restored until the profile is on. */
        fun of(profilePaused: Boolean, archived: Boolean): AppStatus = when {
            profilePaused -> PAUSED
            archived -> ARCHIVED
            else -> READY
        }
    }
}

internal data class KeyParts(val packageName: String, val className: String, val profile: Long?)

/** Every profile's launcher activities ([apps], system labels); [complete] false when a profile or an app could not be read. */
internal class Enumeration(
    val apps: List<AppEntry>,
    val complete: Boolean,
)
