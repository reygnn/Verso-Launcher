package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profiles are plain strings here; "me" is the personal one. */
class EnumerationTest {

    private fun app(pkg: String, label: String = pkg, cls: String? = "$pkg.Main", archived: Boolean = false): () -> RawApp =
        { RawApp(pkg, cls, label, archived) }

    private val failing: () -> RawApp = { throw IllegalStateException("binder") }

    private var profiles: () -> List<String> = { listOf("me", "work") }
    private val serials = mutableMapOf("work" to 10L)
    private val paused = mutableSetOf<String>()
    private val lists = mutableMapOf<String, () -> List<() -> RawApp>>(
        "me" to { listOf(app("com.b", "Bravo"), app("com.a", "Alpha")) },
        "work" to { listOf(app("com.w", "Mail")) },
    )

    private fun run() = enumerate(
        profiles = { profiles() },
        personal = "me",
        serialOf = { serials[it] ?: throw IllegalStateException("no serial") },
        paused = { it in paused },
        activities = { lists.getValue(it)() },
        badge = { raw, _, _ -> "$raw (Work)" },
        ownPackage = "com.verso",
    )

    @Test fun `every profile read is complete, sorted, the work copy badged`() {
        val e = run()!!
        assertTrue(e.complete)
        assertEquals(listOf("Alpha", "Bravo", "Mail (Work)"), e.apps.map { it.label })
        assertEquals(10L, e.apps.last().profile)
    }

    @Test fun `the launcher itself and a row without a class are left out`() {
        lists["me"] = { listOf(app("com.verso"), app("com.x", cls = ""), app("com.a")) }
        assertEquals(listOf("com.a"), run()!!.apps.filter { it.profile == null }.map { it.packageName })
    }

    @Test fun `profiles that cannot be read give null, not an empty list`() {
        profiles = { throw IllegalStateException("dead system") }
        assertNull(run())
    }

    @Test fun `a personal profile that cannot be read gives null`() {
        lists["me"] = { throw SecurityException("denied") }
        assertNull(run())
    }

    @Test fun `an empty personal list is never real`() {
        lists["me"] = { emptyList() }
        assertFalse(run()!!.complete)
    }

    @Test fun `a profile without a serial is skipped, incomplete`() {
        serials["work"] = -1
        val e = run()!!
        assertFalse(e.complete)
        assertEquals(listOf("Alpha", "Bravo"), e.apps.map { it.label })
    }

    @Test fun `a profile whose serial lookup throws is skipped, incomplete`() {
        serials.remove("work")
        assertFalse(run()!!.complete)
    }

    @Test fun `a profile whose list cannot be read is skipped, incomplete`() {
        lists["work"] = { throw SecurityException("denied") }
        val e = run()!!
        assertFalse(e.complete)
        assertEquals(listOf("Alpha", "Bravo"), e.apps.map { it.label })
    }

    @Test fun `an app that cannot be read is skipped, incomplete, the others stay`() {
        lists["me"] = { listOf(app("com.a", "Alpha"), failing) }
        val e = run()!!
        assertFalse(e.complete)
        assertEquals(listOf("Alpha", "Mail (Work)"), e.apps.map { it.label })
    }

    /** Not incomplete: Cleanup.plan keeps such a profile's entries by its serial. */
    @Test fun `an empty work profile is reported by serial, still complete`() {
        lists["work"] = { emptyList() }
        val e = run()!!
        assertTrue(e.complete)
        assertEquals(setOf(10L), e.emptyProfiles)
    }

    @Test fun `a paused profile's apps and an archived app carry their status`() {
        paused += "work"
        lists["me"] = { listOf(app("com.a", archived = true)) }
        val e = run()!!
        assertEquals(listOf(AppStatus.ARCHIVED, AppStatus.PAUSED), e.apps.map { it.status })
    }

    @Test fun `a label of nothing but invisible marks falls back to the package`() {
        lists["me"] = { listOf(app("com.a", "​‮")) }
        assertEquals("com.a", run()!!.apps.first().label)
    }
}
