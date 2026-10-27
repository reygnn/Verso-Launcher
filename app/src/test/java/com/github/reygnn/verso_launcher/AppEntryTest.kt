package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The config key (hard rule 8): HomeCache reads its lines back through [AppEntry.parseKey]. */
class AppEntryTest {

    @Test fun `parseKey takes apart exactly what keyOf builds`() {
        assertEquals(KeyParts("com.foo", "com.foo.Main", null), AppEntry.parseKey("com.foo/com.foo.Main"))
        assertEquals(KeyParts("com.foo", "com.foo.Main\$Inner", 10L), AppEntry.parseKey("com.foo/com.foo.Main\$Inner#10"))
        assertEquals(KeyParts("android", "X", 0L), AppEntry.parseKey("android/X#0"))
        for (bad in listOf("com.foo/X#010", "com.foo/X#+1", "com.foo/X#-1", "com.foo/X ", "com.foo/", "/X", "com.foo")) {
            assertEquals(bad, null, AppEntry.parseKey(bad))
        }
    }

    @Test fun `parseKey leaves odd but real class names to the system`() {
        assertEquals(KeyParts("com.foo", "com.foo.é-1", null), AppEntry.parseKey("com.foo/com.foo.é-1"))
    }

    @Test fun `keyOf and parseKey round-trip both profiles`() {
        for (profile in listOf(null, 0L, 10L)) {
            val key = AppEntry.keyOf("com.foo", "com.foo.Main", profile)
            assertEquals(KeyParts("com.foo", "com.foo.Main", profile), AppEntry.parseKey(key))
        }
    }
}
