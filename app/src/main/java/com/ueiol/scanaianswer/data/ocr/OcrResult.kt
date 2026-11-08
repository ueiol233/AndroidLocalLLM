package com.ueiol.scanaianswer.data.ocr

sealed class OcrResult {
    data class Success(val text: String) : OcrResult()
    data class Error(val exception: Exception) : OcrResult()
}