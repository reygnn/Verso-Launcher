package com.github.reygnn.guru

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A report as ACRA's JSON, for POST /report. The family's backend requires PACKAGE_NAME and
 * STACK_TRACE, groups by the exception class plus the frames in the trace's first three lines,
 * and — for ANRs and native crashes, which have none there — by PACKAGE_NAME and
 * CUSTOM_DATA.GURU_MEDITATION (CLAUDE.md, hard rule 10). REPORT_ID, USER_CRASH_DATE and
 * INSTALLATION_ID are what Acrarium requires; they stay, so a stock ACRA server works too.
 * Hand-written, so the module needs no org.json on the JVM; the escaping is the part under test.
 */
internal object AcraJson {

    /** Acrarium's own parse pattern; anything else is silently taken as "now". */
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

    fun serialize(report: GuruReport, installationId: String, zone: ZoneId): String {
        val env = report.env
        val fields = buildList<Pair<String, String>> {
            add("REPORT_ID" to str(report.id))
            add("INSTALLATION_ID" to str(installationId))
            add("APP_VERSION_CODE" to env.versionCode.toString())
            add("APP_VERSION_NAME" to str(env.versionName))
            add("PACKAGE_NAME" to str(env.packageName))
            add("ANDROID_VERSION" to str(env.androidRelease))
            add("PHONE_MODEL" to str(env.model))
            add("BRAND" to str(env.brand))
            add("BUILD" to obj("BRAND" to str(env.brand), "MODEL" to str(env.model), "DEVICE" to str(env.device),
                "VERSION" to obj("RELEASE" to str(env.androidRelease), "SDK_INT" to env.sdk.toString())))
            add("STACK_TRACE" to str(report.trace))
            add("USER_CRASH_DATE" to str(date(report.crashedAt, zone)))
            if (report.startedAt > 0) add("USER_APP_START_DATE" to str(date(report.startedAt, zone)))
            add("IS_SILENT" to "false")
            add("CUSTOM_DATA" to obj("GURU_MEDITATION" to str(report.number), "GURU_KIND" to str(report.kind.name),
                "THREAD" to str(report.thread)))
        }
        return obj(*fields.toTypedArray())
    }

    fun date(epochMillis: Long, zone: ZoneId): String = DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** A JSON string literal. Lone surrogates are escaped, not replaced: the text stays exact. */
    fun str(value: String): String = buildString(value.length + 2) {
        append('"')
        for ((i, c) in value.withIndex()) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == ' ' || c == ' ' || isLoneSurrogate(value, i) -> append(unicode(c))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> "${str(key)}:$value" }

    private fun unicode(c: Char) = String.format(Locale.ROOT, "\\u%04x", c.code)

    private fun isLoneSurrogate(s: String, i: Int): Boolean {
        val c = s[i]
        return when {
            c.isHighSurrogate() -> i + 1 >= s.length || !s[i + 1].isLowSurrogate()
            c.isLowSurrogate() -> i == 0 || !s[i - 1].isHighSurrogate()
            else -> false
        }
    }
}
