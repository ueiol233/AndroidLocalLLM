package com.ueiol.scanaianswer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ueiol.scanaianswer.data.db.ChatSessionEntity
import com.ueiol.scanaianswer.data.session.ChatRepository
import com.ueiol.scanaianswer.data.session.ChatSession
import com.ueiol.scanaianswer.data.session.Message
import com.ueiol.scanaianswer.data.settings.InferencePrefs
import com.ueiol.scanaianswer.databinding.ActivityMainBinding
import com.ueiol.scanaianswer.llm.LlamaService
import com.ueiol.scanaianswer.ui.capture.CaptureActivity
import com.ueiol.scanaianswer.ui.chat.HistoryAdapter
import com.ueiol.scanaianswer.ui.chat.MessageAdapter
import com.ueiol.scanaianswer.ui.model.ModelSelectionActivity
import com.ueiol.scanaianswer.ui.settings.InferenceSettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ChatRepository

    private val llmService = LlamaService()
    private val messageAdapter = MessageAdapter()
    private val historyAdapter = HistoryAdapter(
        onClick = { session -> onHistorySelected(session) },
        onDelete = { session -> onHistoryDelete(session) }
    )

    private var currentSession: ChatSession? = null
    private var selectedModelGguf: String? = null
    private var selectedModelName: String? = null
    private var storagePermissionRequested = false
    private var isGenerating = false
    private var thinkingEnabled = true

    // 模型加载互斥锁：同一时刻只允许一个 loadModel 在跑，防止快速反复切换思考时并发重载模型触发 native 竞态崩溃
    private var isModelLoading = false
    private val modelLoadMutex = Mutex()

    // 思考开关菜单项引用，用于在生成期间禁用（避免生成中重载模型导致原生崩溃）
    private var thinkingMenuItem: MenuItem? = null

    // 选择模型（设置菜单进入）
    private val modelSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            selectedModelName = data?.getStringExtra(ModelSelectionActivity.EXTRA_MODEL_NAME)
            selectedModelGguf = data?.getStringExtra(ModelSelectionActivity.EXTRA_GGUF_PATH)
            persistSelectedModel()
            updateDrawerModel()
        }
    }

    // 拍照 → 裁剪 → OCR 确认 → 回传文本 → 直接对话
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(EXTRA_CONFIRMED_TEXT)
            if (!text.isNullOrBlank()) {
                startNewProblem(text)
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCaptureScreen()
        } else {
            Toast.makeText(this, R.string.error_camera, Toast.LENGTH_SHORT).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = ChatRepository(this)
        restoreSelectedModel()
        restoreThinkingState()

        setupDrawer()
        setupToolbar()
        setupChatList()
        setupInput()
        observeSessions()
        checkStoragePermission()
    }

    // ---------- 模型选择持久化（记住上次选中的模型） ----------
    private fun restoreSelectedModel() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        selectedModelName = prefs.getString(KEY_MODEL_NAME, null)
        selectedModelGguf = prefs.getString(KEY_GGUF_PATH, null)
    }

    private fun persistSelectedModel() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MODEL_NAME, selectedModelName)
            .putString(KEY_GGUF_PATH, selectedModelGguf)
            .apply()
    }

    // ---------- 思考开关持久化 ----------
    private fun restoreThinkingState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        thinkingEnabled = prefs.getBoolean(KEY_THINKING, true)
    }

    private fun persistThinkingState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_THINKING, thinkingEnabled)
            .apply()
    }

    /** 切换思考开关后，在底部弹出提示（说明生效范围）。 */
    private fun showThinkingTip() {
        val text = if (thinkingEnabled) R.string.thinking_on_tip else R.string.thinking_off_tip
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }

    private fun updateThinkingIcon(menuItem: MenuItem?) {
        menuItem?.setIcon(if (thinkingEnabled) R.drawable.ic_thinking_on else R.drawable.ic_thinking_off)
        // 生成中或模型加载中禁用思考开关：避免重载模型造成原生层竞态崩溃
        updateThinkingMenuEnabled()
    }

    // ---------- Drawer ----------
    private fun setupDrawer() {
        binding.drawerLayout.closeDrawer(binding.drawer, false)

        updateDrawerModel()

        binding.btnSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.drawer)
            modelSelectionLauncher.launch(ModelSelectionActivity.newIntent(this))
        }

        binding.btnInference.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.drawer)
            startActivity(Intent(this, InferenceSettingsActivity::class.java))
        }
    }

    private fun updateDrawerModel() {
        val name = selectedModelName
        binding.tvDrawerModel.text = if (name != null) "当前模型: $name" else getString(R.string.no_model_selected)
    }

    private fun onHistorySelected(session: ChatSessionEntity) {
        binding.drawerLayout.closeDrawer(binding.drawer)
        lifecycleScope.launch {
            val loaded = repository.loadSession(session.sessionId) ?: return@launch
            currentSession = loaded
            messageAdapter.setMessages(loaded.messages)
            updateEmptyState()

            // 切换到历史会话：加载其模型并重置上下文（新的对话起点）
            val ready = ensureModelLoaded(loaded.modelPath)
            if (!ready) {
                Toast.makeText(this@MainActivity, "模型加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Toolbar & navigation ----------
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(binding.drawer)
        }
        binding.toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_toggle_thinking -> {
                    thinkingEnabled = !thinkingEnabled
                    persistThinkingState()
                    updateThinkingIcon(item)
                    showThinkingTip()
                    // 只切换思考开关，不重载模型：
                    // generate() 每次生成前会读取 thinkingEnabled（setThinking），
                    // 因此仅 applyConfig 更新字段即可在下一轮生效，避免重载引发的开销与竞态。
                    llmService.applyConfig(InferencePrefs.load(this), thinkingEnabled)
                    true
                }
                R.id.action_new_problem -> {
                    newChat()
                    true
                }
                else -> false
            }
        }
        // 初始化图标状态并缓存菜单项引用（供生成期间禁用）
        binding.toolbar.post {
            thinkingMenuItem = binding.toolbar.menu.findItem(R.id.action_toggle_thinking)
            updateThinkingIcon(thinkingMenuItem)
        }
    }

    // ---------- New chat ----------
    /** 点击“+”新建一个空对话（不开相机），保留当前模型。 */
    private fun newChat() {
        if (isGenerating) return

        val session = ChatSession(modelPath = selectedModelGguf ?: "")
        currentSession = session
        repositorySinceUi { repository.createSession(session) }

        messageAdapter.clear()
        binding.etInput.text?.clear()
        updateEmptyState()
        scrollToBottom()
        setInputEnabled(true)
        setLoadingState(false)
    }

    // ---------- Chat list ----------
    private fun setupChatList() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        // 关闭 item 动画：流式更新高频触发 notifyItemChanged/payload，DefaultItemAnimator
        // 会对 change 做位移动画，配合 stackFromEnd 高度突变会造成气泡位置错位/跳动。
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = messageAdapter
    }

    // ---------- Input ----------
    private fun setupInput() {
        binding.btnCamera.setOnClickListener { startCameraFlow() }
        binding.btnSend.setOnClickListener {
            val input = binding.etInput.text.toString()
            if (input.isBlank() || isGenerating) return@setOnClickListener
            binding.etInput.text?.clear()
            val (text, override) = parseThinkCommand(input)
            sendMessage(text, override)
        }
    }

    /**
     * 解析输入框中的单条思考命令前缀：
     *   /no_think  强制不使用 CoT（覆盖全局思考开关）
     *   /think     强制执行 CoT
     * 其余情况返回原始文本与 null（跟随全局开关）。
     * @return (去除命令后的真实文本, thinkOverride) ；true=强制思考 false=强制不思考 null=跟随全局
     */
    private fun parseThinkCommand(input: String): Pair<String, Boolean?> {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("/no_think", ignoreCase = true) -> {
                trimmed.removePrefix("/no_think").trim() to false
            }
            trimmed.startsWith("/think", ignoreCase = true) -> {
                trimmed.removePrefix("/think").trim() to true
            }
            else -> input to null
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        binding.etInput.isEnabled = enabled
        binding.btnSend.isEnabled = enabled
        binding.btnSend.alpha = if (enabled) 1.0f else 0.5f
        // 同步思考开关菜单项可用性（生成中禁用，防止生成中切换触发模型重载崩溃）
        thinkingMenuItem?.isEnabled = enabled
    }

    private fun updateEmptyState() {
        val hasMessages = messageAdapter.itemCountValue > 0
        binding.tvEmptyState.visibility = if (hasMessages) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasMessages) View.VISIBLE else View.GONE
    }

    // ---------- Camera → text → chat ----------
    private fun startCameraFlow() {
        if (selectedModelGguf == null) {
            Toast.makeText(this, "请先在设置中选择模型", Toast.LENGTH_SHORT).show()
            return
        }
        if (hasCameraPermission()) {
            openCaptureScreen()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCaptureScreen() {
        captureLauncher.launch(CaptureActivity.newIntent(this, selectedModelGguf))
    }

    // ---------- Message send & generation ----------
    private fun sendMessage(text: String, thinkOverride: Boolean? = null) {
        if (currentSession == null) {
            startNewProblem(text, thinkOverride)
            return
        }
        val session = currentSession ?: return
        session.addUserMessage(text)
        messageAdapter.addMessage(Message.User(text))
        // 新建对话后此处 currentSession 非空、走本分支；addMessage 后必须更新空状态，
        // 把"暂无对话"空视图切回 RecyclerView 可见，否则消息已添加却显示不出来。
        updateEmptyState()
        scrollToBottom()
        repositorySinceUi {
            repository.appendMessage(session.sessionId, Message.User(text))
        }
        generateResponse(text, thinkOverride)
    }

    private fun startNewProblem(text: String, thinkOverride: Boolean? = null) {
        lifecycleScope.launch {
            val gguPath = selectedModelGguf ?: run {
                Toast.makeText(this@MainActivity, "未选择模型", Toast.LENGTH_SHORT).show()
                return@launch
            }

            setLoadingState(true, "正在加载模型...")
            val ready = ensureModelLoaded(gguPath)
            setLoadingState(false)
            if (!ready) {
                Toast.makeText(this@MainActivity, R.string.error_model, Toast.LENGTH_LONG).show()
                return@launch
            }

            val session = ChatSession(modelPath = gguPath)
            session.addUserMessage(text)
            repository.createSession(session)
            repository.appendMessage(session.sessionId, Message.User(text))

            currentSession = session
            messageAdapter.clear()
            messageAdapter.addMessage(Message.User(text))
            updateEmptyState()
            scrollToBottom()

            generateResponse(text, thinkOverride)
        }
    }

    private fun generateResponse(prompt: String, thinkOverride: Boolean? = null) {
        val session = currentSession ?: return
        // 本轮是否实际执行思考：单条 /think、/no_think 优先，其次全局开关
        val thinkingThisTurn = thinkOverride ?: thinkingEnabled
        lifecycleScope.launch {
            isGenerating = true
            setInputEnabled(false)
            setLoadingState(true, if (thinkingThisTurn) getString(R.string.thinking) else getString(R.string.generating))
            messageAdapter.addMessage(Message.Assistant("", isThinking = true))
            scrollToBottom()

            // 拼接本次完整回答（collect 收到的为增量片段）。
            // 用 StringBuilder 累积，避免每次 += 都对全量做一次 String 拷贝（O(n²)）。
            val answerBuilder = StringBuilder()
            // 流式节流：时间窗内合并多次刷新，减少 UI 线程全量解析次数。
            // 原生/Kotlin 侧 emit 的是增量片段，这里负责拼接全量并决定何时刷新。
            val throttleMs = 50L
            var lastFlushMs = 0L
            try {
                llmService.generate(prompt, thinkOverride).collectLatest { piece ->
                    answerBuilder.append(piece)
                    val now = System.currentTimeMillis()
                    if (now - lastFlushMs >= throttleMs) {
                        lastFlushMs = now
                        messageAdapter.updateLastAssistantMessage(answerBuilder.toString())
                        scrollToBottom()
                    }
                }
                val lastAssistant = answerBuilder.toString()
                // 兜底刷新最后一帧，保证末尾 token 完整显示
                if (lastAssistant.isNotBlank()) {
                    messageAdapter.updateLastAssistantMessage(lastAssistant)
                    scrollToBottom()
                }
                if (lastAssistant.isNotBlank()) {
                    session.addAssistantMessage(lastAssistant)
                    repository.appendMessage(session.sessionId, Message.Assistant(lastAssistant))
                } else {
                    messageAdapter.updateLastAssistantMessage("生成失败，请重试。")
                }
            } catch (e: Exception) {
                Timber.e(e, "Generation failed")
                messageAdapter.updateLastAssistantMessage("生成失败，请重试。")
                Toast.makeText(this@MainActivity, "生成失败", Toast.LENGTH_SHORT).show()
            }
            isGenerating = false
            setInputEnabled(true)
            setLoadingState(false)
        }
    }

    private suspend fun ensureModelLoaded(ggufPath: String): Boolean {
        return modelLoadMutex.withLock {
            isModelLoading = true
            updateThinkingMenuEnabled()
            try {
                val engineReady = llmService.initializeEngine(this)
                if (!engineReady) return@withLock false
                val cfg = InferencePrefs.load(this)
                llmService.applyConfig(cfg, thinkingEnabled)
                llmService.loadModel(ggufPath)
            } finally {
                isModelLoading = false
                updateThinkingMenuEnabled()
            }
        }
    }

    /** 生成中或模型加载期间禁用思考开关菜单，避免并发重载 native 模型崩溃。 */
    private fun updateThinkingMenuEnabled() {
        thinkingMenuItem?.isEnabled = !isGenerating && !isModelLoading
    }

    private fun repositorySinceUi(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    // ---------- Drawer history observer ----------
    private fun observeSessions() {
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter
        setupHistorySwipeToDelete()
        lifecycleScope.launch {
            repository.observeSessions().collectLatest { sessions ->
                historyAdapter.submitList(sessions)
            }
        }
    }

    private fun setupHistorySwipeToDelete() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val session = historyAdapter.getItem(position)
                    historyAdapter.removeItem(position)
                    onHistoryDelete(session)
                }
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val holder = viewHolder as HistoryAdapter.ViewHolder
                val cardView = holder.cardView
                val maxSwipe = cardView.width * 0.4f
                val clampedDx = dX.coerceIn(0f, maxSwipe)
                cardView.translationX = clampedDx
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                val holder = viewHolder as HistoryAdapter.ViewHolder
                holder.cardView.translationX = 0f
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvHistory)
    }

    private fun onHistoryDelete(session: ChatSessionEntity) {
        lifecycleScope.launch {
            repository.deleteSession(session.sessionId)
            if (currentSession?.sessionId == session.sessionId) {
                currentSession = null
                messageAdapter.clear()
                updateEmptyState()
            }
        }
    }

    // ---------- UI helpers ----------
    private fun setLoadingState(loading: Boolean, message: String = "") {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvLoadingHint.visibility = if (loading && message.isNotBlank()) View.VISIBLE else View.GONE
        binding.tvLoadingHint.text = message
    }

    private fun scrollToBottom() {
        if (messageAdapter.itemCountValue > 0) {
            binding.recyclerView.post {
                // 直接钳制到列表最底部边缘。scrollBy(0, MAX_VALUE) 即使最后一条
                // 正在流式长高（高度超过视口）也能实时跟到其底部，避免新内容被遮挡。
                binding.recyclerView.scrollBy(0, Int.MAX_VALUE)
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                if (!storagePermissionRequested) {
                    storagePermissionRequested = true
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "需要存储权限以扫描模型文件", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            llmService.release()
        }
    }

    companion object {
        private const val PREFS_NAME = "main_prefs"
        private const val KEY_MODEL_NAME = "selected_model_name"
        private const val KEY_GGUF_PATH = "selected_model_gguf"
        private const val KEY_THINKING = "thinking_enabled"

        /** 由 CaptureActivity 回传的最终确认文本 */
        const val EXTRA_CONFIRMED_TEXT = "confirmed_text"

        fun newIntent(context: android.content.Context): android.content.Intent {
            return android.content.Intent(context, MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }
}