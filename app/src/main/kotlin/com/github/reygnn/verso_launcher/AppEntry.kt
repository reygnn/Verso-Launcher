package com.github.reygnn.verso_launcher

/**
 * One launchable app row: what the drawer, the home page and [LauncherState] work with.
 *
 * Plain Kotlin on purpose. It used to hold an android.content.ComponentName, which kept the
 * whole state layer out of reach of a JVM test; the package and class names as Strings carry
 * exactly the same identity, and the Activity builds the ComponentName only at the moment it
 * launches the app.
 */
internal data class AppEntry(
    /** What is shown: the custom name if one is set, else [systemLabel]. */
    val label: String,
    /** [label] case-folded once ([LauncherLogic.foldLabel]) so sort and search never re-fold. */
    override val labelLower: String,
    val packageName: String,
    val className: String,
    /** The app's own label as the system reports it — WITHOUT any custom name. Kept so a
     *  rename can be applied (or cleared, falling back to this) without re-enumerating. For
     *  an app in another profile it already carries the system's badge ("Gmail (Work)"). */
    val systemLabel: String,
    /** The serial number of the profile (UserManager.getSerialNumberForUser) the app lives in,
     *  or null for the user's own, personal profile. A serial, not a UserHandle: it is plain
     *  data, stable across reboots, and the Activity maps it back when it launches. */
    val profile: Long? = null,
    /** Whether the app can start right away — see [AppStatus]. Not part of the identity [key]:
     *  a paused or archived app is the same row with the same favorite/name state. */
    val status: AppStatus = AppStatus.READY,
) : Ordered {
    /**
     * The identity key for hidden/favorites/names/tags everywhere — see [keyOf]. Per launcher
     * ACTIVITY, not per package: a package can expose several launcher entries, and each must
     * toggle and rename on its own. A body val, so it stays out of equals/hashCode/copy — the
     * constructor fields already define identity.
     */
    override val key: String = keyOf(packageName, className, profile)

    /** The same app under a different displayed label (rename / clear rename). */
    fun withLabel(newLabel: String): AppEntry =
        copy(label = newLabel, labelLower = LauncherLogic.foldLabel(newLabel))

    companion object {
        fun of(
            label: String,
            packageName: String,
            className: String,
            systemLabel: String,
            profile: Long? = null,
            status: AppStatus = AppStatus.READY,
        ) = AppEntry(label, LauncherLogic.foldLabel(label), packageName, className, systemLabel, profile, status)

        /**
         * "package/class" for the personal profile — exactly ComponentName.flattenToString(),
         * the key every config and backup has always used, so they keep matching unchanged.
         * The same app in another profile (work, clone) is a different row with its own
         * favorite/hidden/name state, so it gets the profile's serial appended:
         * "package/class#10". "#" cannot occur in a package or class name.
         */
        fun keyOf(packageName: String, className: String, profile: Long?): String =
            if (profile == null) "$packageName/$className" else "$packageName/$className#$profile"

        /**
         * [key] taken apart — or null when it is no key [keyOf] could have built: no
         * "package/class", a package or class name no app can have, a profile serial that is
         * not a plain non-negative number ("#+10", "#010", "#-1"), anything around them. Such a
         * key can never match an app; "~cleanup" treats it as gone without asking the system.
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

        // What Android accepts as a package name, and a (fully qualified, maybe nested) class.
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
        private val CLASS = Regex("[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)*")
    }
}

/**
 * Whether a listed app can start right away. Both non-ready states stay in the list — shown
 * dimmed — because a tap brings them back: a PAUSED app lives in a work profile that is turned
 * off (quiet mode), and the first tap turns the profile back on; an ARCHIVED app (Android 15+)
 * had its code removed but kept its data, and a tap lets the system restore it.
 */
internal enum class AppStatus {
    READY, PAUSED, ARCHIVED;

    companion object {
        /** A paused profile wins: an archived app in it cannot be restored until the profile is
         *  back on, so turning the profile on is what the first tap must do. */
        fun of(profilePaused: Boolean, archived: Boolean): AppStatus = when {
            profilePaused -> PAUSED
            archived -> ARCHIVED
            else -> READY
        }
    }
}

/** A config key taken apart ([AppEntry.parseKey]); [profile] is null for the personal profile. */
internal data class KeyParts(val packageName: String, val className: String, val profile: Long?)
