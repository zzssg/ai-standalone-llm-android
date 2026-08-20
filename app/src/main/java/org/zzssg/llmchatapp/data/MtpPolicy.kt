package org.zzssg.llmchatapp.data

/** What the user asked for. */
enum class MtpMode { AUTO, ON, OFF }

/** What the policy decided, and why -- the reason is shown in settings. */
data class MtpDecision(
    val enabled: Boolean,
    val draft: Int,
    val reason: String,
)

/**
 * Decides whether speculative decoding is worth enabling on this device.
 *
 * Speculative decoding trades memory for speed: the draft context costs roughly
 * 700 MB that Android cannot reclaim. That memory comes out of the page cache
 * holding the model's weights, and if the weights start being evicted they are
 * re-read from flash -- which costs far more than speculation saves. So the
 * decision is not "is it faster in principle" but "does the phone have room for
 * both".
 *
 * Two inputs settle it, and only two:
 *
 *  - **Device RAM**, because that is the budget.
 *  - **Model parameter count**, because that is what the weights cost. Using the
 *    parameter count rather than the file size keeps the rule stable across
 *    quantisations: a 4 B model is a 4 B model whether it is Q4 or Q8, and the
 *    page-cache pressure scales with it either way.
 */
object MtpPolicy {

    // Every constant below is a measurement, not a guess.

    /**
     * Android, the launcher, and whatever else the user has open. A modern phone
     * does not hand a single app anything close to its nominal RAM.
     */
    private const val OS_RESERVE_GB = 3.5

    /**
     * The app's own non-reclaimable state with speculation on, measured on device
     * at ctx=4096 with draft=2: 1463 MB against 788 MB without.
     */
    private const val APP_STATE_WITH_MTP_GB = 1.5

    /**
     * Page cache a quantised parameter occupies. Measured against the model this
     * was developed on: 2.78 GB of weights for 4 B parameters at Q4_K_M.
     */
    private const val BYTES_PER_PARAM = 0.7

    /**
     * The weights must not merely fit -- they must not be the first thing evicted
     * when anything else asks for memory. Without slack, speculation wins tokens
     * and loses them again re-reading weights from flash.
     */
    private const val CACHE_HEADROOM = 1.25

    /** Deeper drafts cost ~50 MB each, so depth is not the expensive decision. */
    const val DEFAULT_DRAFT = 4

    /**
     * @param mode what the user selected
     * @param totalRamBytes device RAM, from ActivityManager.MemoryInfo.totalMem
     * @param modelParams parameter count of the loaded model, 0 when unknown
     * @param modelHasMtpBlock false for models that ship no draft head
     */
    fun decide(
        mode: MtpMode,
        totalRamBytes: Long,
        modelParams: Long,
        modelHasMtpBlock: Boolean,
    ): MtpDecision {
        if (!modelHasMtpBlock) {
            return MtpDecision(false, 0, "This model has no draft head, so there is nothing to speculate with.")
        }

        if (mode == MtpMode.OFF) {
            return MtpDecision(false, 0, "Turned off.")
        }

        val ramGb = totalRamBytes / GB
        val budgetGb = ramGb - OS_RESERVE_GB - APP_STATE_WITH_MTP_GB
        val weightsGb = modelParams * BYTES_PER_PARAM / GB * CACHE_HEADROOM

        if (mode == MtpMode.ON) {
            val warning = if (weightsGb > budgetGb) {
                " This model may be too large for it on ${format(ramGb)} GB of RAM, " +
                    "which can make generation slower rather than faster."
            } else {
                ""
            }
            return MtpDecision(true, DEFAULT_DRAFT, "Turned on.$warning")
        }

        // AUTO
        if (modelParams <= 0) {
            return MtpDecision(false, 0, "Waiting until the model reports its size.")
        }

        return if (weightsGb <= budgetGb) {
            MtpDecision(
                enabled = true,
                draft = DEFAULT_DRAFT,
                reason = "On: a ${format(paramsB(modelParams))} B model fits comfortably in " +
                    "${format(ramGb)} GB of RAM.",
            )
        } else {
            MtpDecision(
                enabled = false,
                draft = 0,
                reason = "Off: a ${format(paramsB(modelParams))} B model leaves too little room on " +
                    "${format(ramGb)} GB of RAM. Speculation would push the weights out of memory " +
                    "and cost more than it saves.",
            )
        }
    }

    /** Largest model, in billions of parameters, that AUTO will enable MTP for. */
    fun maxParamsBillions(totalRamBytes: Long): Double {
        val budgetGb = totalRamBytes / GB - OS_RESERVE_GB - APP_STATE_WITH_MTP_GB
        if (budgetGb <= 0) return 0.0
        return budgetGb * GB / (BYTES_PER_PARAM * CACHE_HEADROOM) / 1e9
    }

    private fun paramsB(params: Long): Double = params / 1e9

    private fun format(value: Double): String =
        if (value >= 10 || value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }

    private const val GB = 1024.0 * 1024.0 * 1024.0
}
