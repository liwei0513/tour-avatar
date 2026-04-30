package io.touravatar.chat

/**
 * High-level conversational state of the avatar.
 *
 *   IDLE  ──[user holds PTT]──▶ LISTENING
 *   LISTENING ──[user releases]──▶ THINKING
 *   THINKING ──[first LLM token]──▶ SPEAKING
 *   SPEAKING ──[stream end + TTS drained]──▶ IDLE
 *   * ──[error]──▶ ERROR ──[user dismisses]──▶ IDLE
 */
sealed class DialogState {
    data object Idle : DialogState()
    data object Listening : DialogState()
    data object Thinking : DialogState()
    data object Speaking : DialogState()
    data class Error(val message: String) : DialogState()
}
