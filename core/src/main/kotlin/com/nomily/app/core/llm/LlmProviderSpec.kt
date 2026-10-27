package com.nomily.app.core.llm

/**
 * Where a provider's settings live on [com.nomily.app.core.config.AppConfig.LlmProviders]: a fixed address plus a key
 * (`openai` / `claude` / `gemini`), a configurable address (`openRouter` / `ollama` / `custom`), or the [presets]
 * dictionary (`qwen` / `deepseek` / `doubao`).
 */
enum class LlmStorage { KEYED, ENDPOINT, PRESET }

/**
 * One row of the provider list. The settings page, the primary-provider dropdown and every error message read this
 * table, so they cannot disagree about which providers exist or what they are called.
 */
data class LlmProviderSpec(
    val id: String,
    val name: String,
    /** 1–2 glyphs for the tile when there is no brand artwork. Never translated. */
    val mark: String,
    val storage: LlmStorage,
    /** Pre-filled address; empty when the user has to supply one (`custom`). */
    val defaultEndpoint: String,
    /** `false` where the API fixes the address (OpenAI / Claude / Gemini). */
    val endpointEditable: Boolean,
    /** Ollama and Custom legitimately run without a key. */
    val keyRequired: Boolean,
    /** Shown before anything is fetched, and the fallback when the provider has no model-list endpoint. */
    val curatedModels: List<String>,
    /** Where the key comes from. */
    val console: String?,
    /** Provider-specific caveat. */
    val note: String?,
    /** Tile colour, 0xRRGGBB. */
    val tint: Int,
    /** Local or self-hosted: short probe timeout, small output cap. */
    val local: Boolean = false,
) {
    companion object {
        val CHATGPT = LlmProviderSpec(
            "openai", "ChatGPT", "GPT", LlmStorage.KEYED, "https://api.openai.com/v1", false, true,
            listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini", "o3", "o4-mini"),
            "platform.openai.com → API keys", null, 0x1A9E7D,
        )
        val CLAUDE = LlmProviderSpec(
            "claude", "Claude", "C", LlmStorage.KEYED, "https://api.anthropic.com/v1", false, true,
            listOf("claude-opus-4-0-20250514", "claude-sonnet-4-0-20250514", "claude-3-7-sonnet-20250219", "claude-3-5-haiku-20241022"),
            "console.anthropic.com → API keys", null, 0xD97857,
        )
        val GEMINI = LlmProviderSpec(
            "gemini", "Gemini", "G", LlmStorage.KEYED, "https://generativelanguage.googleapis.com/v1beta", false, true,
            listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"),
            "aistudio.google.com → API key", null, 0x4285F5,
        )
        val QWEN = LlmProviderSpec(
            "qwen", "通义千问", "通", LlmStorage.PRESET, "https://dashscope.aliyuncs.com/compatible-mode/v1", true, true,
            listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen-long", "qwen3-max", "qwen2.5-72b-instruct"),
            "bailian.console.aliyun.com → API-KEY", null, 0x6B5CFF,
        )
        val DEEPSEEK = LlmProviderSpec(
            "deepseek", "DeepSeek", "DS", LlmStorage.PRESET, "https://api.deepseek.com/v1", true, true,
            listOf("deepseek-chat", "deepseek-reasoner"),
            "platform.deepseek.com → API keys", null, 0x476BFA,
        )
        val DOUBAO = LlmProviderSpec(
            "doubao", "豆包 · 火山方舟", "豆", LlmStorage.PRESET, "https://ark.cn-beijing.volces.com/api/v3", true, true,
            listOf("doubao-seed-1-6-250615", "doubao-1-5-pro-32k-250115", "doubao-pro-32k-241215", "doubao-lite-128k-240428"),
            "console.volcengine.com/ark → API Key",
            "豆包由火山引擎方舟提供。模型一栏可以填模型名（doubao-…），也可以填推理接入点 ID（ep-… 开头，账号不能按名调用时用这种）。",
            0x2970D6,
        )
        val OPEN_ROUTER = LlmProviderSpec(
            "openRouter", "Open Router", "OR", LlmStorage.ENDPOINT, "https://openrouter.ai/api/v1", true, true,
            listOf("anthropic/claude-sonnet-4", "openai/gpt-4.1", "google/gemini-2.5-pro", "deepseek/deepseek-chat"),
            "openrouter.ai/keys", "一个 key 通吃各家模型；模型名要带厂牌前缀（anthropic/… 、openai/…）。", 0x5C5C70,
        )
        val OLLAMA = LlmProviderSpec(
            "ollama", "Ollama", "OL", LlmStorage.ENDPOINT, "http://localhost:11434", true, false,
            emptyList(), null, "跑在本机或局域网里的电脑上，不联网、不花钱。要填电脑的地址而不是名字。", 0x33333D, local = true,
        )
        val CUSTOM = LlmProviderSpec(
            "custom", "自定义接口", "＋", LlmStorage.ENDPOINT, "", true, false,
            emptyList(), null, "任何 OpenAI 兼容的 /v1 地址（中转、自建 vLLM、LM Studio 都算）。密钥可以留空。", 0x737885, local = true,
        )

        /** One flat list in the order someone is likely to reach for: big cloud APIs, Chinese providers, aggregator, local, catch-all. */
        val ALL = listOf(CHATGPT, CLAUDE, GEMINI, QWEN, DEEPSEEK, DOUBAO, OPEN_ROUTER, OLLAMA, CUSTOM)

        /** The providers stored in the `presets` dictionary, in fallback order. */
        val PRESETS = ALL.filter { it.storage == LlmStorage.PRESET }

        fun find(id: String?): LlmProviderSpec? = ALL.firstOrNull { it.id == id }
    }
}

/** Which of a provider's model ids can write a summary. */
object LlmModels {
    private val NON_CHAT_TOKENS = listOf(
        "embed", "whisper", "tts", "dall-e", "gpt-image", "moderation", "rerank",
        "realtime", "audio", "transcribe", "speech", "ocr", "aqa", "bge", "gte-",
        "text-similarity", "text-search", "image-",
        // "vision" and "-preview" stay out on purpose: gpt-4-vision-preview and friends are chat models.
    )

    fun isChatCapable(id: String): Boolean {
        val lower = id.lowercase()
        return NON_CHAT_TOKENS.none { lower.contains(it) }
    }

    fun chatCapable(ids: List<String>): List<String> = ids.filter(::isChatCapable)

    /** Latency band for a probe result, as an index into fast / normal / slow / very slow. */
    fun latencyBand(ms: Int): Int = when {
        ms < 800 -> 0
        ms < 2000 -> 1
        ms < 5000 -> 2
        else -> 3
    }
}
