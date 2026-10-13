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
     *  rename can be applied (or cleared, falling back to this) without re-enumerating. */
    val systemLabel: String,
) : Ordered {
    /**
     * "package/class" — the identity key for hidden/favorites/names/tags everywhere, and the
     * same string ComponentName.flattenToString() produces, so configs written by Sigil (or by
     * an earlier Verso) keep matching. Per launcher ACTIVITY, not per package: a package can
     * expose several launcher entries, and each must toggle and rename on its own. A body val,
     * so it stays out of equals/hashCode/copy — the constructor fields already define identity.
     */
    override val key: String = "$packageName/$className"

    /** The same app under a different displayed label (rename / clear rename). */
    fun withLabel(newLabel: String): AppEntry =
        copy(label = newLabel, labelLower = LauncherLogic.foldLabel(newLabel))

    companion object {
        fun of(label: String, packageName: String, className: String, systemLabel: String) =
            AppEntry(label, LauncherLogic.foldLabel(label), packageName, className, systemLabel)
    }
}
