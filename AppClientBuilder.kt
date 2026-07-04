package com.app.client

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Optional helper для fetching endpoint URL из Firebase Remote Config
 * вместо hardcode в DEX.
 *
 * Зачем: static APK analysis декомпилятором (jadx, apktool, MobSF) сразу видит
 * hardcoded backend URL в DEX strings → domain попадает в reputation blacklist
 * → бан всех apps использующих этот backend.
 *
 * Firebase Remote Config:
 * - Fetch endpoint URL at first launch → cache locally
 * - При смене backend просто publish new value в Firebase Console — не нужно rebuild APK
 * - Firebase = Google trusted infra → no suspicion в static scan
 *
 * ## Требования
 *
 * 1. Firebase project настроен для APK (google-services.json)
 * 2. Firebase Remote Config dependency в build.gradle:
 *    ```
 *    implementation("com.google.firebase:firebase-config:22.0.0")
 *    ```
 * 3. Remote Config parameter с ключом (по умолчанию `app_client_endpoint`)
 *
 * ## Использование
 *
 * ```kotlin
 * // MyApp.onCreate() или где-то до первого AppClient.resolve():
 * // ВАЖНО: вызывать НЕ в main thread — blocking call с timeout.
 * lifecycleScope.launch(Dispatchers.IO) {
 *     val endpoint = AppClientBuilder.fetchEndpointOrFallback(
 *         context = applicationContext,
 *         remoteConfigKey = "app_client_endpoint",
 *         fallback = "https://your-mini-server.com",
 *     )
 *     val client = AppClient(
 *         context = applicationContext,
 *         endpoint = endpoint,
 *         authToken = BuildConfig.SERVICE_TOKEN,
 *         cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,
 *     )
 *     val url = client.resolve()
 * }
 * ```
 *
 * Reflection-based invocation, чтобы SDK не имел hard-dependency на Firebase.
 * Если Firebase не подключён в APK — просто вернёт fallback без exception.
 */
object AppClientBuilder {

    /**
     * Blocking call. Возвращает endpoint из Firebase Remote Config или fallback.
     * Timeout: 5 сек по умолчанию.
     */
    @JvmStatic
    @JvmOverloads
    fun fetchEndpointOrFallback(
        context: Context,
        remoteConfigKey: String = "app_client_endpoint",
        fallback: String,
        timeoutSeconds: Long = 5L,
        minimumFetchIntervalSeconds: Long = 3600L,
    ): String {
        return try {
            val remoteConfigCls = Class.forName("com.google.firebase.remoteconfig.FirebaseRemoteConfig")
            val getInstance = remoteConfigCls.getMethod("getInstance")
            val rc = getInstance.invoke(null)

            // Optional: apply minimum fetch interval settings (best-effort — if the API surface
            // has drifted between Firebase versions, we silently skip and use defaults).
            runCatching {
                val settingsCls = Class.forName("com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings")
                val settingsBuilder = settingsCls.getMethod("newBuilder").invoke(null)
                val builderCls = settingsBuilder.javaClass
                builderCls.getMethod("setMinimumFetchIntervalInSeconds", Long::class.javaPrimitiveType)
                    .invoke(settingsBuilder, minimumFetchIntervalSeconds)
                val settings = builderCls.getMethod("build").invoke(settingsBuilder)
                remoteConfigCls.getMethod("setConfigSettingsAsync", settingsCls).invoke(rc, settings)
            }

            // Fetch + activate — wait via Task's addOnCompleteListener + CountDownLatch.
            // No hard-dep на kotlinx-coroutines-play-services.
            val task = remoteConfigCls.getMethod("fetchAndActivate").invoke(rc)
            val taskCls = Class.forName("com.google.android.gms.tasks.Task")
            val listenerCls = Class.forName("com.google.android.gms.tasks.OnCompleteListener")
            val latch = CountDownLatch(1)
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerCls.classLoader,
                arrayOf(listenerCls),
            ) { _, _, _ ->
                latch.countDown()
                null
            }
            taskCls.getMethod("addOnCompleteListener", listenerCls).invoke(task, listener)
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) return fallback

            val value = remoteConfigCls.getMethod("getString", String::class.java)
                .invoke(rc, remoteConfigKey) as? String

            if (!value.isNullOrBlank() && value.startsWith("https://")) value else fallback
        } catch (_: Throwable) {
            // Firebase not on classpath, or fetch failed — fall back silently.
            fallback
        }
    }
}
