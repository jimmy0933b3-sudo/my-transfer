package com.example.cameratranslator.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Sends recognized text to the authenticated Worker, never the Azure key. */
class CloudTranslationProvider {
    companion object {
        const val WORKER_URL = "https://camera-translator-api.jimmy0933.workers.dev"
    }

    suspend fun translate(text: String, source: String, target: String, token: String): String? =
        withContext(Dispatchers.IO) {
            if (token.length < 32 || text.isBlank() || text.codePointCount(0, text.length) > 1500) {
                return@withContext null
            }
            var connection: HttpsURLConnection? = null
            try {
                connection = URL(WORKER_URL + "/translate").openConnection() as HttpsURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 4000
                connection.readTimeout = 9000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                val payload = JSONObject()
                    .put("text", text)
                    .put("from", source)
                    .put("to", target)
                    .toString()
                connection.outputStream.use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w("CameraTranslator", "Cloud translation HTTP " + code)
                    return@withContext null
                }
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                JSONObject(response).optString("text").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.w("CameraTranslator", "Cloud translation unavailable", e)
                null
            } finally {
                connection?.disconnect()
            }
        }
}
