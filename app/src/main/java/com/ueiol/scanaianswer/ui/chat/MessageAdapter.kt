package com.ueiol.scanaianswer.ui.chat

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ueiol.scanaianswer.R
import com.ueiol.scanaianswer.data.session.Message
import com.ueiol.scanaianswer.databinding.ItemMessageAssistantBinding
import com.ueiol.scanaianswer.databinding.ItemMessageUserBinding

/**
 * 聊天消息列表适配器。主页面与历史恢复共用。
 * 支持 think 标签的实时思考 + 展开/折叠。
 */
class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<Message>()

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_ASSISTANT = 2

        /** 仅刷思考内容的 payload（不重建整个 item，保留展开状态）。 */
        private const val PAYLOAD_THINKING = "thinking"
    }

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun setMessages(list: List<Message>) {
        messages.clear()
        messages.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * 流式更新最后一条 assistant 消息。
     * 使用实时解析（fromStream）。更新思考内容时走 payload，不重建 item，
     * 因此用户在展开状态下展开的思考区不会因新 token 又被收起。
     */
    fun updateLastAssistantMessage(content: String) {
        val lastIndex = messages.lastIndex
        val last = messages.getOrNull(lastIndex) as? Message.Assistant
        if (last != null) {
            val newMsg = Message.Assistant.fromStream(content, last)
            val stateChanged = newMsg.isThinking != last.isThinking
            messages[lastIndex] = newMsg
            if (stateChanged) {
                // 阶段切换（思考↔回答）导致 item 高度突变（思考区收起/回答区出现），
                // 必须用完整 notifyItemChanged 让 RecyclerView 重新测量 item 尺寸；
                // 带 payload 的更新不会触发高度重新测量，气泡会按旧高度缓存错位。
                notifyItemChanged(lastIndex)
            } else if (last.thinkingContent != newMsg.thinkingContent) {
                // 思考中仅刷文本，高度随内容自然增长，用 payload 局部更新即可。
                notifyItemChanged(lastIndex, PAYLOAD_THINKING)
            } else {
                notifyItemChanged(lastIndex, PAYLOAD_THINKING)
            }
        } else {
            messages.add(
                Message.Assistant.fromStream(
                    content,
                    Message.Assistant(content = "")
                )
            )
            notifyItemInserted(messages.size - 1)
        }
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    val itemCountValue: Int get() = messages.size

    override fun getItemViewType(position: Int): Int {
        return when (messages[position]) {
            is Message.User -> TYPE_USER
            is Message.Assistant -> TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_USER -> {
                val binding = ItemMessageUserBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                UserViewHolder(binding)
            }
            else -> {
                val binding = ItemMessageAssistantBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                AssistantViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val message = messages[position] as? Message.Assistant ?: return
        if (holder !is AssistantViewHolder) return
        if (payloads.contains(PAYLOAD_THINKING)) {
            // 思考进行中仅刷思考文本 + 回答区可见性
            holder.updateThinkingContent(message, position)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(message as Message.User)
            is AssistantViewHolder -> holder.bind(message as Message.Assistant, position)
        }
    }

    override fun getItemCount(): Int = messages.size

    inner class UserViewHolder(
        private val binding: ItemMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message.User) {
            binding.tvContent.text = message.content
        }
    }

    inner class AssistantViewHolder(
        private val binding: ItemMessageAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var expanded = false
        private val handler = Handler(Looper.getMainLooper())
        private var thinkingRunnable: Runnable? = null

        fun bind(message: Message.Assistant, position: Int) {
            // 从消息恢复展开状态（滚动离开再回来时保持一致）
            expanded = message.isThinkingExpanded

            // 无论处于哪个分支都把展开按钮的监听注册好（流式 payload 刷新的 holder
            // 不会再次走完整 bind，若只在"有思考内容"分支注册，刚输出的思考区将无法展开）
            binding.btnThinkingToggle.setOnClickListener {
                toggleThinkingExpanded(position)
            }

            when {
                message.thinkingContent.isNotBlank() || message.isThinking -> {
                    // 有思考内容或正在思考：统一显示可折叠的思考区域（带灯泡图标）
                    binding.tvThinkingIndicator.visibility = View.GONE
                    binding.layoutThinking.visibility = View.VISIBLE

                    // 标题：思考中显示秒数，完成后显示"思考完成"
                    updateThinkingTitle(message)

                    // 思考内容（实时更新）
                    binding.tvThinkingContent.setLatexText(message.thinkingContent)

                    // 正式回答：思考结束前不显示
                    if (message.isThinking || message.answerContent.isBlank()) {
                        binding.tvContent.visibility = View.GONE
                    } else {
                        binding.tvContent.visibility = View.VISIBLE
                        binding.tvContent.setLatexText(message.answerContent)
                    }

                    updateThinkingExpandState()
                }
                else -> {
                    // 普通回答（无 think 标签，或纯流式回答）
                    binding.tvThinkingIndicator.visibility = View.GONE
                    binding.layoutThinking.visibility = View.GONE
                    binding.tvContent.visibility = View.VISIBLE
                    binding.tvContent.setLatexText(message.answerContent)
                }
            }
        }

        /** payload 只刷思考内容：文本 + 若展开则让内容区跟随变化。不触碰展开状态。 */
        fun updateThinkingContent(message: Message.Assistant, position: Int) {
            // 若该 holder 是被滚动复用/新绑定而直接 payload 进入（未走完整 bind），
            // 需要补齐展开状态与点击监听，保证思考区可展开
            expanded = message.isThinkingExpanded
            binding.btnThinkingToggle.setOnClickListener {
                toggleThinkingExpanded(position)
            }

            // 确保思考区域可见（RecyclerView 复用或初始状态可能为 GONE）
            binding.tvThinkingIndicator.visibility = View.GONE
            binding.layoutThinking.visibility = View.VISIBLE
            binding.tvContent.visibility = if (message.isThinking) View.GONE else View.VISIBLE

            // 进入回答阶段后仍是流式（payload 局部刷新），必须同步渲染回答文本，
            // 否则回答 token 不会实时显示，直到触发完整 bind（如点击思考按钮）才刷新。
            if (!message.isThinking) {
                binding.tvContent.setLatexText(message.answerContent)
            }

            updateThinkingTitle(message)
            binding.tvThinkingContent.setLatexText(message.thinkingContent)
            updateThinkingExpandState()

            // 思考区展开/折叠会改变 item 测量高度，主动请求测量以让 RecyclerView
            // 重新计算本 item 尺寸并重排其下方 item，避免 stackFromEnd 列表位置错位。
            binding.root.requestLayout()
        }

        private fun updateThinkingTitle(message: Message.Assistant) {
            // 取消旧的定时器
            thinkingRunnable?.let { handler.removeCallbacks(it) }
            thinkingRunnable = null

            if (message.isThinking && message.thinkingStartTime > 0) {
                val runnable = object : Runnable {
                    override fun run() {
                        val seconds = ((System.currentTimeMillis() - message.thinkingStartTime) / 1000).toInt()
                        binding.tvThinkingTitle.text = "已思考${seconds}秒"
                        handler.postDelayed(this, 1000)
                    }
                }
                thinkingRunnable = runnable
                runnable.run()
            } else {
                binding.tvThinkingTitle.text = "思考完成"
            }
        }

        private fun updateThinkingExpandState() {
            if (expanded) {
                binding.tvThinkingContent.visibility = View.VISIBLE
                binding.ivThinkingIcon.setImageResource(R.drawable.ic_thinking_on)
                binding.ivThinkingArrow.setImageResource(R.drawable.ic_chevron_down)
            } else {
                binding.tvThinkingContent.visibility = View.GONE
                binding.ivThinkingIcon.setImageResource(R.drawable.ic_thinking_off)
                binding.ivThinkingArrow.setImageResource(R.drawable.ic_chevron_right)
            }
        }

        private fun toggleThinkingExpanded(position: Int) {
            expanded = !expanded
            // 同步回消息，便于滚动重绑时恢复
            val msg = messages.getOrNull(position) as? Message.Assistant ?: return
            messages[position] = msg.copy(isThinkingExpanded = expanded)
            // 展开/折叠会改变 item 高度（思考区 GONE↔VISIBLE），必须用完整
            // notifyItemChanged 让 RecyclerView 重新测量并重排下方 item，否则
            // stackFromEnd 列表会按旧高度快照排布，气泡被挤压/覆盖（位置错位）。
            notifyItemChanged(position)
        }
    }
}