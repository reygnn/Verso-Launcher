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
        ) = AppEntry(label, LauncherLogic.foldLabel(label), packageName, className, systemLabel, profile)

        /**
         * "package/class" for the personal profile — exactly ComponentName.flattenToString(),
         * the key every config and backup has always used, so they keep matching unchanged.
         * The same app in another profile (work, clone) is a different row with its own
         * favorite/hidden/name state, so it gets the profile's serial appended:
         * "package/class#10". "#" cannot occur in a package or class name.
         */
        fun keyOf(packageName: String, className: String, profile: Long?): String =
            if (profile == null) "$packageName/$className" else "$packageName/$className#$profile"
    }
}
