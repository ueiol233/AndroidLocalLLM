package com.ueiol.scanaianswer.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ueiol.scanaianswer.R
import com.ueiol.scanaianswer.data.settings.InferencePrefs
import com.ueiol.scanaianswer.data.settings.InferenceSettings
import com.ueiol.scanaianswer.databinding.ActivityInferenceSettingsBinding

/**
 * 推理设置页：自定义系统提示词、温度、生成长度、上下文长度、线程数。
 * 保存到 SharedPreferences，下次加载模型时生效（见 MainActivity.ensureModelLoaded）。
 */
class InferenceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInferenceSettingsBinding
    private var current = InferenceSettings.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInferenceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadAndBind()
        setupActions()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadAndBind() {
        current = InferencePrefs.load(this)
        binding.etPrompt.setText(current.systemPrompt)
        binding.etTemperature.setText(current.temperature.toString())
        binding.etTopP.setText(current.topP.toString())
        binding.etTopK.setText(current.topK.toString())
        binding.etPresencePenalty.setText(current.presencePenalty.toString())
        binding.etFrequencyPenalty.setText(current.frequencyPenalty.toString())
        binding.etStopStrings.setText(current.stopStrings.joinToString(","))
        binding.etMaxTokens.setText(current.maxTokens.toString())
        binding.etContextLen.setText(current.contextLen.toString())
        binding.etThreads.setText(current.threads.toString())
        binding.etThinkingBudget.setText(current.thinkingBudget.toString())
        binding.etThinkingStartTag.setText(current.thinkingStartTag)
        binding.etThinkingEndTag.setText(current.thinkingEndTag)
    }

    private fun setupActions() {
        binding.btnSave.setOnClickListener { onSave() }
        binding.btnReset.setOnClickListener { onReset() }
    }

    private fun onSave() {
        val temp = binding.etTemperature.text.toString().toFloatOrNull() ?: run {
            Toast.makeText(this, "请输入有效的温度", Toast.LENGTH_SHORT).show()
            return
        }
        if (temp < 0f || temp > 2f) {
            Toast.makeText(this, "温度需在 0.0 ~ 2.0 之间", Toast.LENGTH_SHORT).show()
            return
        }
        val topP = binding.etTopP.text.toString().toFloatOrNull() ?: run {
            Toast.makeText(this, "请输入有效的 Top-p 值", Toast.LENGTH_SHORT).show()
            return
        }
        if (topP < 0f || topP > 1f) {
            Toast.makeText(this, "Top-p 需在 0.0 ~ 1.0 之间", Toast.LENGTH_SHORT).show()
            return
        }
        val topK = binding.etTopK.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的 Top-k 值", Toast.LENGTH_SHORT).show()
            return
        }
        if (topK < 1 || topK > 100) {
            Toast.makeText(this, "Top-k 需在 1 ~ 100 之间", Toast.LENGTH_SHORT).show()
            return
        }
        val presencePenalty = binding.etPresencePenalty.text.toString().toFloatOrNull() ?: run {
            Toast.makeText(this, "请输入有效的存在惩罚值", Toast.LENGTH_SHORT).show()
            return
        }
        if (presencePenalty < 0f || presencePenalty > 2f) {
            Toast.makeText(this, "存在惩罚需在 0.0 ~ 2.0 之间", Toast.LENGTH_SHORT).show()
            return
        }
        val frequencyPenalty = binding.etFrequencyPenalty.text.toString().toFloatOrNull() ?: run {
            Toast.makeText(this, "请输入有效的频率惩罚值", Toast.LENGTH_SHORT).show()
            return
        }
        if (frequencyPenalty < 0f || frequencyPenalty > 2f) {
            Toast.makeText(this, "频率惩罚需在 0.0 ~ 2.0 之间", Toast.LENGTH_SHORT).show()
            return
        }
        val stopStrings = binding.etStopStrings.text.toString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val maxTokens = binding.etMaxTokens.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的生成长度", Toast.LENGTH_SHORT).show()
            return
        }
        if (maxTokens < 64 || maxTokens > 2048) {
            Toast.makeText(this, "生成长度需在 64 ~ 2048 之间", Toast.LENGTH_SHORT).show()
            return
        }
        val contextLen = binding.etContextLen.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的上下文长度", Toast.LENGTH_SHORT).show()
            return
        }
        if (contextLen < 512 || contextLen > 8192) {
            Toast.makeText(this, "上下文长度需在 512 ~ 8192 之间", Toast.LENGTH_SHORT).show()
            return
        }
        val threads = binding.etThreads.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的线程数", Toast.LENGTH_SHORT).show()
            return
        }
        if (threads < 1 || threads > 16) {
            Toast.makeText(this, "线程数需在 1 ~ 16 之间", Toast.LENGTH_SHORT).show()
            return
        }

        val thinkingBudget = binding.etThinkingBudget.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "请输入有效的思考长度上限（-1 表示不限制）", Toast.LENGTH_SHORT).show()
            return
        }
        if (thinkingBudget < -1) {
            Toast.makeText(this, "思考长度上限需大于等于 -1", Toast.LENGTH_SHORT).show()
            return
        }
        val thinkingStartTag = binding.etThinkingStartTag.text.toString().trim()
        val thinkingEndTag = binding.etThinkingEndTag.text.toString().trim()
        if (thinkingStartTag.isBlank() || thinkingEndTag.isBlank()) {
            Toast.makeText(this, "请填写思考开始与结束标记", Toast.LENGTH_SHORT).show()
            return
        }

        val next = InferenceSettings(
            systemPrompt = binding.etPrompt.text.toString(),
            temperature = temp,
            maxTokens = maxTokens,
            contextLen = contextLen,
            threads = threads,
            topP = topP,
            topK = topK,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            stopStrings = stopStrings,
            thinkingBudget = thinkingBudget,
            thinkingStartTag = thinkingStartTag,
            thinkingEndTag = thinkingEndTag
        )
        InferencePrefs.save(this, next)
        current = next
        Toast.makeText(this, R.string.inference_settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun onReset() {
        InferencePrefs.clear(this)
        current = InferenceSettings.DEFAULT
        loadAndBind()
        Toast.makeText(this, R.string.inference_settings_reset, Toast.LENGTH_SHORT).show()
    }
}