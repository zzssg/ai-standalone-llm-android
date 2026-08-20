package org.zzssg.llmchatapp.llm

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * First increment of multi-token prediction: does the draft context come up at
 * all, and what does it cost?
 *
 * Worth its own test before the draft/verify loop exists, because everything
 * after this depends on the answer, and the answer can only come from a real
 * model on a real device -- the emulator can at least prove the wiring.
 *
 *   adb push <qwen3.5-class model>.gguf /data/local/tmp/extra-model.gguf
 */
class MtpSetupTest {

    private val engine = LlamaEngine()

    @After
    fun tearDown() = runBlocking { engine.unload() }

    @Test
    fun draftContextComesUpForAModelWithAnMtpBlock(): Unit = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val before = memoryInUseBytes()
        val loaded = engine.load(model, contextSize = 512, mtpDraft = DRAFT)
        val after = memoryInUseBytes()

        Log.i(
            TAG,
            "model=${loaded.description} mtpDraft=${loaded.mtpDraft} " +
                "rssDelta=${(after - before) / 1024 / 1024} MB",
        )

        assumeTrue("This model ships no MTP block", loaded.mtpDraft > 0)
        assertEquals("the requested draft depth should be in use", DRAFT, loaded.mtpDraft)
    }

    /** With MTP off nothing extra is created, and nothing about loading changes. */
    @Test
    fun draftContextIsAbsentWhenNotRequested(): Unit = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val loaded = engine.load(model, contextSize = 512, mtpDraft = 0)

        assertEquals("MTP must stay off when not asked for", 0, loaded.mtpDraft)
        assertTrue("the model itself should still be usable", loaded.contextSize > 0)
    }

    /** What the extra context actually costs, so the trade-off is a number. */
    @Test
    fun reportsTheMemoryCostOfEnablingMtp(): Unit = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        engine.load(model, contextSize = 512, mtpDraft = 0)
        val plain = memoryInUseBytes()
        engine.unload()

        val withMtp = engine.load(model, contextSize = 512, mtpDraft = DRAFT)
        val speculative = memoryInUseBytes()

        assumeTrue("This model ships no MTP block", withMtp.mtpDraft > 0)
        Log.i(
            TAG,
            "memory: plain=${plain / 1024 / 1024} MB, mtp=${speculative / 1024 / 1024} MB, " +
                "delta=${(speculative - plain) / 1024 / 1024} MB",
        )
    }

    /**
     * Splits the cost of MTP into its two parts.
     *
     * The MTP block's weights are a fixed price; the rollback snapshots scale
     * with draft depth. Only the second is tunable, so knowing which dominates
     * decides whether a deeper draft is affordable.
     */
    @Test
    fun separatesFixedMtpCostFromPerSnapshotCost(): Unit = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val readings = mutableListOf<Pair<Int, Long>>()
        for (draft in listOf(0, 1, 2, 4)) {
            engine.unload()
            val loaded = engine.load(model, contextSize = 512, mtpDraft = draft)
            readings += draft to memoryInUseBytes()
            Log.i(TAG, "draft=$draft inUse=${memoryInUseBytes() / 1024 / 1024} MB mtp=${loaded.mtpDraft}")
        }

        val base = readings.first { it.first == 0 }.second
        readings.forEach { (draft, bytes) ->
            Log.i(TAG, "draft=$draft delta=${(bytes - base) / 1024 / 1024} MB")
        }
    }

    /**
     * Native allocations are mostly outside the Java heap, so Runtime figures are
     * useless here. Debug.getNativeHeapAllocatedSize covers the malloc arena;
     * mmap'd weights do not show up, which is what we want -- the interesting
     * number is the extra state, not the shared model file.
     */
    private fun memoryInUseBytes(): Long = android.os.Debug.getNativeHeapAllocatedSize()

    private companion object {
        const val MODEL_PATH = "/data/local/tmp/extra-model.gguf"
        const val DRAFT = 4
        const val TAG = "MtpSetupTest"
    }
}
