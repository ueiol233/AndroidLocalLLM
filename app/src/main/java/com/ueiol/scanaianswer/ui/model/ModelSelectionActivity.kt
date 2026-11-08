package com.ueiol.scanaianswer.ui.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ueiol.scanaianswer.data.repo.ModelInfo
import com.ueiol.scanaianswer.data.repo.ModelRepository
import com.ueiol.scanaianswer.databinding.ActivityModelSelectionBinding
import com.ueiol.scanaianswer.databinding.ItemModelBinding
import kotlinx.coroutines.launch

class ModelSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelSelectionBinding
    private val repository = ModelRepository()
    private val adapter = ModelAdapter { model ->
        onModelSelected(model)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadModels()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadModels() {
        lifecycleScope.launch {
            val models = repository.scanModels()
            if (models.isEmpty()) {
                Toast.makeText(
                    this@ModelSelectionActivity,
                    "未找到模型，请将模型放入 /sdcard/AIModel/",
                    Toast.LENGTH_LONG
                ).show()
            }
            adapter.submitList(models)
        }
    }

    private fun onModelSelected(model: ModelInfo) {
        val result = Intent().apply {
            putExtra(EXTRA_MODEL_NAME, model.name)
            putExtra(EXTRA_MODEL_PATH, model.path)
            putExtra(EXTRA_GGUF_PATH, model.ggufPath)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_GGUF_PATH = "gguf_path"

        fun newIntent(context: Context): Intent {
            return Intent(context, ModelSelectionActivity::class.java)
        }
    }
}

class ModelAdapter(
    private val onClick: (ModelInfo) -> Unit
) : RecyclerView.Adapter<ModelAdapter.ViewHolder>() {

    private var items: List<ModelInfo> = emptyList()

    fun submitList(list: List<ModelInfo>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemModelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(model: ModelInfo) {
            binding.tvModelName.text = model.name
            binding.tvModelPath.text = model.path
            binding.root.setOnClickListener { onClick(model) }
        }
    }
}