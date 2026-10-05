package com.jarvis.mobile

/** How a provider's chat API is shaped. Almost all are OpenAI-compatible; Anthropic differs. */
enum class ApiStyle { OPENAI, ANTHROPIC }

/**
 * One vision-model provider. JARVIS ships with a curated list so the user just picks one,
 * pastes a key, and goes. Add more by appending to [Providers.all].
 */
data class Provider(
    val id: String,
    val label: String,
    val url: String,
    val defaultModel: String,
    val apiStyle: ApiStyle = ApiStyle.OPENAI,
    val keyHint: String,
    val keyUrl: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    val vision: Boolean = true,                 // false = text-only (skip sending images)
    val suggestedModels: List<String> = emptyList()
)

object Providers {
    val all = listOf(
        Provider(
            "openrouter", "OpenRouter (many models)",
            "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4o-mini",
            keyHint = "sk-or-…", keyUrl = "https://openrouter.ai/keys",
            suggestedModels = listOf(
                "openai/gpt-4o-mini", "openai/gpt-4o",
                "anthropic/claude-3.5-sonnet", "google/gemini-flash-1.5"
            )
        ),
        Provider(
            "gemini", "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "gemini-2.0-flash", keyHint = "AIza…", keyUrl = "https://aistudio.google.com/app/apikey",
            suggestedModels = listOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro")
        ),
        Provider(
            "openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini",
            keyHint = "sk-…", keyUrl = "https://platform.openai.com/api-keys",
            suggestedModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")
        ),
        Provider(
            "nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1/chat/completions",
            "meta/llama-3.2-90b-vision-instruct", keyHint = "nvapi-…", keyUrl = "https://build.nvidia.com",
            suggestedModels = listOf(
                "meta/llama-3.2-90b-vision-instruct",
                "microsoft/phi-3.5-vision-instruct", "meta/llama-3.2-11b-vision-instruct"
            )
        ),
        Provider(
            "anthropic", "Anthropic Claude", "https://api.anthropic.com/v1/messages",
            "claude-3-5-haiku-latest", apiStyle = ApiStyle.ANTHROPIC, keyHint = "sk-ant-…",
            keyUrl = "https://console.anthropic.com/settings/keys",
            extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
            suggestedModels = listOf("claude-3-5-haiku-latest", "claude-3-5-sonnet-latest", "claude-3-opus-latest")
        ),
        Provider(
            "groq", "Groq (fast)", "https://api.groq.com/openai/v1/chat/completions",
            "llama-3.2-90b-vision-preview", keyHint = "gsk_…", keyUrl = "https://console.groq.com/keys",
            suggestedModels = listOf("llama-3.2-90b-vision-preview", "llama-3.2-11b-vision-preview")
        ),
        Provider(
            "together", "Together AI", "https://api.together.xyz/v1/chat/completions",
            "meta-llama/Llama-3.2-11b-Vision-Instruct-Turbo", keyHint = "paste key",
            keyUrl = "https://api.together.xyz/settings/api-keys",
            suggestedModels = listOf("meta-llama/Llama-3.2-90b-Vision-Instruct-Turbo", "Qwen/Qwen2-VL-72B-Instruct")
        ),
        Provider(
            "mistral", "Mistral", "https://api.mistral.ai/v1/chat/completions", "pixtral-12b-2409",
            keyHint = "paste key", keyUrl = "https://console.mistral.ai/api-keys",
            suggestedModels = listOf("pixtral-12b-2409", "pixtral-large-latest")
        ),
        Provider(
            "deepseek", "DeepSeek (text-only)", "https://api.deepseek.com/v1/chat/completions",
            "deepseek-chat", vision = false, keyHint = "sk-…", keyUrl = "https://platform.deepseek.com/api_keys",
            suggestedModels = listOf("deepseek-chat", "deepseek-reasoner")
        )
    )

    fun byId(id: String?): Provider = all.firstOrNull { it.id.equals(id, true) } ?: all[0]
    fun indexOf(id: String?): Int = all.indexOfFirst { it.id.equals(id, true) }.let { if (it < 0) 0 else it }
}
