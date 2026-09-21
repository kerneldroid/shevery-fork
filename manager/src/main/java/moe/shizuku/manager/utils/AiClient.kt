package moe.shizuku.manager.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun httpError(code: Int, body: String): String {
        val detail = body.replace('\n', ' ').replace('\r', ' ').trim().take(160)
        val hint = when (code) {
            401 ->" (check API key)"
            403 ->" (check API key permissions)"
            429 ->" (rate limited)"
            in 500..599 ->" (provider server error)"
            else ->""
        }
        return if (detail.isBlank()) "HTTP $code$hint" else "HTTP $code$hint: $detail"
    }

    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String?,
        userPrompt: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("empty_key"))
        if (model.isBlank()) return@withContext Result.failure(IllegalStateException("empty_model"))
        try {
            val messages = JSONArray()
            if (!systemPrompt.isNullOrBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            messages.put(JSONObject().put("role", "user").put("content", userPrompt))
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val url = baseUrl.trimEnd('/') + "/chat/completions"
            val reqBuilder = Request.Builder().url(url).post(body)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
            // OpenRouter attribution (optional, harmless elsewhere)
            if (baseUrl.contains("openrouter.ai")) {
                reqBuilder.header("HTTP-Referer", "https://github.com/HmnDev-Tech/shevery")
                    .header("X-Title", "Shevery")
            }
            val resp = http.newCall(reqBuilder.build()).execute()
            resp.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    return@withContext Result.failure(RuntimeException(httpError(it.code, text)))
                }
                val content = JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Result.success(content.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val url = baseUrl.trimEnd('/') + "/models"
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                http.newCall(req).execute().use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        return@withContext Result.failure(RuntimeException(httpError(it.code, text)))
                    }
                    val ids = mutableListOf<String>()
                    val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
                    for (i in 0 until data.length()) {
                        data.optJSONObject(i)?.optString("id")?.takeIf { id -> id.isNotBlank() }?.let(ids::add)
                    }
                    Result.success(ids.sorted())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
