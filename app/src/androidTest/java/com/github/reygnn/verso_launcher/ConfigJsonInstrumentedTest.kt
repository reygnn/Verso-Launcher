package com.github.reygnn.verso_launcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The desktop org.json of the JVM tests refuses deep nesting with a JSONException; Android's
 * overflows the stack instead, a StackOverflowError, which is not an Exception.
 */

@RunWith(AndroidJUnit4::class)
class ConfigJsonInstrumentedTest {

    private val deep = "{\"favorites\":" + "[".repeat(DEPTH) + "]".repeat(DEPTH) + "}"

    @Test fun anAbsurdlyNestedRestoreFileIsRefusedNotACrash() {
        assertNull(ConfigJson.parseForeign(deep))
    }

    @Test fun anAbsurdlyNestedOwnFileIsRefusedNotACrash() {
        assertNull(ConfigJson.parse(deep))
    }

    @Test fun aRealConfigStillParsesOnAndroidsOrgJson() {
        val c = ConfigJson.parseForeign("""{"favorites":["a/A","b/B"],"hidden":["c/C"]}""")
        assertEquals(listOf("a/A", "b/B"), c!!.favorites.toList())
        assertEquals(setOf("c/C"), c.hidden)
    }

    private companion object {
        /** 200 KB of brackets — well inside the 1 MiB restore cap, far past the stack. */
        const val DEPTH = 100_000
    }
}
