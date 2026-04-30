package io.touravatar.voice

import kotlinx.coroutines.flow.Flow

/**
 * Streaming TTS contract. Implementations should support **sentence-level
 * incremental synthesis** so the avatar can begin speaking before the LLM
 * finishes generating the full reply.
 *
 * Typical flow:
 *   1. [feed] is called repeatedly with text chunks as the LLM streams tokens.
 *   2. The implementation segments incoming text into sentences (by punctuation)
 *      and synthesizes each sentence to PCM in a background coroutine.
 *   3. [events] emits SpeakingStart per sentence and SpeakingEnd when the
 *      audio queue drains.
 *   4. [flush] should be called when the LLM stream ends, to flush any
 *      partial trailing sentence.
 *   5. [interrupt] cancels playback (e.g. when user starts a new question).
 */
interface TtsManager {
    val events: Flow<TtsEvent>
    suspend fun init()
    fun feed(textChunk: String)
    fun flush()
    fun interrupt()
    fun release()
}

sealed class TtsEvent {
    data class SpeakingStart(val sentence: String) : TtsEvent()
    data object SpeakingEnd : TtsEvent()
    data class Error(val message: String, val cause: Throwable? = null) : TtsEvent()
}
