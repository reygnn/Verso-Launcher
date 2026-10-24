package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupTest {

    private val here = AppEntry.of("Here", "here", "here.Main", "Here")
    private val config = VersoConfig(
        favorites = listOf(here.key, "gone/Gone", "disabled/Main"),
        hidden = linkedSetOf("gone/Gone"),
    )

    @Test fun `an incomplete or failed enumeration offers nothing, orphans or not`() {
        assertEquals(CleanupPlan.Incomplete, Cleanup.plan(config, Enumeration(listOf(here), complete = false)) { false })
        assertEquals(CleanupPlan.Incomplete, Cleanup.plan(config, null) { false })
    }

    @Test fun `a complete enumeration offers the entries of apps gone from the device`() {
        val plan = Cleanup.plan(config, Enumeration(listOf(here), complete = true)) { false }
        assertEquals(setOf("gone/Gone", "disabled/Main"), (plan as CleanupPlan.Offer).orphans.keys)
    }

    @Test fun `an app still on the device in some form is never offered`() {
        val plan = Cleanup.plan(config, Enumeration(listOf(here), complete = true)) { it.packageName == "disabled" }
        val orphans = (plan as CleanupPlan.Offer).orphans
        assertEquals(setOf("gone/Gone"), orphans.keys)
        assertEquals(listOf(1, 1), listOf(orphans.favorites, orphans.hidden))
    }

    @Test fun `a key no app can have is gone without asking the system`() {
        val junk = listOf("com.foo/Bar ", "com.foo", "/x", "", "#10", "com.foo/X#", "com.foo/X#+10",
            "com.foo/X#010", "com.foo/X#-1", "com.foo/X#10#11", "com.foo/a b", "1com/X")
        val asked = mutableListOf<KeyParts>()
        val plan = Cleanup.plan(VersoConfig(hidden = LinkedHashSet(junk)), Enumeration(emptyList(), complete = true)) {
            asked += it; true
        }
        assertEquals(junk.toSet(), (plan as CleanupPlan.Offer).orphans.keys)
        assertEquals(emptyList<KeyParts>(), asked)
    }

    @Test fun `entries of a profile that answered empty are kept, other profiles still checked`() {
        val cfg = VersoConfig(favorites = listOf("a.b/C#10", "a.b/C#11"))
        val plan = Cleanup.plan(cfg, Enumeration(emptyList(), complete = true, emptyProfiles = setOf(10L))) { false }
        assertEquals(setOf("a.b/C#11"), (plan as CleanupPlan.Offer).orphans.keys)
    }

    @Test fun `parseKey takes apart exactly what keyOf builds`() {
        assertEquals(KeyParts("com.foo", "com.foo.Main", null), AppEntry.parseKey("com.foo/com.foo.Main"))
        assertEquals(KeyParts("com.foo", "com.foo.Main\$Inner", 10L), AppEntry.parseKey("com.foo/com.foo.Main\$Inner#10"))
        assertEquals(KeyParts("android", "X", 0L), AppEntry.parseKey("android/X#0"))
        for (bad in listOf("com.foo/X#010", "com.foo/X#+1", "com.foo/X#-1", "com.foo/X ", "com.foo/", "/X", "com.foo"))
            assertEquals(bad, null, AppEntry.parseKey(bad))
    }

    @Test fun `an activity missing from a loaded package is gone, a package without loaded code stays`() {
        assertEquals(false, Cleanup.activityStillThere("a.Old", listOf("a.New")))
        assertEquals(true, Cleanup.activityStillThere("a.Main", listOf("a.Main", "a.Other")))
        assertEquals(true, Cleanup.activityStillThere("a.Main", null))         // unmounted, data kept
        assertEquals(true, Cleanup.activityStillThere("a.Main", emptyList()))
    }

    @Test fun `parseKey leaves odd but real class names to the system`() {
        assertEquals(KeyParts("com.foo", "com.foo.\u00e9-1", null), AppEntry.parseKey("com.foo/com.foo.\u00e9-1"))
    }

    @Test fun `nothing gone means nothing to do`() {
        val plan = Cleanup.plan(config, Enumeration(listOf(here), complete = true)) { true }
        assertEquals(CleanupPlan.NothingToDo, plan)
    }

    /** Own apps carry no serial, so such a key (from another device's config) matches no app,
     *  though the system would call it "still there". */

    @Test fun `a key with the launcher's own profile serial is gone`() {
        val ownKey = "here/here.Main#0"
        val workKey = "work/work.Main#10"
        val cfg = VersoConfig(favorites = listOf(here.key, ownKey, workKey))
        val plan = Cleanup.plan(cfg, Enumeration(listOf(here), complete = true), ownSerial = 0L) { true }
        assertEquals(setOf(ownKey), (plan as CleanupPlan.Offer).orphans.keys)
    }
}
