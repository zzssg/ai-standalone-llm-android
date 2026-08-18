package org.zzssg.llmchatapp.data

import android.content.Context
import androidx.core.content.edit
import org.zzssg.llmchatapp.llm.SamplingConfig

/** Everything the user can tune, plus which model to reopen on next launch. */
data class AppSettings(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val sampling: SamplingConfig = SamplingConfig(),
    val contextSize: Int = 4096,
    /** 0 = let the engine pick from the big-core count. */
    val threads: Int = 0,
    val lastModelId: String? = null,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant. Answer clearly and concisely."
    }
}

/**
 * Small SharedPreferences-backed store. Deliberately not DataStore: the settings
 * are read once at startup and written on explicit user action, so the extra
 * dependency would not buy anything.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null) ?: defaults.systemPrompt,
            sampling = SamplingConfig(
                temperature = prefs.getFloat(KEY_TEMPERATURE, defaults.sampling.temperature),
                topP = prefs.getFloat(KEY_TOP_P, defaults.sampling.topP),
                topK = prefs.getInt(KEY_TOP_K, defaults.sampling.topK),
                minP = prefs.getFloat(KEY_MIN_P, defaults.sampling.minP),
                repeatPenalty = prefs.getFloat(KEY_REPEAT_PENALTY, defaults.sampling.repeatPenalty),
                maxTokens = prefs.getInt(KEY_MAX_TOKENS, defaults.sampling.maxTokens),
            ),
            contextSize = prefs.getInt(KEY_CONTEXT_SIZE, defaults.contextSize),
            threads = prefs.getInt(KEY_THREADS, defaults.threads),
            lastModelId = prefs.getString(KEY_LAST_MODEL, null),
        )
    }

    fun save(settings: AppSettings) = prefs.edit {
        putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
        putFloat(KEY_TEMPERATURE, settings.sampling.temperature)
        putFloat(KEY_TOP_P, settings.sampling.topP)
        putInt(KEY_TOP_K, settings.sampling.topK)
        putFloat(KEY_MIN_P, settings.sampling.minP)
        putFloat(KEY_REPEAT_PENALTY, settings.sampling.repeatPenalty)
        putInt(KEY_MAX_TOKENS, settings.sampling.maxTokens)
        putInt(KEY_CONTEXT_SIZE, settings.contextSize)
        putInt(KEY_THREADS, settings.threads)
        putString(KEY_LAST_MODEL, settings.lastModelId)
    }

    private companion object {
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_TOP_K = "top_k"
        const val KEY_MIN_P = "min_p"
        const val KEY_REPEAT_PENALTY = "repeat_penalty"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_CONTEXT_SIZE = "context_size"
        const val KEY_THREADS = "threads"
        const val KEY_LAST_MODEL = "last_model"
    }
}
