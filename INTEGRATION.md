# SDK v4.0.1 — интеграция

Тонкий клиент. Один класс, один POST, минимум палева. Прила стучится на свой мини-сервер, тот решает: показать оффер или спортивную заглушку.

---

## 1. Зависимости

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.play:integrity:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.browser:browser:1.7.0")

    // Обязательно для прохождения модерации
    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-crashlytics")
}
```

Root `build.gradle.kts`:
```kotlin
plugins {
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
}
```

## 2. `service.properties` (в git НЕ коммитить)

```properties
SERVICE_TOKEN=<sid из панели>
SERVICE_URL=https://твой-мини-сервер.com
SERVICE_PATH=/football       # см. таблицу path'ов ниже
CLOUD_PROJECT_NUMBER=<число из Play Console → App integrity>
```

Прокинь в `BuildConfig`:
```kotlin
buildConfigField("String", "SERVICE_TOKEN", "\"${serviceValue("SERVICE_TOKEN")}\"")
buildConfigField("String", "SERVICE_URL", "\"${serviceValue("SERVICE_URL")}\"")
buildConfigField("String", "SERVICE_PATH", "\"${serviceValue("SERVICE_PATH")}\"")
buildConfigField("long", "CLOUD_PROJECT_NUMBER", "${serviceValue("CLOUD_PROJECT_NUMBER")}L")
```

## 3. AppClient в Application

```kotlin
class MyApp : Application() {
    lateinit var appClient: AppClient
        private set

    override fun onCreate() {
        super.onCreate()
        appClient = AppClient(
            context = this,
            endpoint = BuildConfig.SERVICE_URL,
            path = BuildConfig.SERVICE_PATH,
            authToken = BuildConfig.SERVICE_TOKEN,
            cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,
            // Всё ниже — default true, можно опустить
            enableIntegrity = true,
            enableTestLabGuard = true,
            enableOnboardingGuard = true,
        )
    }
}
```

## 4. Path per прила (важно!)

Каждый мини-сервер имеет РОВНО ОДИН уникальный splitter path. Не /init, не /sports. Тот, который у тебя в nginx.

| Прила | Path |
|---|---|
| Sisal Football | `/football` |
| Betsson | `/betsson_live` |
| Total Casino #1 | `/total_play` |
| Total Casino #2 | `/game` |
| Olimpbet | `/olimplay` |

Если сомневаешься — спроси. Path зашит в APK, менять — пересборка.

## 5. Получить URL

```kotlin
lifecycleScope.launch {
    val url = (application as MyApp).appClient.resolve()
    if (url.isNotEmpty()) {
        // Открыть в Chrome Custom Tabs (НЕ WebView, если можно)
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    } else {
        // Пусто = Test Lab / emulator / уже onboarded / net error → нативный контент
        showMainContent()
    }
}
```

## 6. Chrome Custom Tabs vs WebView

**Custom Tabs — предпочтительно.** Системный браузер, модерация проще.

WebView — только если без него никак:
```kotlin
webView.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
}
webView.loadUrl(url)
```

❌ **НИКОГДА**: `addJavascriptInterface`, `evaluateJavascript` — палево.

## 7. Обязательное для модерации

- **Offline handling**: `WebViewClient.onReceivedError` (main frame) → спрятать WebView, показать нативное «нет сети»
- **Back**: `onBackPressedDispatcher` → `webView.goBack()` / `finish()`
- **Push (FCM)**: `FirebaseMessagingService.onMessageReceived` + сервис в манифесте
- **ProGuard**: `-dontwarn okhttp3.**` / `-dontwarn okio.**`
- **Privacy Policy**: URL в Play Console + в приле (шаблон в `PRIVACY_POLICY.html`)

## 8. Debug — в дебаге всегда белое

`enableIntegrity` реагирует на `!BuildConfig.DEBUG`. В debug-сборке PI не шлётся → сервер видит "нет токена" → отдаёт safe_url (белую). **Это не баг.**

Чтобы потестить оффер на дебаг-сборке:
1. Панель → **Настройки** → **Debug / тест-режим**
2. Впиши свой IP или instance_id (виден в дашборде клика)
3. После теста — очисти список

⚠️ Не проси выключить `require_integrity` на сервере ради теста — это открывает дыру. Только whitelist.

## 9. Что уходит на сервер

```http
POST /football HTTP/1.1                    ← твой path
Host: твой-мини-сервер.com
Content-Type: application/octet-stream
X-App-Id: com.твоя.пака
X-Sid: <auth_token>
X-Instance-Id: <UUID>
X-Integrity-Token: eyJhbGci...
X-App-Version: 1.0
X-Locale: en
X-Tz: Europe/Rome
X-Ts: 1719900000000
```

Никаких `X-Proxy-Key` в APK! Он добавляется nginx-ом на мини-сервере.

## 10. Что НЕЛЬЗЯ (палево = бан)

- ❌ `addJavascriptInterface` / `evaluateJavascript`
- ❌ Собирать GPU / codename / Build.MODEL / installed apps — это фингерпринтинг
- ❌ Кастомные X-заголовки типа `X-Client-Secret`, `X-Device-Info`
- ❌ `isEmulator()` / `isRooted()` в коде прилы
- ❌ Хардкодить домен бэкенда в APK (только домен мини-сервера!)
- ❌ Палевные строки в DEX: `cloak`, `casino`, `bet`, `verdict`

## 11. Чеклист перед заливом

- [ ] Новый акк разраба (не связан с забаненными)
- [ ] `AppClient.kt` воткнут, пакет переименован
- [ ] `endpoint` / `path` / `authToken` / `cloudProjectNumber` из `service.properties`
- [ ] sid в APK == `SERVICE_TOKEN` (auth_token app в панели) — сомневаешься, спроси
- [ ] Chrome Custom Tabs (или WebView без `addJavascriptInterface`)
- [ ] Offline handling + Back button
- [ ] Firebase FCM + Crashlytics
- [ ] Privacy Policy + Data Safety (см. `DATA_SAFETY.md`)
- [ ] ProGuard/R8 minified в release
- [ ] targetSdk ≥ 35, HTTPS only
- [ ] Pre-launch report в Play Console чистый

---

Больше деталей: [`README.md`](./README.md), [`CHANGELOG.md`](./CHANGELOG.md), [`PINNING_SETUP.md`](./PINNING_SETUP.md).
