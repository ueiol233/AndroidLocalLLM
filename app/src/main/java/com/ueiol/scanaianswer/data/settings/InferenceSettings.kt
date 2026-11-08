package com.ueiol.scanaianswer.data.settings

import android.content.Context

/**
 * 推理参数配置（用户在“推理设置”中可自定义）。
 * @property systemPrompt 用户自定义系统提示词。
 * @property temperature 采样温度。
 * @property maxTokens 单次最大生成 token 数（N_PREDICT）。
 * @property contextLen 上下文长度（N_CTX）。
 * @property threads 推理线程数。
 * @property thinkingBudget 思考 token 预算；-1 = 不限制，>=0 时限制模型思考的最大 token 数。
 * @property thinkingStartTag 思考开启标记（需与模型实际输出一致）。
 * @property thinkingEndTag 思考结束标记（需与模型实际输出一致）。
 */
data class InferenceSettings(
    val systemPrompt: String,
    val temperature: Float,
    val maxTokens: Int,
    val contextLen: Int,
    val threads: Int,
    val topP: Float,
    val topK: Int,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val stopStrings: List<String>,
    val thinkingBudget: Int,
    val thinkingStartTag: String,
    val thinkingEndTag: String
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个助手。你可以帮助用户解答问题。回答要友好、实用、有条理，尽量给出具体可操作的建议。"

        const val THINKING_INSTRUCTION =
            "\n\n请先逐步思考用户的需求，理清问题的关键点，再给出全面、实用的回答。"

        const val NO_THINKING_INSTRUCTION =
            "\n\n请直接给出清晰、实用的回答和建议。"

        /** 输出长度上限：单次最大生成 token 数约束为 2048。 */
        const val MAX_OUTPUT_TOKENS = 2048

        /** 思考预算：自动取输出长度（maxTokens）的 25%；-1 表示不限制。 */
        fun defaultThinkingBudget(maxTokens: Int): Int = maxTokens / 4

        val DEFAULT = InferenceSettings(
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            temperature = 0.7f,
            maxTokens = MAX_OUTPUT_TOKENS,
            contextLen = 2048,
            threads = 4,
            topP = 0.8f,
            topK = 20,
            presencePenalty = 0.0f,
            frequencyPenalty = 0.0f,
            stopStrings = emptyList(),
            thinkingBudget = defaultThinkingBudget(MAX_OUTPUT_TOKENS),
            thinkingStartTag = "thinking",
            thinkingEndTag = "response"
        )
    }

    /** 根据思考开关返回对应的系统提示词。 */
    fun effectiveSystemPrompt(thinkingEnabled: Boolean): String {
        return systemPrompt + if (thinkingEnabled) THINKING_INSTRUCTION else NO_THINKING_INSTRUCTION
    }
}

/** 推理设置的持久化存储（SharedPreferences）。 */
object InferencePrefs {
    private const val PREFS_NAME = "inference_prefs"
    private const val KEY_VERSION = "prefs_version"
    private const val CURRENT_VERSION = 4  // 采样参数默认值更新(温度0.7/top_p0.8/top_k20)后递增，使旧设置自动带上新默认值

    private const val KEY_PROMPT = "system_prompt"
    private const val KEY_TEMP = "temperature"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_CTX = "context_len"
    private const val KEY_THREADS = "threads"
    private const val KEY_TOP_P = "top_p"
    private const val KEY_TOP_K = "top_k"
    private const val KEY_PRESENCE_PENALTY = "presence_penalty"
    private const val KEY_FREQ_PENALTY = "frequency_penalty"
    private const val KEY_STOP_STRINGS = "stop_strings"
    private const val KEY_THINKING_BUDGET = "thinking_budget"
    private const val KEY_THINKING_START_TAG = "thinking_start_tag"
    private const val KEY_THINKING_END_TAG = "thinking_end_tag"

    fun load(context: Context): InferenceSettings {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = p.getInt(KEY_VERSION, 0)
        // 版本不匹配时自动重置为新的默认值（确保新增采样参数等升级生效）
        if (storedVersion != CURRENT_VERSION) {
            p.edit().clear().putInt(KEY_VERSION, CURRENT_VERSION).apply()
            return InferenceSettings.DEFAULT
        }
        val d = InferenceSettings.DEFAULT
        return InferenceSettings(
            systemPrompt = p.getString(KEY_PROMPT, d.systemPrompt) ?: d.systemPrompt,
            temperature = p.getFloat(KEY_TEMP, d.temperature),
            maxTokens = p.getInt(KEY_MAX_TOKENS, d.maxTokens),
            contextLen = p.getInt(KEY_CTX, d.contextLen),
            threads = p.getInt(KEY_THREADS, d.threads),
            topP = p.getString(KEY_TOP_P, d.topP.toString())?.toFloatOrNull() ?: d.topP,
            topK = p.getString(KEY_TOP_K, d.topK.toString())?.toIntOrNull() ?: d.topK,
            presencePenalty = p.getFloat(KEY_PRESENCE_PENALTY, d.presencePenalty),
            frequencyPenalty = p.getFloat(KEY_FREQ_PENALTY, d.frequencyPenalty),
            stopStrings = p.getStringSet(KEY_STOP_STRINGS, emptySet())
                ?.toList()?.filter { it.isNotBlank() } ?: emptyList(),
            thinkingBudget = p.getInt(KEY_THINKING_BUDGET, d.thinkingBudget),
            thinkingStartTag = p.getString(KEY_THINKING_START_TAG, d.thinkingStartTag)
                ?: d.thinkingStartTag,
            thinkingEndTag = p.getString(KEY_THINKING_END_TAG, d.thinkingEndTag)
                ?: d.thinkingEndTag
        )
    }

    fun save(context: Context, cfg: InferenceSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putString(KEY_PROMPT, cfg.systemPrompt)
            .putFloat(KEY_TEMP, cfg.temperature)
            .putInt(KEY_MAX_TOKENS, cfg.maxTokens)
            .putInt(KEY_CTX, cfg.contextLen)
            .putInt(KEY_THREADS, cfg.threads)
            .putString(KEY_TOP_P, cfg.topP.toString())
            .putString(KEY_TOP_K, cfg.topK.toString())
            .putFloat(KEY_PRESENCE_PENALTY, cfg.presencePenalty)
            .putFloat(KEY_FREQ_PENALTY, cfg.frequencyPenalty)
            .putStringSet(KEY_STOP_STRINGS, cfg.stopStrings.filter { it.isNotBlank() }.toSet())
            .putInt(KEY_THINKING_BUDGET, cfg.thinkingBudget)
            .putString(KEY_THINKING_START_TAG, cfg.thinkingStartTag)
            .putString(KEY_THINKING_END_TAG, cfg.thinkingEndTag)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}