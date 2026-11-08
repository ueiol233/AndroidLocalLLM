package com.ueiol.scanaianswer.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * MLKit 中英文 OCR 封装。
 * 同时启用中文和拉丁文识别器，合并结果。
 */
class OcrHelper(private val context: Context) {

    private val chineseRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val latinRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    suspend fun recognize(imageUri: Uri): OcrResult {
        return try {
            val image = InputImage.fromFilePath(context, imageUri)

            // 优先中文识别
            val result = suspendCancellableCoroutine { continuation ->
                chineseRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val text = visionText.text
                        if (text.isNotBlank()) {
                            continuation.resume(OcrResult.Success(text))
                        } else {
                            // 中文识别为空，尝试拉丁
                            latinRecognizer.process(image)
                                .addOnSuccessListener { latinText ->
                                    continuation.resume(
                                        OcrResult.Success(latinText.text)
                                    )
                                }
                                .addOnFailureListener { e ->
                                    Timber.e(e, "Latin OCR failed")
                                    continuation.resume(OcrResult.Error(e))
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Timber.e(e, "Chinese OCR failed")
                        continuation.resume(OcrResult.Error(e))
                    }
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "OCR processing failed")
            OcrResult.Error(e)
        }
    }

    fun release() {
        chineseRecognizer.close()
        latinRecognizer.close()
    }
}