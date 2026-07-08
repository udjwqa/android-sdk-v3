# SDK v4.0.3 — интеграция

Тонкий клиент. Один класс, один POST, минимум палева. Прила стучится на свой мини-сервер по **уникальному пути прилы** (тому, что заявлен в `assetlinks.json` и виден в её лендинге). Nginx там прозрачно проксирует запрос в КЛО и возвращает JSON `{"url":"..."}`. Домен КЛО в APK не светится.

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
SERVICE_PATH=/YOUR_DAL_PATH       # ← ровно тот, что в assetlinks.json и в URL лендинга
CLOUD_PROJECT_NUMBER=<число из Play Console → App integrity>
```

> ### ⚠️ Anti-ban правило — `SERVICE_PATH` обязан совпадать с DAL
>
> `SERVICE_PATH` **= path в `/.well-known/assetlinks.json`** на домене мини-сервера **= path вашего лендинга** (URL, который выдаёт клиент).
>
> Google Play Protect scanner кликает по URL из DAL. Если заявили `/foo`, а SDK бьёт на `/bar` → палево → бан.
>
> Примеры реальных прил:
>
> | Прила | Лендинг URL | `SERVICE_PATH` |
> |---|---|---|
> | Betsson (`com.mzourobv.motocross`) | `https://sportfootballapi.com/betsson_live` | `/betsson_live` |
> | Total Casino #1 | `https://supercastotalgame.com/total_play` | `/total_play` |
> | Total Casino #2 | `https://totalsupergame.com/game` | `/game` |
> | SNAI | `https://footballapisnai.com/sports` | `/sports` |
> | Betsson #2 | `https://sportapiplay.com/stats` | `/stats` |
>
> Валидацию сделает панель КЛО во вкладке **«Пути / DAL»** — кнопка `DAL check` тянет `assetlinks.json`, сверяет SHA-256, проверяет что path отдаёт HTML-лендинг; кнопка `Health check` делает реальный SDK POST и проверяет что мини-сервер вернул `{"url":"..."}`.

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
            enableOnboardingGuard = true,
        )
    }
}
```

## 4. Path — per app из DAL

`SERVICE_PATH` = точный путь из `/.well-known/assetlinks.json` на домене мини-сервера. Это тот же path, что отдаёт браузерный лендинг (клиент прислал ссылку типа `https://<домен>/<path>` — этот `<path>` и есть `SERVICE_PATH`).

**Как работает под капотом (одинаково для всех прил):**

- **Браузер** заходит на `https://<домен>/<path>` → nginx `location = /<path>` отдаёт Next.js лендинг (белая спортивная страница). Google scanner при DAL-проверке видит именно этот безобидный лендинг.
- **SDK** (okhttp UA) POST-ит на тот же `https://<домен>/<path>` → nginx детектит okhttp через `if $http_user_agent ~* okhttp { return 418 }` → error_page 418 → `@sdk_proxy` → `rewrite ^ /init break` → проксирует в mini-КЛО на `127.0.0.1:8100`.

Т.е. один и тот же URL обслуживает **и** лендинг **и** SDK — split по User-Agent на уровне nginx. SDK не видит `/init`, КЛО не видит клиентский path — оба видят то, что им надо. Google видит согласованный DAL: заявили path, path работает, SHA-256 совпадает.

**Проверка DAL перед релизом:** панель КЛО → вкладка **«Пути / DAL»** → кнопка `DAL check`. Всё зелёное = готово.

`SERVICE_URL` — брендовый домен мини-сервера (напр. `https://sportfootballapi.com`). Доменом КЛО в APK **не светим**.

> ⚠️ Прила ДОЛЖНА быть с `direct_redirect=true` в панели — иначе КЛО для grey вернёт бонс на `api.threeamigosteam.com/engine/go` (светит домен). У direct_redirect КЛО отдаёт брендовый Keitaro-URL напрямую. У всех боевых прил это уже включено.

## 5. Получить URL

```kotlin
lifecycleScope.launch {
    val url = (application as MyApp).appClient.resolve()
    if (url.isNotEmpty()) {
        // Открыть в Chrome Custom Tabs (НЕ WebView, если можно)
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    } else {
        // Пусто = onboarded (<24ч назад) / net error → нативный контент
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
POST /<DAL_path> HTTP/1.1                   ← путь из SERVICE_PATH (см. секцию 4)
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
