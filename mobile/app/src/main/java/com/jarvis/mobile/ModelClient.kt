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
 * Talks to a vision model to decide each step, and to answer questions. Provider-agnostic:
 * pick a provider id (see [Providers]) + key + model. Handles both OpenAI-compatible APIs and
 * Anthropic's Messages API.
 */
object ModelClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

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

    private val LIVE_SYSTEM = """
        You are JARVIS, a witty, concise voice assistant living on an Android phone. The
        user is talking to you right now. You are given SCREEN TEXT (readable on-screen
        elements) and sometimes a CAMERA image of what they're showing you. Answer in 1-3
        short spoken sentences — you are being read aloud, so no markdown, lists, or emoji.
        If they show you something in the camera, describe/identify it helpfully. If you
        can't see something, say so plainly. Be warm and a little like Tony Stark's JARVIS.
    """.trimIndent()

    /** Decide the single next device action for a goal. */
    fun decide(
        providerId: String,
        apiKey: String,
        model: String,
        goal: String,
        screenshotB64: String?,
        screenSummary: String,
        history: String,
        extraImages: List<String> = emptyList(),
        pluginContext: String? = null
    ): AgentAction {
        val p = Providers.byId(providerId)
        val userText = buildString {
            append("GOAL: ").append(goal).append('\n')
            if (history.isNotBlank()) append("HISTORY: ").append(history).append('\n')
            if (!pluginContext.isNullOrBlank()) append("PLUGINS: ").append(pluginContext).append('\n')
            append("SCREEN ELEMENTS (centre x,y):\n").append(screenSummary.ifBlank { "(none readable)" })
        }
        val images = if (p.vision) buildList { screenshotB64?.let { add(it) }; addAll(extraImages) } else emptyList()
        val raw = complete(p, apiKey, model, JarvisConfig.systemPrompt(SYSTEM), userText, images, 300)
        return parse(raw)
    }

    /** Conversational vision Q&A — returns plain text for JARVIS to speak. */
    fun ask(providerId: String, apiKey: String, model: String, prompt: String, images: List<String> = emptyList()): String {
        val p = Providers.byId(providerId)
        val imgs = if (p.vision) images else emptyList()
        val raw = complete(p, apiKey, model, JarvisConfig.systemPrompt(LIVE_SYSTEM), prompt, imgs, 400)
        if (raw.startsWith("[HTTP")) return "I couldn't reach my brain — check the key and connection."
        return raw.ifBlank { "I didn't catch that." }
    }

    /** One request/response across API styles. Returns assistant text, or an "[HTTP …]" error. */
    private fun complete(
        p: Provider, key: String, model: String,
        system: String, userText: String, images: List<String>, maxTokens: Int
    ): String {
        val isAnthropic = p.apiStyle == ApiStyle.ANTHROPIC
        val body: JSONObject
        if (isAnthropic) {
            val content = JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", userText))
                for (b64 in images) put(JSONObject().put("type", "image").put(
                    "source", JSONObject().put("type", "base64")
                        .put("media_type", "image/jpeg").put("data", b64)))
            }
            body = JSONObject().apply {
                put("model", model); put("max_tokens", maxTokens); put("system", system)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            }
        } else {
            val parts = JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", userText))
                for (b64 in images) put(JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            }
            body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", parts))
                })
                put("temperature", 0.2); put("max_tokens", maxTokens)
            }
        }
        val req = Request.Builder().url(p.url).apply {
            if (isAnthropic) header("x-api-key", key) else header("Authorization", "Bearer $key")
            p.extraHeaders.forEach { (k, v) -> header(k, v) }
            post(body.toString().toRequestBody(JSON))
        }.build()
        client.newCall(req).execute().use { resp ->
            val s = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return "[HTTP ${resp.code}] ${s.take(160)}"
            return if (isAnthropic) parseAnthropic(s) else parseOpenAI(s)
        }
    }

    private fun parseOpenAI(s: String): String = try {
        JSONObject(s).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
    } catch (e: Exception) { "" }

    private fun parseAnthropic(s: String): String = try {
        val arr = JSONObject(s).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        sb.toString()
    } catch (e: Exception) { "" }

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
