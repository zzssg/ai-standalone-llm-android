package org.zzssg.llmchatapp.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatBytesTest {

    private fun format(bytes: Long) = formatBytes(bytes, Locale.US)

    @Test
    fun `small sizes stay in bytes`() {
        assertEquals("0 B", format(0))
        assertEquals("512 B", format(512))
        assertEquals("1023 B", format(1023))
    }

    @Test
    fun `kilobytes and megabytes round to whole units`() {
        assertEquals("1 KB", format(1024))
        assertEquals("1 MB", format(1024L * 1024))
        assertEquals("640 MB", format(640L * 1024 * 1024))
    }

    @Test
    fun `gigabytes keep one decimal so model sizes stay distinguishable`() {
        assertEquals("1.0 GB", format(1L shl 30))
        assertEquals("4.1 GB", format(4_400_000_000L))
    }

    @Test
    fun `unit boundaries do not overlap`() {
        assertEquals("1023 KB", format((1L shl 20) - 1024))
        assertEquals("1 MB", format(1L shl 20))
        assertEquals("1023 MB", format((1L shl 30) - (1L shl 20)))
        assertEquals("1.0 GB", format(1L shl 30))
    }
}
