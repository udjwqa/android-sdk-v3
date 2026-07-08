# SDK v4.0.2 — интеграция

Тонкий клиент. Один класс, один POST, минимум палева. Прила стучится на свой мини-сервер по пути `/sports`, nginx там прозрачно проксирует запрос в КЛО и возвращает JSON `{"url":"..."}`. Домен КЛО в APK не светится.

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
SERVICE_PATH=/sports         # путь зависит от типа мини-сервера (см. таблицу ниже)
CLOUD_PROJECT_NUMBER=<число из Play Console → App integrity>
```

> **⚠️ `SERVICE_PATH` зависит от типа мини-сервера:**
>
> | Тип сервера | Путь | Примеры |
> |---|---|---|
> | Тип A/C (nginx + КЛО-прокси) | `/sports` | Betclic, Stake, gesr, Sisal, LamDep, Snai, Betsson и др. |
> | Тип B (Next.js middleware без nginx) | `/init` | Unibet (`unisportapp.com`), NV Casino (`casualnvgameapi.com`) |
>
> Если не уверен — используй `/init` (работает на ВСЕХ серверах). `/sports` — только на серверах с nginx `location = /sports`.

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

## 4. Path

`SERVICE_PATH` зависит от типа мини-сервера:

| Тип | Путь | Как работает | Серверы |
|---|---|---|---|
| **A/C** (nginx + КЛО-прокси) | `/sports` | nginx `location = /sports` → проксирует POST в КЛО `/engine/init`, инжектит `X-Proxy-Key` | Betclic, Stake, gesr, Sisal, LamDep, Snai, Betsson и т.д. |
| **B** (Next.js middleware) | `/init` | middleware.ts matcher `/init` → fetch к КЛО, возвращает JSON | Unibet (`unisportapp.com`), NV Casino (`casualnvgameapi.com`) |

**Не уверен какой тип?** Используй `/init` — работает на **всех** серверах (Тип A/C тоже принимает `/init` через middleware fallback).

`SERVICE_URL` — брендовый домен мини-сервера (напр. `https://asportvalsisapp.com`). Доменом КЛО в APK **не светим**.

> ⚠️ Прила ДОЛЖНА быть с `direct_redirect=true` в панели — иначе КЛО для grey вернёт бонс на `api.threeamigosteam.com/engine/go` (светит домен). У direct_redirect КЛО отдаёт брендовый Keitaro-URL напрямую. У всех боевых прил это уже включено.

## 5. Получить URL

```kotlin
lifecycleScope.launch {
    val url = (application as MyApp).appClient.resolve()
    if (url.isNotEmpty()) {
        // Открыть в Chrome Custom Tabs (НЕ WebView, если можно)
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    } else {
        // Пусто = Test Lab / emulator / onboarded (<24ч назад) / net error → нативный контент
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
POST /sports HTTP/1.1                       ← путь из SERVICE_PATH (/sports или /init)
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

Ответ — всегда `200 application/json`:
```json
{"url":"https://брендовый-keitaro-домен/xxxxxx?clickid=<UUID>&geo=XX"}
```
SDK парсит `url`, открывает в Chrome CCT. Никаких 302, никаких `X-Proxy-Key` в APK — ключ инжектит nginx мини-сервера.

**Onboarding TTL 24ч:** после успешного resolve SDK молчит 24 часа (не долбит бэкенд каждый запуск — это палит Play Protect), затем guard пере-взводится (ежедневный повторный оффер — доход не режется).

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
