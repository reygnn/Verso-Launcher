package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A config is a value: equal means "the same file content". RestoreSteps stops a restore that
 * would change nothing on it (hard rule 7), so a false "equal" would skip a real restore and a
 * false "different" would replace the undo point with the very config it protects.
 */
class VersoConfigTest {

    private val a = VersoConfig(
        favorites = listOf("a/A", "b/B"),
        hidden = linkedSetOf("x/X", "y/Y"),
        names = linkedMapOf("a/A" to "Alpha", "b/B" to "Bravo"),
        tags = linkedMapOf("a/A" to listOf("work", "chat")),
    )

    @Test fun `the order of hidden, names and tags does not count, and the hash agrees`() {
        val reordered = VersoConfig(
            favorites = listOf("a/A", "b/B"),
            hidden = linkedSetOf("y/Y", "x/X"),
            names = linkedMapOf("b/B" to "Bravo", "a/A" to "Alpha"),
            tags = linkedMapOf("a/A" to listOf("work", "chat")),
        )
        assertEquals(a, reordered)
        assertEquals(a.hashCode(), reordered.hashCode())
    }

    /** The favorites' order is the home page's rank. */
    @Test fun `the favorites' order counts`() {
        assertNotEquals(a, a.copy(favorites = listOf("b/B", "a/A")))
    }

    @Test fun `the order inside one app's tags counts`() {
        assertNotEquals(a, a.copy(tags = linkedMapOf("a/A" to listOf("chat", "work"))))
    }

    @Test fun `every field counts`() {
        assertNotEquals(a, a.copy(hidden = linkedSetOf("x/X")))
        assertNotEquals(a, a.copy(names = linkedMapOf("a/A" to "Alpha")))
        assertNotEquals(a, a.copy(tags = linkedMapOf("a/A" to listOf("home"))))
        assertNotEquals(a, a.copy(byUsage = true))
    }
}
