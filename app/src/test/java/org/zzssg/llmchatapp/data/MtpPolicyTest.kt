package org.zzssg.llmchatapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtpPolicyTest {

    private fun gb(n: Double): Long = (n * 1024 * 1024 * 1024).toLong()
    private fun billions(n: Double): Long = (n * 1e9).toLong()

    private fun decide(ramGb: Double, paramsB: Double, mode: MtpMode = MtpMode.AUTO) =
        MtpPolicy.decide(mode, gb(ramGb), billions(paramsB), modelHasMtpBlock = true)

    @Test
    fun `a model without a draft head can never use speculation`() {
        val d = MtpPolicy.decide(MtpMode.ON, gb(16.0), billions(1.0), modelHasMtpBlock = false)

        assertFalse(d.enabled)
        assertEquals(0, d.draft)
        assertTrue("the reason should name the cause", d.reason.contains("no draft head"))
    }

    @Test
    fun `off always means off`() {
        assertFalse(decide(ramGb = 16.0, paramsB = 1.0, mode = MtpMode.OFF).enabled)
    }

    /**
     * Auto declines even where memory allows it.
     *
     * Measurement on the reference device showed a five-token verification costs
     * 2.3x a single-token decode, so break-even needs ~36% acceptance and the
     * measured rate is nowhere near it. Memory was never the binding constraint.
     */
    @Test
    fun `auto declines because batching is not free on this class of device`() {
        val d = decide(ramGb = 12.0, paramsB = 4.0)

        assertFalse("speculation does not pay off here", d.enabled)
        assertTrue("the reason should name the cause", d.reason.contains("not free"))
    }

    /**
     * The same model on 8 GB. Measured overhead is ~700 MB of non-reclaimable
     * state, and at that point the weights start being evicted -- speculation
     * would cost more than it saves.
     */
    @Test
    fun `memory is checked before anything else`() {
        val d = decide(ramGb = 8.0, paramsB = 4.0)

        assertFalse("8 GB is too tight for a 4 B model plus a draft context", d.enabled)
        assertEquals(0, d.draft)
        assertTrue("the reason should explain the trade", d.reason.contains("out of memory"))
    }

    /** Memory is still checked first, and still reported distinctly. */
    @Test
    fun `a model too large for memory is refused for that reason`() {
        val d = decide(ramGb = 8.0, paramsB = 8.0)

        assertFalse(d.enabled)
        assertTrue("memory should be named, not batching", d.reason.contains("out of memory"))
    }

    @Test
    fun `auto declines when there is no budget at all`() {
        assertFalse(decide(ramGb = 4.0, paramsB = 1.0).enabled)
    }

    @Test
    fun `the limit rises with RAM`() {
        val small = MtpPolicy.maxParamsBillions(gb(8.0))
        val large = MtpPolicy.maxParamsBillions(gb(16.0))

        assertTrue("more RAM must allow more parameters, got $small then $large", large > small)
        assertEquals("a 4 GB phone has no budget", 0.0, MtpPolicy.maxParamsBillions(gb(4.0)), 0.001)
    }

    @Test
    fun `forcing it on for an oversized model warns rather than silently agreeing`() {
        val d = decide(ramGb = 8.0, paramsB = 8.0, mode = MtpMode.ON)

        assertTrue("the user's choice is respected", d.enabled)
        assertTrue("but the risk is stated", d.reason.contains("too large"))
    }

    @Test
    fun `auto waits instead of guessing when the size is unknown`() {
        val d = MtpPolicy.decide(MtpMode.AUTO, gb(12.0), modelParams = 0, modelHasMtpBlock = true)

        assertFalse(d.enabled)
        assertTrue(d.reason.contains("Waiting"))
    }
}
