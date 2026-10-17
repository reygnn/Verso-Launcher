package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * snapshot() hands a copy to the IO thread while main keeps mutating the live config, so no
 * container may be shared. The trap is the tag map: copying the outer map leaves the inner lists aliased.
 */
class VersoConfigTest {

    private fun populated() = VersoConfig(
        hidden = linkedSetOf("h/1", "h/2"),
        favorites = linkedSetOf("f/1", "f/2", "f/3"),
        names = linkedMapOf("f/1" to "Custom"),
        tags = linkedMapOf(
            "f/1" to mutableListOf("work", "chat"),
            "f/2" to mutableListOf("media"),
        ),
    )

    // ---- the deep-copy invariant ---------------------------------------------

    /** What a shallow LinkedHashMap(tags) copy would break. */
    @Test
    fun `mutating a tag list on the original does not reach the snapshot`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        cfg.tags["f/1"]!! += "added-after-snapshot"
        cfg.tags["f/2"]!!.clear()

        assertEquals(listOf("work", "chat"), snap.tags["f/1"])
        assertEquals(listOf("media"), snap.tags["f/2"])
    }

    @Test
    fun `mutating a tag list on the snapshot does not reach the original`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        snap.tags["f/1"]!! += "only-in-snapshot"

        assertEquals(listOf("work", "chat"), cfg.tags["f/1"])
    }

    /** Identity, not equality, decides whether two threads can collide on a list. */
    @Test
    fun `each tag list is a distinct object`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        for (key in cfg.tags.keys) {
            assertNotSame("tag list for $key is shared", cfg.tags[key], snap.tags[key])
        }
    }

    @Test
    fun `the four containers are distinct objects`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        assertNotSame(cfg.hidden, snap.hidden)
        assertNotSame(cfg.favorites, snap.favorites)
        assertNotSame(cfg.names, snap.names)
        assertNotSame(cfg.tags, snap.tags)
    }

    @Test
    fun `adding to the original's hidden, favorites or names does not reach the snapshot`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        cfg.hidden += "h/3"
        cfg.favorites += "f/4"
        cfg.names["f/2"] = "Late"
        cfg.tags["f/3"] = mutableListOf("new-key")

        assertEquals(setOf("h/1", "h/2"), snap.hidden)
        assertEquals(setOf("f/1", "f/2", "f/3"), snap.favorites)
        assertEquals(mapOf("f/1" to "Custom"), snap.names)
        assertTrue("f/3" !in snap.tags)
    }

    // ---- what the copy must preserve -----------------------------------------

    /** Favorites order is the home-screen rank. */
    @Test
    fun `favorites keep their order across a snapshot`() {
        val cfg = VersoConfig(favorites = linkedSetOf("z/1", "a/1", "m/1"))

        assertEquals(listOf("z/1", "a/1", "m/1"), cfg.snapshot().favorites.toList())
    }

    @Test
    fun `a snapshot carries every value across`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        assertEquals(cfg.hidden, snap.hidden)
        assertEquals(cfg.favorites, snap.favorites)
        assertEquals(cfg.names, snap.names)
        assertEquals(cfg.tags, snap.tags)
    }

    @Test
    fun `an empty config snapshots to an empty config`() {
        val snap = VersoConfig().snapshot()

        assertTrue(snap.hidden.isEmpty())
        assertTrue(snap.favorites.isEmpty())
        assertTrue(snap.names.isEmpty())
        assertTrue(snap.tags.isEmpty())
    }

    @Test
    fun `sameContent ignores map and set order but not the favorites order`() {
        val a = VersoConfig(
            favorites = linkedSetOf("a/A", "b/B"),
            hidden = linkedSetOf("x/X", "y/Y"),
            names = linkedMapOf("a/A" to "Alpha", "b/B" to "Bravo"),
            tags = linkedMapOf("a/A" to mutableListOf("work")),
        )
        val reordered = VersoConfig(
            favorites = linkedSetOf("a/A", "b/B"),
            hidden = linkedSetOf("y/Y", "x/X"),
            names = linkedMapOf("b/B" to "Bravo", "a/A" to "Alpha"),
            tags = linkedMapOf("a/A" to mutableListOf("work")),
        )
        assertTrue(a.sameContent(reordered))
        assertFalse(a.sameContent(VersoConfig(favorites = linkedSetOf("b/B", "a/A"), hidden = a.hidden, names = a.names, tags = a.tags)))
        assertFalse(a.sameContent(VersoConfig(favorites = a.favorites, hidden = linkedSetOf("x/X"), names = a.names, tags = a.tags)))
        assertFalse(a.sameContent(VersoConfig(favorites = a.favorites, hidden = a.hidden, names = linkedMapOf("a/A" to "Alpha"), tags = a.tags)))
        assertFalse(a.sameContent(VersoConfig(favorites = a.favorites, hidden = a.hidden, names = a.names, tags = linkedMapOf("a/A" to mutableListOf("home")))))
    }
}
