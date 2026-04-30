package io.touravatar.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.touravatar.data.ChatRepository
import io.touravatar.data.MessageEntity
import io.touravatar.data.Role
import io.touravatar.llm.ChatMessage
import io.touravatar.llm.LlmClient
import io.touravatar.llm.LlmStreamEvent
import io.touravatar.rag.RagRetriever
import io.touravatar.util.AppConfig
import io.touravatar.util.logE
import io.touravatar.util.logI
import io.touravatar.voice.AsrEvent
import io.touravatar.voice.AsrManager
import io.touravatar.voice.TtsEvent
import io.touravatar.voice.TtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Orchestrator: ASR → LLM (streaming) → TTS, while persisting messages to Room.
 *
 * The ViewModel owns the dialog state machine. UI observes [state],
 * [messages], and [avatarCommands] — UI never directly touches the
 * voice/llm subsystems.
 */
class ChatViewModel(
    private val repo: ChatRepository,
    private val asr: AsrManager,
    private val tts: TtsManager,
    private val llm: LlmClient,
    private val rag: RagRetriever,
) : ViewModel() {

    private val _state = MutableStateFlow<DialogState>(DialogState.Idle)
    val state: StateFlow<DialogState> = _state.asStateFlow()

    private val _conversationId = MutableStateFlow<Long?>(null)
    val conversationId: StateFlow<Long?> = _conversationId.asStateFlow()

    /**
     * Stream of messages for the current conversation. The UI binds a
     * RecyclerView to this flow.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: kotlinx.coroutines.flow.Flow<List<MessageEntity>> = _conversationId
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repo.observeMessages(id)
        }

    /** Avatar JS commands: setEmotion / setSpeaking / wave / idle. */
    private val _avatarCommands = MutableSharedFlow<AvatarCommand>(extraBufferCapacity = 16)
    val avatarCommands: SharedFlow<AvatarCommand> = _avatarCommands

    private var partialUserText: String = ""
    private var assistantStreamingId: Long? = null
    private var assistantBuffer: StringBuilder = StringBuilder()
    private var llmJob: Job? = null

    init {
        viewModelScope.launch { observeAsr() }
        viewModelScope.launch { observeTts() }
    }

    fun ensureConversation() {
        if (_conversationId.value != null) return
        viewModelScope.launch {
            val id = repo.createConversation(title = "新对话")
            _conversationId.value = id
            logI("Started conversation #$id")
        }
    }

    fun selectConversation(id: Long) {
        _conversationId.value = id
    }

    fun startListening() {
        if (_state.value is DialogState.Speaking) tts.interrupt()
        ensureConversation()
        partialUserText = ""
        _state.value = DialogState.Listening
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("listening"))
        asr.start()
    }

    fun stopListening() {
        asr.stop()
    }

    fun clearCurrentConversation() {
        llmJob?.cancel()
        tts.interrupt()
        _conversationId.value = null
        _state.value = DialogState.Idle
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("idle"))
    }

    private suspend fun observeAsr() {
        asr.events.collect { event ->
            when (event) {
                is AsrEvent.Started -> { /* UI status already shows listening */ }
                is AsrEvent.Partial -> partialUserText = event.text
                is AsrEvent.Final -> handleUserUtterance(event.text)
                is AsrEvent.Stopped -> { /* drain into final, no-op */ }
                is AsrEvent.Error -> _state.value = DialogState.Error(event.message)
            }
        }
    }

    private suspend fun observeTts() {
        tts.events.collect { event ->
            when (event) {
                is TtsEvent.SpeakingStart -> {
                    _state.value = DialogState.Speaking
                    _avatarCommands.tryEmit(AvatarCommand.SetSpeaking(true))
                }
                is TtsEvent.SpeakingEnd -> {
                    _avatarCommands.tryEmit(AvatarCommand.SetSpeaking(false))
                    if (llmJob?.isActive != true) {
                        _state.value = DialogState.Idle
                        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("idle"))
                    }
                }
                is TtsEvent.Error -> {
                    logE("TTS error: ${event.message}")
                }
            }
        }
    }

    private fun handleUserUtterance(text: String) {
        if (text.isBlank()) {
            _state.value = DialogState.Idle
            return
        }
        val convId = _conversationId.value ?: return
        _state.value = DialogState.Thinking
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("thinking"))

        llmJob?.cancel()
        llmJob = viewModelScope.launch {
            try {
                repo.appendMessage(convId, Role.USER, text)

                // Build prompt: system + (optional) RAG context + recent history + current user
                val rag = if (AppConfig.ragEnabled) rag.retrieve(text, topK = 3) else emptyList()
                val ragBlock = if (rag.isNotEmpty()) {
                    "\n\n[背景资料]\n" + rag.joinToString("\n") { "· ${it.title}：${it.text}" }
                } else ""

                val history = repo.listMessages(convId)
                    .takeLast(AppConfig.contextWindow)
                    .map { ChatMessage(it.role.name.lowercase(), it.content) }

                val request = buildList {
                    add(ChatMessage("system", AppConfig.systemPrompt + ragBlock))
                    addAll(history)
                }

                // Persist a streaming assistant message we update as tokens arrive.
                assistantBuffer = StringBuilder()
                assistantStreamingId = repo.appendMessage(convId, Role.ASSISTANT, "")

                var lastPersistedLen = 0
                llm.streamChat(request).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TokenDelta -> {
                            assistantBuffer.append(event.delta)
                            tts.feed(event.delta)
                            // Persist progressively so the UI sees streaming updates.
                            // Throttle: only flush every 16 chars to avoid DB churn.
                            val streamingId = assistantStreamingId
                            if (streamingId != null && assistantBuffer.length - lastPersistedLen >= 16) {
                                repo.updateMessageContent(streamingId, assistantBuffer.toString())
                                lastPersistedLen = assistantBuffer.length
                            }
                        }
                        is LlmStreamEvent.Done -> {
                            tts.flush()
                            // Final flush of the assistant content.
                            val streamingId = assistantStreamingId
                            if (streamingId != null) {
                                repo.updateMessageContent(streamingId, assistantBuffer.toString())
                            }
                            // Title the conversation from the first user line if still default.
                            repo.renameToFirstUserMessage(convId)
                        }
                        is LlmStreamEvent.Error -> {
                            _state.value = DialogState.Error(event.message)
                            _avatarCommands.tryEmit(AvatarCommand.SetEmotion("error"))
                        }
                    }
                }
            } catch (e: Exception) {
                logE("LLM pipeline failed", e)
                _state.value = DialogState.Error(e.message ?: "unknown")
            }
        }
    }
}

sealed class AvatarCommand {
    data class SetEmotion(val name: String) : AvatarCommand()
    data class SetSpeaking(val active: Boolean) : AvatarCommand()
    data object Wave : AvatarCommand()
}
