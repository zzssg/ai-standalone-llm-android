package org.zzssg.llmchatapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationStatsTest {

    @Test
    fun `tokens per second is derived from the elapsed time`() {
        assertEquals(10.0, GenerationStats(20, 2_000).tokensPerSecond, 0.001)
    }

    @Test
    fun `a zero duration does not divide by zero`() {
        assertEquals(0.0, GenerationStats(5, 0).tokensPerSecond, 0.001)
    }

    @Test
    fun `sub-second replies are reported in milliseconds`() {
        assertEquals("840 ms", GenerationStats(3, 840).formattedDuration)
        assertEquals("999 ms", GenerationStats(3, 999).formattedDuration)
    }

    @Test
    fun `replies under a minute are reported in seconds`() {
        assertEquals("1.0 s", GenerationStats(3, 1_000).formattedDuration)
        assertEquals("32.4 s", GenerationStats(107, 32_400).formattedDuration)
        assertEquals("59.9 s", GenerationStats(3, 59_900).formattedDuration)
    }

    /** A 4B model on a phone routinely crosses the minute mark. */
    @Test
    fun `longer replies are reported in minutes and seconds`() {
        assertEquals("1:00 min", GenerationStats(3, 60_000).formattedDuration)
        assertEquals("2:05 min", GenerationStats(3, 125_000).formattedDuration)
        assertEquals("10:09 min", GenerationStats(3, 609_400).formattedDuration)
    }
}
