package com.ueiol.scanaianswer.ui.confirm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ueiol.scanaianswer.MainActivity
import com.ueiol.scanaianswer.data.ocr.OcrHelper
import com.ueiol.scanaianswer.data.ocr.OcrResult
import com.ueiol.scanaianswer.databinding.ActivityConfirmBinding
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * OCR 结果确认页。用户在确认/编辑识别文本后，
 * 把确认文本通过 setResult 回传给主页面，由主页面直接发起对话。
 */
class ConfirmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmBinding
    private lateinit var ocrHelper: OcrHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ocrHelper = OcrHelper(this)

        val imageUri = intent.getParcelableExtra<Uri>(EXTRA_IMAGE_URI)
        if (imageUri == null) {
            Toast.makeText(this, "图片无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.ivPreview.setImageURI(imageUri)
        performOcr(imageUri)

        binding.btnSubmit.setOnClickListener {
            val text = binding.etOcrResult.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "请输入题目内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 确认后回传文本给主页面，主页面直接进入对话
            val result = Intent().putExtra(MainActivity.EXTRA_CONFIRMED_TEXT, text)
            setResult(RESULT_OK, result)
            finish()
        }

        binding.btnRetake.setOnClickListener {
            finish()
        }
    }

    private fun performOcr(imageUri: Uri) {
        lifecycleScope.launch {
            binding.progressBar.visibility = android.view.View.VISIBLE
            when (val result = ocrHelper.recognize(imageUri)) {
                is OcrResult.Success -> {
                    binding.etOcrResult.setText(result.text)
                }
                is OcrResult.Error -> {
                    Timber.e(result.exception, "OCR failed")
                    Toast.makeText(
                        this@ConfirmActivity,
                        "识别失败，请手动输入",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            binding.progressBar.visibility = android.view.View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrHelper.release()
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"

        fun newIntent(context: Context, imageUri: Uri): Intent {
            return Intent(context, ConfirmActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_URI, imageUri)
            }
        }
    }
}