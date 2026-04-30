package io.touravatar.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** Ollama-specific: turn off "thinking" mode in reasoning-capable models
     *  (e.g. Gemma 4, qwq, deepseek-r1) so the response is direct content
     *  rather than long internal reasoning chunks. Ignored by non-Ollama servers. */
    val think: Boolean? = null,
)

@Serializable
data class ChatStreamChunk(
    val choices: List<ChatStreamChoice> = emptyList(),
)

@Serializable
data class ChatStreamChoice(
    val delta: ChatStreamDelta = ChatStreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatStreamDelta(
    val role: String? = null,
    val content: String? = null,
    /** Ollama returns reasoning chunks here for thinking-mode models;
     *  we keep it parsed so future revisions can surface it as a separate UI lane. */
    val reasoning: String? = null,
)
