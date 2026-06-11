package com.app.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AppClient(private val endpoint: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$endpoint/init")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (body.startsWith("{")) {
                JSONObject(body).optString("url", "")
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
