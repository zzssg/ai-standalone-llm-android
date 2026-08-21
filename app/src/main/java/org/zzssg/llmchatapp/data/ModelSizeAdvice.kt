package org.zzssg.llmchatapp.data

/**
 * How large a model this phone can actually run.
 *
 * The guidance in the app used to be a flat "1-3 B models run on most phones",
 * which is useless in both directions: it undersells a 12 GB device and oversells
 * a 4 GB one. The phone knows its own memory, so the advice can be a number.
 *
 * Deliberately conservative. Being told a file will fit and then watching the
 * import finish and the load fail is a far worse experience than being pointed
 * at a slightly smaller file that works, and a model needs room beyond its own
 * weights: the KV cache grows with the conversation, and Android will kill the
 * app before it lets it squeeze the system.
 */
object ModelSizeAdvice {

    /**
     * The largest model file worth downloading, in bytes, or 0 when there is not
     * enough memory for any of it.
     */
    fun recommendedMaxBytes(totalRamBytes: Long): Long {
        if (totalRamBytes <= 0) return 0
        val budgetGb = totalRamBytes / GB - OS_RESERVE_GB - APP_OVERHEAD_GB
        if (budgetGb <= 0) return 0
        return (budgetGb / CACHE_HEADROOM * GB).toLong()
    }

    /**
     * A parameter count to aim for at the usual quantisation, as a plain phrase.
     *
     * Sizes are named the way the files are -- "4B", "8B" -- because that is what
     * the reader will be scanning for in a list of downloads.
     */
    fun recommendedParameterRange(totalRamBytes: Long): String {
        val gb = recommendedMaxBytes(totalRamBytes) / GB
        // Thresholds are the actual file sizes at Q4_K_M, near enough: 1B is
        // 0.8 GB, 3B is 2.0, 4B is 2.6, 8B is 4.9, 12B is 7.3. Naming a size the
        // stated byte figure cannot hold is the one thing this must not do.
        return when {
            gb <= 0 -> "none"
            gb < 0.8 -> "under 1B"
            gb < 2.0 -> "1B to 2B"
            gb < 4.9 -> "3B to 4B"
            gb < 7.3 -> "7B to 8B"
            else -> "12B and up"
        }
    }

    /** What the system keeps for itself. Measured, not guessed; see MtpPolicy. */
    private const val OS_RESERVE_GB = 3.5

    /** The app's own footprint outside the model weights. */
    private const val APP_OVERHEAD_GB = 0.6

    /** Room for the KV cache, which grows with the length of the conversation. */
    private const val CACHE_HEADROOM = 1.25

    private const val GB = 1024.0 * 1024.0 * 1024.0
}
