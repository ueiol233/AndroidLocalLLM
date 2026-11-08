package com.ueiol.scanaianswer.data.repo

/**
 * 扫描 /sdcard/AIModel/ 得到的模型信息。
 */
data class ModelInfo(
    val name: String,
    val path: String,       // /sdcard/AIModel/<name>/
    val ggufPath: String    // /sdcard/AIModel/<name>/xxx.gguf
)