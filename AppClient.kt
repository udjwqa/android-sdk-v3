package com.app.client

import android.content.Context
import android.content.SharedPreferences
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class AppClient(
    private val context: Context,
    private val endpoint: String,
    private val path: String = "/football",
    private val authToken: String = "",
    private val enableIntegrity: Boolean = true,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_client", Context.MODE_PRIVATE)

    private val instanceId: String
        get() {
            val existing = prefs.getString("instance_id", null)
            if (existing != null) return existing
            val id = UUID.randomUUID().toString()
            prefs.edit().putString("instance_id", id).apply()
            return id
        }

    private val appId: String get() = context.packageName

    private val locale: String get() = Locale.getDefault().language

    private val appVersion: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }

    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        try {
            val token = if (enableIntegrity) requestIntegrityToken() else null

            val url = endpoint.toHttpUrl().newBuilder()
                .encodedPath(path)
                .addQueryParameter("app_id", appId)
                .addQueryParameter("locale", locale)
                .addQueryParameter("app_version", appVersion)
                .addQueryParameter("instance_id", instanceId)
                .apply {
                    if (authToken.isNotEmpty()) addQueryParameter("sid", authToken)
                    if (token != null) addQueryParameter("integrity_token", token)
                }
                .build()

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            val location = response.header("Location")
            if (response.code in 301..302 && !location.isNullOrBlank()) {
                return@withContext location
            }

            val body = response.body?.string() ?: ""
            if (body.startsWith("{")) {
                JSONObject(body).optString("url", "")
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun requestIntegrityToken(): String? {
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    val manager = IntegrityManagerFactory.create(context)
                    val request = IntegrityTokenRequest.builder()
                        .setNonce(UUID.randomUUID().toString().replace("-", ""))
                        .build()
                    manager.requestIntegrityToken(request)
                        .addOnSuccessListener { cont.resume(it.token()) }
                        .addOnFailureListener { cont.resume(null) }
                } catch (_: Exception) {
                    cont.resume(null)
                }
            }
        }
    }
}
