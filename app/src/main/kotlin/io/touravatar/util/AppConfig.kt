package io.touravatar.util

/**
 * Runtime configuration. In production these would be loaded from a settings
 * screen / SharedPreferences. For the MVP we keep them as overridable constants.
 */
object AppConfig {

    /** OpenAI-compatible base URL. Examples:
     *  - Ollama on LAN:    http://192.168.1.10:11434/v1
     *  - OpenAI:           https://api.openai.com/v1
     *  - 智谱:             https://open.bigmodel.cn/api/paas/v4
     *  - 百度文心:         https://qianfan.baidubce.com/v2
     */
    var llmBaseUrl: String = "http://10.0.2.2:11434/v1"

    /** Optional bearer token. Empty for local Ollama. */
    var llmApiKey: String = ""

    /** Model name. e.g. "gpt-4o-mini", "qwen2.5:7b", "glm-4-flash" */
    var llmModel: String = "qwen2.5:7b"

    /** Conversational system prompt for the tour-guide persona. */
    var systemPrompt: String = """
        你是一名专业的数字导览员，正在为美术馆 / 博物馆 / 旅游景点的访客服务。
        - 用温暖、亲切、富有文化感的语气回答
        - 先给一句话核心答案，再展开 2-3 句解释，避免冗长
        - 当游客提问超出展品/景点范围时，礼貌引导回主题
        - 必要时结合检索到的展品资料（在 [背景资料] 段落中提供）
        - 回答用中文，除非游客明显使用其他语言
    """.trimIndent()

    /** Whether to enable on-device RAG over museum/exhibit knowledge. */
    var ragEnabled: Boolean = true

    /** Max tokens to keep in conversation context window. */
    var contextWindow: Int = 16
}
