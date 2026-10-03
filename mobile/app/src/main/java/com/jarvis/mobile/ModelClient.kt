package com.jarvis.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The single next action the model wants us to take. */
data class AgentAction(
    val action: String,               // tap | longpress | type | launch | swipe | back | home | recents | wait | done
    val x: Int = 0, val y: Int = 0,
    val x2: Int = 0, val y2: Int = 0,
    val text: String = "",
    val app: String = "",
    val reason: String = ""
)

/**
 * Talks to a vision model to decide each step. Two providers, same request shape:
 *   • openrouter — https://openrouter.ai/api/v1/chat/completions (any cheap VLM)
 *   • gemini     — Google's OpenAI-compatible endpoint (generativelanguage…/openai/)
 * You pick provider + key + model in the app.
 */
object ModelClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

    private val SYSTEM = """
        You are JARVIS, an agent controlling an Android phone. You are shown a GOAL,
        the current SCREEN ELEMENTS (text with centre x,y and tap flags) and a screenshot.
        Decide the SINGLE next action to progress the goal. Reply with ONLY a compact JSON
        object, no prose, no markdown:
        {"action":"tap|longpress|type|launch|swipe|back|home|recents|wait|done",
         "x":int,"y":int,"x2":int,"y2":int,"text":"...","app":"app name","reason":"short why"}
        Rules:
        - Prefer tapping an element by its centre coordinates from SCREEN ELEMENTS.
        - Use "launch" with an app name to open an app. Use "type" only when a field is focused
          (tap it first). Use "swipe" to scroll (give x1,y1,x2,y2).
        - Dismiss popups/permission dialogs (tap Allow/OK/close, or "back").
        - If you already did the same thing and nothing changed, try a DIFFERENT approach.
        - When the goal is fully achieved, return action "done".
        - Never invent coordinates outside the screen.
    """.trimIndent()

    fun decide(
        provider: String,
        apiKey: String,
        model: String,
        goal: String,
        screenshotB64: String?,
        screenSummary: String,
        history: String
    ): AgentAction {
        val url = if (provider.equals("gemini", true)) GEMINI_URL else OPENROUTER_URL
        val userText = buildString {
            append("GOAL: ").append(goal).append('\n')
            if (history.isNotBlank()) append("HISTORY: ").append(history).append('\n')
            append("SCREEN ELEMENTS (centre x,y):\n").append(screenSummary.ifBlank { "(none readable)" })
        }
        val parts = JSONArray()
        parts.put(JSONObject().apply { put("type", "text"); put("text", userText) })
        if (screenshotB64 != null) {
            parts.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$screenshotB64"))
            })
        }
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", SYSTEM) })
            put(JSONObject().apply { put("role", "user"); put("content", parts) })
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 300)
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val s = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return AgentAction("wait", reason = "HTTP ${resp.code}: ${s.take(160)}")
            }
            val content = try {
                JSONObject(s).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
            } catch (e: Exception) { "" }
            return parse(content)
        }
    }

    private fun parse(raw: String): AgentAction {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return AgentAction("wait", reason = "no JSON: ${raw.take(120)}")
        return try {
            val o = JSONObject(cleaned.substring(start, end + 1))
            AgentAction(
                action = o.optString("action", "wait").ifBlank { "wait" },
                x = o.optInt("x"), y = o.optInt("y"),
                x2 = o.optInt("x2"), y2 = o.optInt("y2"),
                text = o.optString("text"),
                app = o.optString("app"),
                reason = o.optString("reason")
            )
        } catch (e: Exception) {
            AgentAction("wait", reason = "bad JSON: ${e.message}")
        }
    }
}
