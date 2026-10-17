package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class GuardTest {

    /** Kotlin compiles a call that throws IOException without a try: not a RuntimeException. */
    @Test fun `a checked exception is logged, not propagated`() {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val boom = IOException("boom")
        Guard.wrap("io task", { msg, e -> logged += msg to e }) { throw boom }.run()
        assertEquals(listOf("io task failed" to boom), logged)
    }

    @Test fun `an Error propagates`() {
        assertThrows(OutOfMemoryError::class.java) {
            Guard.wrap("io task", { _, _ -> }) { throw OutOfMemoryError() }.run()
        }
    }
}
