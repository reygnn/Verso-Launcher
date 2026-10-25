package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** The file name; saving through MediaStore is GuruSaveTest's. */
class GuruDownloadsTest {

    @get:Rule val locales = GuruLocaleRule()

    private val utc = ZoneOffset.UTC
    private val at = LocalDateTime.of(2026, 10, 24, 14, 3, 12).toInstant(utc).toEpochMilli()
    private val crash = report(crashedAt = at, env = testEnv.copy(versionName = "0.3.1"))

    @Test
    fun `name, version and crash time`() {
        assertEquals("Verso-0.3.1-2026-10-24_14-03-12", GuruDownloads.baseName("Verso", crash, utc))
    }

    @Test
    fun `the crash time is local`() {
        assertEquals("Verso-0.3.1-2026-10-24_16-03-12", GuruDownloads.baseName("Verso", crash, ZoneId.of("Europe/Zurich")))
    }

    @Test
    fun `what a file name should not carry becomes an underscore - letters stay`() {
        assertEquals("Guru_Sample-0.3.1-2026-10-24_14-03-12", GuruDownloads.baseName(" Guru Sample ", crash, utc))
        assertEquals("a_b_c_-0.3.1-2026-10-24_14-03-12", GuruDownloads.baseName("a/b:c*", crash, utc))
        assertEquals("Grüße-0.3.1-2026-10-24_14-03-12", GuruDownloads.baseName("Grüße", crash, utc))
        assertEquals("Verso-1.0_beta-2026-10-24_14-03-12", GuruDownloads.baseName("Verso", report(crashedAt = at,
            env = testEnv.copy(versionName = "1.0 beta")), utc))
        assertEquals("Guru-Sample-1.0-rc1-2026-10-24_14-03-12", GuruDownloads.baseName("Guru-Sample", report(crashedAt = at,
            env = testEnv.copy(versionName = "1.0-rc1")), utc))
        assertEquals("a__b-0.3.1-2026-10-24_14-03-12", GuruDownloads.baseName("a😀b", crash, utc))
    }

    @Test
    fun `the year is the calendar's, not the week's`() {
        val endOfYear = LocalDateTime.of(2024, 12, 30, 10, 0, 0).toInstant(utc).toEpochMilli()
        assertEquals("Verso-2024-12-30_10-00-00", GuruDownloads.baseName("Verso",
            report(crashedAt = endOfYear, env = testEnv.copy(versionName = "")), utc))
    }

    @Test
    fun `label and version are cut, so the name stays under 255 bytes`() {
        val cjk = "崩".repeat(200)
        val name = GuruDownloads.baseName(cjk, report(crashedAt = at, env = testEnv.copy(versionName = "版".repeat(200))), utc)

        assertEquals("崩".repeat(GuruDownloads.MAX_LABEL) + "-" + "版".repeat(GuruDownloads.MAX_VERSION) + "-2026-10-24_14-03-12", name)
        assertTrue("$name (99).json".toByteArray(Charsets.UTF_8).size <= 255)
    }

    /** The prune counts only these (Verso's audit 2026-10-24, B.9). */
    @Test
    fun `Guru's own names are known by their end`() {
        val base = GuruDownloads.baseName("Guru Sample", crash, utc)
        val noVersion = GuruDownloads.baseName("崩", report(crashedAt = at, env = testEnv.copy(versionName = "")), utc)
        listOf("$base.json", "$base (1).json", "$base (12).json", "$noVersion.json").forEach {
            assertTrue(it, GuruDownloads.OWN_NAME.matches(it))
        }
        listOf("Verso-backup.json", "$base.txt", "$base.json.txt", "$base (x).json", "Verso-2026-10-24.json").forEach {
            assertFalse(it, GuruDownloads.OWN_NAME.matches(it))
        }
    }

    @Test
    fun `an empty part is left out`() {
        val noVersion = report(crashedAt = at, env = testEnv.copy(versionName = ""))
        assertEquals("Verso-2026-10-24_14-03-12", GuruDownloads.baseName("Verso", noVersion, utc))
        assertEquals("2026-10-24_14-03-12", GuruDownloads.baseName("", noVersion, utc))
    }

    @Test
    fun `digits stay ASCII under any locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        assertEquals("Verso-0.3.1-2026-10-24_14-03-12", GuruDownloads.baseName("Verso", crash, utc))
    }
}
