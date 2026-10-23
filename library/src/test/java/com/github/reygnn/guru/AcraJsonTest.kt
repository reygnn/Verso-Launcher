package com.github.reygnn.guru

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Parsed back with the real (desktop) org.json — the same library Acrarium reads it with. */
class AcraJsonTest {

    @get:Rule val locale = GuruLocaleRule()

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun parsed(r: GuruReport = report(), installation: String = "inst-1", zone: ZoneId = berlin) =
        JSONObject(AcraJson.serialize(r, installation, zone))

    /** Acrarium's ReportService calls getString on these four: one missing and the report is refused. */
    @Test
    fun `the fields Acrarium requires are there`() {
        val json = parsed()
        assertEquals(report().id, json.getString("REPORT_ID"))
        assertEquals(report().trace, json.getString("STACK_TRACE"))
        assertEquals("inst-1", json.getString("INSTALLATION_ID"))
        assertEquals("2025-10-23T08:13:20.000+02:00", json.getString("USER_CRASH_DATE"))
    }

    @Test
    fun `version, device and Guru's own data`() {
        val json = parsed()
        assertEquals(42, json.getInt("APP_VERSION_CODE"))
        assertEquals("1.2.3", json.getString("APP_VERSION_NAME"))
        assertEquals("com.example.app", json.getString("PACKAGE_NAME"))
        assertEquals("16", json.getString("ANDROID_VERSION"))
        assertEquals("Pixel 9a", json.getString("PHONE_MODEL"))
        assertEquals("google", json.getString("BRAND"))
        assertEquals("tegu", json.getJSONObject("BUILD").getString("DEVICE"))
        assertEquals(36, json.getJSONObject("BUILD").getJSONObject("VERSION").getInt("SDK_INT"))
        assertFalse(json.getBoolean("IS_SILENT"))
        val custom = json.getJSONObject("CUSTOM_DATA")
        assertEquals(report().number, custom.getString("GURU_MEDITATION"))
        assertEquals("CRASH", custom.getString("GURU_KIND"))
        assertEquals("main", custom.getString("THREAD"))
    }

    @Test
    fun `the start date is left out when unknown`() {
        assertEquals("2025-10-23T07:56:40.000+02:00", parsed().getString("USER_APP_START_DATE"))
        assertFalse(parsed(report(startedAt = 0)).has("USER_APP_START_DATE"))
    }

    /** Acrarium parses with exactly this pattern and takes anything else as "now" — silently. */
    @Test
    fun `the date parses with Acrarium's pattern, in UTC too`() {
        val acrarium = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        val utc = parsed(zone = ZoneOffset.UTC).getString("USER_CRASH_DATE")
        assertEquals("2025-10-23T06:13:20.000Z", utc)
        assertEquals(report().crashedAt, java.time.Instant.from(acrarium.parse(utc)).toEpochMilli())
    }

    @Test
    fun `the date is ASCII in any default locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("2025-10-23T08:13:20.000+02:00", parsed().getString("USER_CRASH_DATE"))
    }

    @Test
    fun `quotes, backslashes and control characters come back exact`() {
        val trace = "say \"hi\"\\ \n\r\t \u0000 \u001f     😀 end"
        assertEquals(trace, parsed(report(trace = trace)).getString("STACK_TRACE"))
    }

    /** A message may hold half an emoji; replacing it would change the trace. */
    @Test
    fun `lone surrogates are escaped, not replaced`() {
        assertEquals("\\ud83d", AcraJson.str("\uD83D").removeSurrounding("\""))
        assertEquals("\\ude00x", AcraJson.str("\uDE00x").removeSurrounding("\""))
        // The first high surrogate is lone; the second one starts a valid pair and stays as it is.
        assertEquals("a\\ud83d😀", AcraJson.str("a\uD83D😀").removeSurrounding("\""))
        val trace = "half \uD83D emoji"
        assertEquals(trace, parsed(report(trace = trace)).getString("STACK_TRACE"))
    }
}
