package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Cfg {
    const val BASE = "https://api.deepseek.com/v1"
    const val MODEL = "deepseek-chat"
    const val LANG = "ar-EG"
}

object Bus {
    @Volatile var listener: ((String) -> Unit)? = null
    fun log(s: String) {
        android.util.Log.d("Jarvis", s)
        listener?.invoke(s)
    }
}

object LlmClient {
    /** OpenAI-compatible /chat/completions call. Returns the assistant message object. */
    fun chat(base: String, key: String, model: String, messages: JSONArray, tools: JSONArray): JSONObject {
        val c = URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        val body = JSONObject().put("model", model).put("messages", messages).put("tools", tools)
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(200)}")
        return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    }
}
