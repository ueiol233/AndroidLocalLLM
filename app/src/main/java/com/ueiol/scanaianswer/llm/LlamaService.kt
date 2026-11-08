package com.ueiol.scanaianswer.llm

import android.content.Context
import com.ueiol.liteai.llm.LlamaBridge
import com.ueiol.scanaianswer.data.settings.InferenceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * llama.cpp 服务封装。
 * 直接加载 /sdcard/AIModel/ 目录下的 .gguf 模型并流式生成。
 *
 * 原生库为有状态的增量式对话：初始化时设置一次系统提示，
 * 之后每轮通过 processUserPrompt + generateNextToken 追加生成，
 * KV 缓存会被保留，实现多轮追问。
 */
class LlamaService {

    /**
     * 思考标记在模型模板里通常出现在“换行 + 空格”之后（如千问 "\n thinking"）。
     * reasoning budget 按包含前导空格的 token 逐个匹配，因此前端输入的标记统一补一个前导空格。
     */
    private fun String.normalizeThinkingTag(): String {
        val t = trim()
        return if (t.isEmpty() || t.first() == ' ') t else " $t"
    }

    private companion object {
        const val N_THREADS_MIN = 1
        const val N_THREADS_MAX = 16
        const val N_CTX_MIN = 512
        const val N_CTX_MAX = 8192
        const val TEMP_MIN = 0f
        const val TEMP_MAX = 2f
        const val N_PREDICT_MIN = 64
        const val N_PREDICT_MAX = 2048

        /** /think 前缀：强制思考。 */
        const val FORCE_THINK_PROMPT =
            "重要指令：请务必先逐步思考用户的需求，理清问题的关键点并展示详细的思考过程，然后再给出全面、实用的回答。不要跳过思考步骤。"

        /** /no_think 前缀：强制不思考（无 CoT）。 */
        const val FORCE_NO_THINK_PROMPT =
            "重要指令：请直接给出清晰、实用的回答和建议，不要展示任何思考过程，不要使用思维链推理（CoT）。"
    }

    @Volatile
    private var config: InferenceSettings = InferenceSettings.DEFAULT

    @Volatile
    private var thinkingEnabled: Boolean = true

    /**
     * 加载模型时注入系统提示所用的思考态。
     * 系统提示（含"请先思考/直接回答"指令）只会在 loadModel 时固定进 KV 缓存，
     * 之后切换思考开关仅更新 native 模板开关，无法回头修改系统提示；
     * generate() 据此检测不一致并在本轮自动注入对应指令，保证行为与开关同步。
     */
    @Volatile
    private var thinkingAtLoad: Boolean = true

    @Volatile
    private var initialized = false
    @Volatile
    private var modelLoaded = false
    @Volatile
    private var generating = false

    /**
     * 应用推理配置（提示词、思考开关、温度、上下文、线程、max tokens）。
     * 应在加载模型（loadModel）之前调用，随 loadModel 一并生效。
     */
    fun applyConfig(cfg: InferenceSettings, thinking: Boolean = true) {
        config = cfg
        thinkingEnabled = thinking
    }

