package io.touravatar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.touravatar.chat.AvatarCommand
import io.touravatar.chat.ChatViewModel
import io.touravatar.chat.DialogState
import io.touravatar.data.AppDatabase
import io.touravatar.data.ChatRepository
import io.touravatar.databinding.ActivityMainBinding
import io.touravatar.llm.OpenAiCompatibleClient
import io.touravatar.rag.InMemoryRagRetriever
import io.touravatar.ui.AvatarBridge
import io.touravatar.ui.ChatAdapter
import io.touravatar.voice.AsrManager
import io.touravatar.voice.SherpaAsrManager
import io.touravatar.voice.SherpaModelLayout
import io.touravatar.voice.SherpaTtsManager
import io.touravatar.voice.StubAsrManager
import io.touravatar.voice.StubTtsManager
import io.touravatar.voice.TtsManager
import io.touravatar.util.logI
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var avatarBridge: AvatarBridge
    private lateinit var chatAdapter: ChatAdapter

    private val viewModel: ChatViewModel by viewModels {
        val repo = ChatRepository(AppDatabase.get(applicationContext))
        val asr: AsrManager = if (SherpaModelLayout.asrAvailable(applicationContext)) {
            logI("Using SherpaAsrManager (model files found)")
            SherpaAsrManager(applicationContext)
        } else {
            logI("Using StubAsrManager (no ASR model — adb push models to ${SherpaModelLayout.asrDir(applicationContext)})")
            StubAsrManager()
        }
        val tts: TtsManager = if (SherpaModelLayout.ttsAvailable(applicationContext)) {
            logI("Using SherpaTtsManager (model files found)")
            SherpaTtsManager(applicationContext)
        } else {
            logI("Using StubTtsManager (no TTS model — adb push models to ${SherpaModelLayout.ttsDir(applicationContext)})")
            StubTtsManager()
        }
        ChatVmFactory(
            ChatViewModel(
                repo = repo,
                asr = asr,
                tts = tts,
                llm = OpenAiCompatibleClient(),
                rag = InMemoryRagRetriever(),
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAvatar()
        setupChatList()
        setupTopBar()
        setupPtt()
        observeViewModel()
        ensureMicPermission()
        viewModel.ensureConversation()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAvatar() {
        val webView = binding.avatarWebView
        // Serve bundled assets via virtual https origin so ES module imports
        // (importmap → CDN) work — file:// origin is "null" and CORS-blocked.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        webView.setBackgroundColor(0)
        avatarBridge = AvatarBridge(webView)
        webView.addJavascriptInterface(avatarBridge, "TourAvatarBridge")
        webView.loadUrl("https://appassets.androidplatform.net/assets/avatar/index.html")
    }

    private fun setupChatList() {
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
    }

    private fun setupTopBar() {
        binding.topBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.action_clear -> {
                    viewModel.clearCurrentConversation()
                    viewModel.ensureConversation()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPtt() {
        binding.pttButton.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    viewModel.startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    viewModel.stopListening()
                    true
                }
                else -> false
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.statusBar.text = when (state) {
                            DialogState.Idle -> getString(R.string.status_idle)
                            DialogState.Listening -> getString(R.string.status_listening)
                            DialogState.Thinking -> getString(R.string.status_thinking)
                            DialogState.Speaking -> getString(R.string.status_speaking)
                            is DialogState.Error -> getString(R.string.status_error, state.message)
                        }
                    }
                }

                launch {
                    viewModel.messages.collect { msgs ->
                        chatAdapter.submitList(msgs.filter { it.role.name != "SYSTEM" }) {
                            val last = chatAdapter.itemCount - 1
                            if (last >= 0) binding.chatRecyclerView.scrollToPosition(last)
                        }
                    }
                }

                launch {
                    viewModel.avatarCommands.collect { cmd ->
                        when (cmd) {
                            is AvatarCommand.SetEmotion -> avatarBridge.setEmotion(cmd.name)
                            is AvatarCommand.SetSpeaking -> avatarBridge.setSpeaking(cmd.active)
                            is AvatarCommand.Wave -> avatarBridge.wave()
                        }
                    }
                }
            }
        }
    }

    private fun ensureMicPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }
    }
}

private class ChatVmFactory(private val vm: ChatViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
}
