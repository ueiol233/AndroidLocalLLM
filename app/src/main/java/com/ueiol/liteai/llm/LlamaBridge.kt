package com.ueiol.liteai.llm

import android.util.Log

/**
 * llama.cpp (liteai) 原生库 JNI 桥。
 *
 * 支持直接加载 /sdcard/AIModel/ 目录下的 .gguf 模型并流式生成文本。
 * 必须保持包名 com.ueiol.liteai.llm .LlamaBridge，与 libliteai.so 的导出符号一一对应。
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"
    private var libraryLoaded = false

    fun ensureLibraryLoaded() {
        if (libraryLoaded) return
        try {
            System.loadLibrary("liteai")
            libraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libliteai.so", e)
            throw e
        }
    }

    /**
     * 初始化 llama 后端，加载 nativeLibDir 下的所有 ggml 后端（CPU）so。
     */
    external fun init(nativeLibDir: String)

    /**
     * 加载 .gguf 模型。返回 0 表示成功，非 0 表示失败。
     */
    external fun load(modelPath: String, useMmap: Boolean, useMlock: Boolean): Int

    /**
     * 初始化上下文与采样器。返回 0 表示成功，非 0 表示失败。
     * @param temp 采样温度
     * @param topP 核采样（Top-p）阈值；1.0 = 禁用
     * @param topK 采样保留的最高概率 token 数；<=0 用词表大小
     * @param presencePenalty 存在惩罚；0.0 = 禁用
     * @param frequencyPenalty 频率惩罚；0.0 = 禁用
     * @param stopStrings 自定义停止序列，命中任一即终止生成（可为空数组）
     * @param enableThinking 是否启用模型的思考模式（chat template 的 enable_thinking 参数）
     * @param thinkingBudget 思考 token 预算；-1 = 不限制，>=0 时思考型模型最多思考该 token 数即强制结束
     * @param thinkingStartTag 思考开启标记（如 " 思考"、" thinking"），需与模型输出一致
     * @param thinkingEndTag 思考结束标记（如 " 回答："、" response"），需与模型输出一致
     */
    external fun prepare(
        nThreads: Int,
        nCtx: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        presencePenalty: Float,
        frequencyPenalty: Float,
        enableThinking: Boolean,
        thinkingBudget: Int,
        thinkingStartTag: String,
        thinkingEndTag: String,
        stopStrings: Array<String>
    ): Int

    /**
     * 设定系统提示（含 chat 模板）。返回 0 表示成功。
     */
    external fun processSystemPrompt(systemPrompt: String): Int

    /**
     * 输入用户文本并开始一轮生成。返回 0 表示成功。
     */
    external fun processUserPrompt(userPrompt: String, nPredict: Int): Int

    /**
     * 在会话进行中动态设置是否使用 CoT 思考模式。
     * 用于 ./think、./no_think 前缀对某一条消息强制覆盖思考行为。
     */
    external fun setThinking(enabled: Boolean)

    /**
     * 取下一个生成的 token 片段；生成结束返回 null。
     */
    external fun generateNextToken(): String?

    /**
     * 请求中断当前生成循环。下一次 generateNextToken() 调用会立即返回 null。
     */
    external fun stopGeneration()

    /**
     * 返回后端/系统信息描述。
     */
    external fun systemInfo(): String

    /**
     * 基准测试，返回 markdown 结果字符串。
     */
    external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    /**
     * 释放模型与上下文资源。
     */
    external fun unload()

    /**
     * 关闭 llama 后端。
     */
    external fun shutdown()
}