    /**
     * 初始化原生库（加载 so 及 ggml 后端）。
     */
    suspend fun initializeEngine(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            LlamaBridge.ensureLibraryLoaded()
            LlamaBridge.init(context.applicationInfo.nativeLibraryDir)
            initialized = true
            Timber.d("llama.cpp backend initialized")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to init llama.cpp")
            false
        }
    }

    /**
     * 加载 .gguf 模型。
     * @param modelPath gguf 文件的完整路径（如 /sdcard/AIModel/xxx.gguf）
     */
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!initialized) {
                Timber.e("Engine not initialized")
                return@withContext false
            }
            unload()
            val loadResult = LlamaBridge.load(modelPath, true, true)
            if (loadResult != 0) {
                Timber.e("Model load failed, code=$loadResult")
                return@withContext false
            }
            val ctxResult = LlamaBridge.prepare(
                config.threads,
                config.contextLen,
                config.temperature,
                config.topP,
                config.topK,
                config.presencePenalty,
                config.frequencyPenalty,
                thinkingEnabled,
                config.thinkingBudget,
                config.thinkingStartTag.normalizeThinkingTag(),
                config.thinkingEndTag.normalizeThinkingTag(),
                config.stopStrings.toTypedArray()
            )
            if (ctxResult != 0) {
                Timber.e("Context prepare failed, code=$ctxResult")
                return@withContext false
            }
            val sysResult = LlamaBridge.processSystemPrompt(config.effectiveSystemPrompt(thinkingEnabled))
            if (sysResult != 0) {
                Timber.e("System prompt failed, code=$sysResult")
                return@withContext false
            }
            modelLoaded = true
            thinkingAtLoad = thinkingEnabled
            Timber.d("Model loaded: $modelPath (thinking=$thinkingEnabled)")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load model")
            false
        }
    }

    /**
     * 流式生成回答。
     * @param prompt 当前提问。原生库自行维护对话上下文。
     * @param thinkOverride 单条强制思考覆盖：
     *   - true  → 强制 CoT（在提问前注入思考指令）
     *   - false → 强制无 CoT（在提问前注入禁止思考指令）
     *   - null  → 跟随全局 [thinkingEnabled]
     */
    fun generate(prompt: String, thinkOverride: Boolean? = null): Flow<String> = flow {
        if (!modelLoaded) {
            throw IllegalStateException("Model not initialized")
        }
        generating = true
        try {
            val effectiveThinking = thinkOverride ?: thinkingEnabled
            // 指令注入的两种情形：
            // 1) 单条 /think、/no_think 前缀显式覆盖；
            // 2) 模型加载后切换过全局思考开关——系统提示已在加载时按旧状态
            //    固定进 KV 缓存，为保持行为一致，本轮自动补注入对应指令。
            //    状态一致时不注入任何内容，零额外开销。
            val injectInstruction = thinkOverride != null ||
                (thinkingEnabled != thinkingAtLoad)
            val effectivePrompt = if (injectInstruction) {
                (if (effectiveThinking) FORCE_THINK_PROMPT else FORCE_NO_THINK_PROMPT) +
                    "\n\n" + prompt
            } else {
                prompt
            }
            // 让原生 chat template 的 enable_thinking 按本轮意图生效
            // （配合 use_jinja=true 进入模板）。
            LlamaBridge.setThinking(effectiveThinking)
            if (LlamaBridge.processUserPrompt(effectivePrompt, config.maxTokens) != 0) {
                throw IllegalStateException("processUserPrompt failed")
            }
            while (true) {
                val piece = LlamaBridge.generateNextToken() ?: break
                // 只 emit 增量片段，避免每 token 全量重建字符串（O(n²)）。
                // 全量拼接与节流由 UI 侧 collect 完成。
                emit(piece)
            }
        } finally {
            generating = false
        }
    }.flowOn(Dispatchers.IO)

    val isInitialized: Boolean get() = initialized
    val isModelLoaded: Boolean get() = modelLoaded
    val isBusy: Boolean get() = generating

    /** 请求中断当前生成。下一次 token 采样会立即结束。 */
    fun stop() {
        if (generating) {
            LlamaBridge.stopGeneration()
        }
    }

    /** 卸载当前模型。 */
    fun unload() {
        try {
            LlamaBridge.unload()
        } catch (e: Throwable) {
            Timber.e(e, "unload failed")
        }
        modelLoaded = false
    }

    /** 释放资源。 */
    fun release() {
        try {
            // 若仍在生成，必须先在原生层请求停止并等待生成循环完整退出，
            // 否则紧接着 unload() 的 llama_free() 会与 DefaultDispatch 解码线程
            // 并发读写同一块 g_context/KV cache，造成 use-after-free（SIGSEGV）。
            if (generating) {
                LlamaBridge.stopGeneration()
                // 阻塞等待生成线程退出（最多 2s），确保原生上下文不再被使用。
                // 生成循环在 Dispatchers.IO 的独立线程执行，这里短暂等待是安全的。
                var waited = 0L
                while (generating && waited < 2000) {
                    Thread.sleep(20)
                    waited += 20
                }
            }
            unload()
            LlamaBridge.shutdown()
        } catch (e: Throwable) {
            Timber.e(e, "shutdown failed")
        }
        initialized = false
    }
}