package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Only [BackupStore.readCapped], the OOM guard of a restore read; the rest is MediaStore glue. */
class BackupStoreTest {

    @Test fun `reads content under the cap`() {
        assertEquals(
            "hello",
            BackupStore.readCapped(ByteArrayInputStream("hello".toByteArray()), maxBytes = 16),
        )
    }

    @Test fun `content exactly at the cap is accepted (inclusive)`() {
        val exact = "x".repeat(8)
        assertEquals(exact, BackupStore.readCapped(ByteArrayInputStream(exact.toByteArray()), maxBytes = 8))
    }

    @Test fun `content one byte over the cap is refused, not truncated`() {
        assertNull(BackupStore.readCapped(ByteArrayInputStream("x".repeat(9).toByteArray()), maxBytes = 8))
    }

    @Test fun `an empty stream reads as empty, never null`() {
        assertEquals("", BackupStore.readCapped(ByteArrayInputStream(ByteArray(0)), maxBytes = 8))
    }

    @Test fun `a chunked over-cap stream is still refused`() {
        // A real ContentProvider can hand out bytes a few at a time.

        val chunky = object : InputStream() {
            private var remaining = 9
            override fun read(): Int = if (remaining-- > 0) 'x'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(3, len, remaining)
                for (i in 0 until n) b[off + i] = 'x'.code.toByte()
                remaining -= n
                return n
            }
        }
        assertNull(BackupStore.readCapped(chunky, maxBytes = 8))
    }
}
