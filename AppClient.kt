package com.app.client

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Content delivery client. See INTEGRATION.md for setup. */
class AppClient(
    private val context: Context,
    private val endpoint: String,
    private val path: String = "/init",
    private val authToken: String = "",
    private val cloudProjectNumber: Long = 0L,
    private val enableIntegrity: Boolean = true,
    private val enableOnboardingGuard: Boolean = true,
) {
    companion object {
        private const val PREFS_NAME = "session_state"
        private const val KEY_INSTANCE_ID = "instance_id"
        private const val KEY_LAST_RESOLVE = "last_resolve_ts"
        private const val ONBOARDING_TTL_MS = 24L * 60L * 60L * 1000L
        private const val INTEGRITY_TIMEOUT_MS = 8_000L

        private val NETWORK_PINS = mutableMapOf<String, List<String>>()
        @Volatile
        private var cachedProvider: StandardIntegrityTokenProvider? = null
        private val providerMutex = Mutex()

        @JvmStatic
        fun addPins(host: String, pins: List<String>) {
            NETWORK_PINS[host] = pins
        }
    }

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        try {
            val host = endpoint.toHttpUrl().host
            val pins = NETWORK_PINS[host]
            if (!pins.isNullOrEmpty()) {
                val pinnerBuilder = CertificatePinner.Builder()
                for (pin in pins) pinnerBuilder.add(host, pin)
                builder.certificatePinner(pinnerBuilder.build())
            }
        } catch (_: Exception) { /* skip pinning if host parse fails */ }

        builder.build()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val instanceId: String
        get() {
            val existing = prefs.getString(KEY_INSTANCE_ID, null)
            if (existing != null) return existing
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTANCE_ID, id).apply()
            return id
        }

    private val appId: String get() = context.packageName

    private val locale: String get() = Locale.getDefault().language.ifBlank { "en" }

    private val timezone: String get() = TimeZone.getDefault().id

    private val appVersion: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }

    /** Returns content URL or empty string. Caller shows native UI on empty. */
    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        try {
            if (enableOnboardingGuard) {
                val last = prefs.getLong(KEY_LAST_RESOLVE, 0L)
                if (last != 0L && System.currentTimeMillis() - last < ONBOARDING_TTL_MS) {
                    return@withContext ""
                }
            }

            val token = if (enableIntegrity) requestIntegrityToken() else null

            val url = endpoint.toHttpUrl().newBuilder()
                .encodedPath(path)
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .post(EMPTY_BODY)
                .header("X-App-Id", appId)
                .header("X-Instance-Id", instanceId)
                .header("X-App-Version", appVersion)
                .header("X-Locale", locale)
                .header("X-Tz", timezone)
                .header("X-Ts", System.currentTimeMillis().toString())

            if (authToken.isNotEmpty()) {
                requestBuilder.header("X-Sid", authToken)
            }
            if (!token.isNullOrEmpty()) {
                requestBuilder.header("X-Integrity-Token", token)
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (response.code != 200) return@withContext ""
            val body = response.body?.string().orEmpty()
            val target = runCatching {
                JSONObject(body).optString("url", "")
            }.getOrDefault("")

            if (!target.startsWith("https://")) return@withContext ""

            if (enableOnboardingGuard) {
                prefs.edit().putLong(KEY_LAST_RESOLVE, System.currentTimeMillis()).apply()
            }

            target
        } catch (_: Exception) {
            ""
        }
    }

    /** Resets TTL stamp. Debug only. */
    fun resetOnboardingForTesting() {
        prefs.edit().remove(KEY_LAST_RESOLVE).apply()
    }

    private suspend fun requestIntegrityToken(): String? {
        if (cloudProjectNumber == 0L) return null
        return withTimeoutOrNull(INTEGRITY_TIMEOUT_MS) {
            try {
                val provider = obtainTokenProvider() ?: return@withTimeoutOrNull null
                val requestHash = sha256Hex(UUID.randomUUID().toString())
                val request = StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build()
                awaitTask(provider.request(request)).token()
            } catch (_: Exception) {
                cachedProvider = null
                null
            }
        }
    }

    private suspend fun obtainTokenProvider(): StandardIntegrityTokenProvider? {
        cachedProvider?.let { return it }
        return providerMutex.withLock {
            cachedProvider?.let { return it }
            try {
                val manager = IntegrityManagerFactory.createStandard(context)
                val prepareRequest = PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build()
                val provider = awaitTask(manager.prepareIntegrityToken(prepareRequest))
                cachedProvider = provider
                provider
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

private val EMPTY_BODY = "".toRequestBody("application/octet-stream".toMediaType())
