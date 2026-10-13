package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** [Cleanup.plan]: what "~cleanup" may offer — only after a complete enumeration, and never an
 *  app still on the device in some form (hard rule 14). */
class CleanupTest {

    private val here = AppEntry.of("Here", "here", "here.Main", "Here")
    private val config = VersoConfig(
        favorites = linkedSetOf(here.key, "gone/Gone", "disabled/Main"),
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
        val plan = Cleanup.plan(config, Enumeration(listOf(here), complete = true)) { it == "disabled/Main" }
        val orphans = (plan as CleanupPlan.Offer).orphans
        assertEquals(setOf("gone/Gone"), orphans.keys)
        assertEquals(listOf(1, 1), listOf(orphans.favorites, orphans.hidden))
    }

    @Test fun `nothing gone means nothing to do`() {
        val plan = Cleanup.plan(config, Enumeration(listOf(here), complete = true)) { true }
        assertEquals(CleanupPlan.NothingToDo, plan)
    }
}
