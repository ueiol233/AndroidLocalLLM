package com.ueiol.scanaianswer.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ueiol.scanaianswer.data.db.ChatSessionEntity
import com.ueiol.scanaianswer.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抽屉「历史对话」列表适配器。
 * 基于 AsyncListDiffer 做增量差异刷新，避免长历史时全量 rebind。
 */
class HistoryAdapter(
    private val onClick: (ChatSessionEntity) -> Unit,
    private val onDelete: (ChatSessionEntity) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val differ = AsyncListDiffer(this, DiffCallback)

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        /**
         * 条目差异比较：sessionId 标识同一项，其余字段决定内容是否变化。
         * ChatSessionEntity 为 data class，equals 基于全部字段。
         */
        private val DiffCallback = object : DiffUtil.ItemCallback<ChatSessionEntity>() {
            override fun areItemsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity): Boolean =
                oldItem.sessionId == newItem.sessionId

            override fun areContentsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity): Boolean =
                oldItem == newItem
        }
    }

    fun submitList(list: List<ChatSessionEntity>) {
        differ.submitList(list)
    }

    fun getItem(position: Int): ChatSessionEntity = differ.currentList[position]

    fun removeItem(position: Int) {
        val newList = differ.currentList.toMutableList().apply { removeAt(position) }
        differ.submitList(newList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(differ.currentList[position])
    }

    override fun getItemCount(): Int = differ.currentList.size

    inner class ViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        val cardView: View get() = binding.cardItem

        fun bind(session: ChatSessionEntity) {
            binding.tvTitle.text = session.title
            binding.tvTime.text = TIME_FORMAT.format(Date(session.updatedAt))
            binding.cardItem.setOnClickListener { onClick(session) }
            binding.layoutDeleteBackground.setOnClickListener { onDelete(session) }
        }
    }
}