package org.zzssg.llmchatapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advice the first screen gives before the user has downloaded anything.
 *
 * It replaces a flat "1-3 B models run on most phones", which was wrong in both
 * directions -- it undersold a 12 GB device and oversold a 4 GB one. Being told
 * a file will fit and then watching the import finish and the load fail is the
 * outcome these numbers exist to prevent, so they lean small.
 */
class ModelSizeAdviceTest {

    private fun gb(value: Double): Long = (value * 1024 * 1024 * 1024).toLong()
    private fun asGb(bytes: Long): Double = bytes / (1024.0 * 1024 * 1024)

    @Test
    fun `a 12 GB phone is told a figure a 7B model fits inside`() {
        val max = asGb(ModelSizeAdvice.recommendedMaxBytes(gb(12.0)))

        // A 7B at Q4_K_M is about 4.4 GB, an 8B about 4.9 GB.
        assertTrue("should clear a 7B at Q4_K_M, got $max GB", max > 5.0)
        assertTrue("should not promise room for an unquantised model, got $max GB", max < 8.0)
    }

    @Test
    fun `a 6 GB phone is kept to small models`() {
        val max = asGb(ModelSizeAdvice.recommendedMaxBytes(gb(6.0)))

        assertTrue("should still allow a 1B at Q4_K_M, got $max GB", max > 0.9)
        assertTrue("should not reach a 4B, got $max GB", max < 2.6)
    }

    /**
     * The phrase and the byte figure have to agree. Naming a size the stated
     * limit cannot hold sends the reader to a download that will not load,
     * which is the exact failure this advice exists to avoid.
     */
    @Test
    fun `the named size always fits inside the stated limit`() {
        val q4BytesPerBillion = 0.62

        listOf(6.0, 8.0, 12.0, 16.0, 24.0).forEach { ram ->
            val maxGb = asGb(ModelSizeAdvice.recommendedMaxBytes(gb(ram)))
            val phrase = ModelSizeAdvice.recommendedParameterRange(gb(ram))
            val smallest = Regex("""\d+""").find(phrase)!!.value.toInt()

            assertTrue(
                "$ram GB advises \"$phrase\" but only allows %.1f GB".format(maxGb),
                smallest * q4BytesPerBillion <= maxGb,
            )
        }
    }

    /** Below the point where the system's own reserve eats everything. */
    @Test
    fun `a phone with too little memory is told so rather than given a number`() {
        assertEquals(0L, ModelSizeAdvice.recommendedMaxBytes(gb(3.0)))
        assertEquals(0L, ModelSizeAdvice.recommendedMaxBytes(gb(4.0)))
    }

    @Test
    fun `an unknown memory size yields nothing to promise`() {
        assertEquals(0L, ModelSizeAdvice.recommendedMaxBytes(0))
        assertEquals(0L, ModelSizeAdvice.recommendedMaxBytes(-1))
    }

    @Test
    fun `more memory never advises a smaller file`() {
        val sizes = listOf(4.0, 6.0, 8.0, 12.0, 16.0, 24.0).map {
            ModelSizeAdvice.recommendedMaxBytes(gb(it))
        }

        assertEquals(sizes.sorted(), sizes)
    }

    @Test
    fun `the parameter range is phrased the way the files are named`() {
        assertEquals("none", ModelSizeAdvice.recommendedParameterRange(gb(3.0)))
        assertEquals("1B to 2B", ModelSizeAdvice.recommendedParameterRange(gb(6.0)))
        assertEquals("7B to 8B", ModelSizeAdvice.recommendedParameterRange(gb(12.0)))
    }
}
