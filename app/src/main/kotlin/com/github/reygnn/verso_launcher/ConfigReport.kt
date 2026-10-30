package com.github.reygnn.verso_launcher

/** One thing "~verify" says about the user's file, a row of its own. */
internal sealed interface ConfigFinding {
    /** The file was not read ([status] is never [ConfigStatus.READ]): the default favorites show. */
    data class NotRead(val status: ConfigStatus) : ConfigFinding

    /** A line that is no entry, skipped. */
    data class BadLine(val line: ConfigLine) : ConfigFinding

    /** An entry that names no app of the list ([ConfigText.unresolved]). */
    data class NoApp(val line: ConfigLine) : ConfigFinding

    /** The list is not whole, so a [NoApp] may be an app it misses, or none could be said yet. */
    data object ListIncomplete : ConfigFinding

    /** Read, every line an entry, every entry an app. */
    data object AllGood : ConfigFinding
}

/**
 * What "~verify" lists about the user's file. Pure: the state hands in what its read found and its
 * app list.
 *
 * Why: the read itself only logs (hard rules 2 and 4); this is the one way a user learns, on the
 *   phone, why a line of theirs counts for nothing. It reports and changes nothing, and is shown
 *   only when asked for: no dialog, no notice, a home app must not nag about a file the user can
 *   only fix on a PC.
 */
internal object ConfigReport {

    /**
     * The findings in reading order: the file's status, then each line by its number. [apps] null
     * while no list is in: then no entry can be called unresolved, and [ConfigFinding.ListIncomplete]
     * says so. [complete] false: the last enumeration missed a profile or failed.
     */
    fun findings(status: ConfigStatus, text: ConfigText, apps: List<AppEntry>?, complete: Boolean): List<ConfigFinding> {
        if (status != ConfigStatus.READ) return listOf(ConfigFinding.NotRead(status))
        val bad = text.badLines.map { ConfigFinding.BadLine(it) }
        val noApp = apps?.let { list -> ConfigText.unresolved(text, list).map { ConfigFinding.NoApp(it) } }.orEmpty()
        val lines = (bad + noApp).sortedBy { lineOf(it) }
        val incomplete = if (apps == null || !complete) listOf(ConfigFinding.ListIncomplete) else emptyList()
        return (lines + incomplete).ifEmpty { listOf(ConfigFinding.AllGood) }
    }

    private fun lineOf(finding: ConfigFinding): Int = when (finding) {
        is ConfigFinding.BadLine -> finding.line.number
        is ConfigFinding.NoApp -> finding.line.number
        else -> 0
    }
}
