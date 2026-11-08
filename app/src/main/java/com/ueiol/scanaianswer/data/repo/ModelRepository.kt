package com.ueiol.scanaianswer.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 扫描 /sdcard/AIModel/ 目录下的 .gguf 模型文件。
 */
class ModelRepository {

    companion object {
        private const val MODEL_ROOT = "/sdcard/AIModel"
    }

    suspend fun scanModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val rootDir = File(MODEL_ROOT)
        // 目录不存在时自动新建
        if (!rootDir.exists()) {
            val created = try {
                rootDir.mkdirs()
            } catch (e: Exception) {
                Timber.e(e, "Failed to create model directory: $MODEL_ROOT")
                false
            }
            Timber.i("Model directory not found, creating: $MODEL_ROOT success=$created")
        }
        if (!rootDir.exists() || !rootDir.isDirectory) {
            Timber.w("Model directory unavailable: $MODEL_ROOT")
            return@withContext emptyList()
        }

        // 直接扫描 AIModel/ 根目录下的 .gguf，一个文件即一个模型
        rootDir.listFiles { f ->
            f.isFile && f.extension.equals("gguf", ignoreCase = true)
        }
            ?.map { f ->
                ModelInfo(
                    name = f.nameWithoutExtension,
                    ggufPath = f.absolutePath,
                    path = f.absolutePath
                )
            }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /** 获取指定模型对应的 .gguf 路径；不存在返回 null。 */
    fun getModelGgufPath(modelName: String): String? {
        val rootFile = File(MODEL_ROOT, "$modelName.gguf")
        return if (rootFile.isFile) rootFile.absolutePath else null
    }
}