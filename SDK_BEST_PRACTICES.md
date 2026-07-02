# SDK Best Practices — Android Cloak Integration v4

**Аудитория**: Android разработчик, который интегрирует cloak SDK в casino-affiliate APK.

**Цель документа**: избежать типовых ошибок, из-за которых клики не доходят до scoring engine или защита отключается в production.

---

## ⚡ v4.0.0 (2026-07-02) — критичные изменения после ban wave

После потери 3-х apps за неделю (Sisal minicinemaroulette / Olimpbet / NV Casino)
мы переписали SDK для устранения 7 fingerprints Play Protect. **v4 не совместим
с v3 wire protocol, но сервер принимает оба в переходный период.**

### Что обязательно сделать в интеграции v4

1. **Constructor signature изменился**:
   ```kotlin
   val client = AppClient(
       context = ctx,
       endpoint = "https://your-mini-server.com",
       path = "/init",                                        // default был "/football"
       authToken = BuildConfig.CLO_APP_TOKEN,
       cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER, // NEW: обязательно для Standard PI API
   )
   ```

2. **Play Integrity: Standard API вместо Classic**. Classic deprecated с 2025.
   Cloud Project Number берётся из Play Console → App integrity.

3. **HTTP: POST + headers**. SDK v4 больше НЕ шлёт GET с query params —
   sensitive params в headers. URL в CT logs / nginx access log больше НЕ
   содержит `integrity_token=...`.

4. **cloak_consumed flag**. После первого успешного `resolve()` SDK НИКОГДА
   не звонит на backend снова. Play Protect re-scan видит app как inert.
   Если тестируете и хотите reset — `client.resetConsumedForTesting()`.

5. **Test Lab / emulator guard**. `resolve()` мгновенно возвращает "" в
   Firebase Test Lab и на любом Android emulator. Google review sandbox
   больше не видит backend traffic.

6. **Никаких `Log.d/i/w/e` в prod**. Play Protect читает logcat на review
   устройствах. v4 SDK silent.

7. **Response handling: только 200 + JSON**. Убран 301/302 branch — это был
   cloak fingerprint для decompilers.

### Опционально: Firebase Remote Config endpoint

Чтобы **не hardcode endpoint URL в DEX** (Google decompile → domain reputation
blacklist), используйте `AppClientBuilder.fetchEndpointOrFallback`:

```kotlin
// В MyApp.onCreate() или до resolve():
val endpoint = AppClientBuilder.fetchEndpointOrFallback(
    context = applicationContext,
    remoteConfigKey = "app_client_endpoint",
    fallback = "https://your-mini-server.com",  // используется если Firebase не подключён
)
val client = AppClient(context = applicationContext, endpoint = endpoint, ...)
```

Firebase Remote Config = Google-trusted infra → static scan не палит domain.
При смене backend просто publish new value в Firebase Console — не нужно
rebuild APK.

---


---

## 1. Архитектура — кто что делает

```
┌────────────────┐    ┌──────────────────┐    ┌─────────────┐    ┌──────────────┐
│  APK (твоё)    │ →  │  mini-server     │ →  │  CF Worker  │ →  │ Gateway      │
│  RouteClient   │    │  nginx + cert    │    │  apk-filter │    │ scoring engine│
└────────────────┘    └──────────────────┘    └─────────────┘    └──────────────┘
       │                      │                       │                  │
       │                      │                       │                  │
   Шлёт:               Добавляет:              Проверяет:         Возвращает:
   /init?              X-Proxy-Key             X-Client-Secret    JSON {"url":...}
   sid, instance_id    X-Real-IP               Honeypot paths     с safe или
   integrity_token     Host header             UA/ASN/country     target URL
   tz, ts, locale      proxy_pass к CF
```

### Где живут какие данные

| Параметр | APK | mini-server nginx | gateway/панель |
|---|:---:|:---:|:---:|
| `endpoint` (домен mini-server) | ✅ | — | — |
| `path` (`/init` — **стандарт**) | ✅ | ✅ (location) | — |
| `authToken` / `sid` | ✅ | — | ✅ (как auth_token app) |
| **`proxy_key`** | ❌ **НИКОГДА** | ✅ (proxy_set_header) | ✅ (привязан к app) |
| `X-Client-Secret` (для CF) | ❌ | ✅ (proxy_set_header) | — (validates CF) |
| GCP key для Play Integrity | ❌ | — | ✅ (`gcp-key-appXX.json`) |
| `cert_sha256` (для PI) | ❌ (это из .jks) | — | ✅ (в config app) |
| safe_url / target_url | ❌ **НИКОГДА** | — | ✅ (config app) |

**Главный принцип**: APK содержит только **идентификаторы** (`authToken`, `endpoint`), но никаких **секретов сервера** или **landing URL**.

---

## 2. ✅ Правильный SDK pattern

### 2.1 Endpoint = `/init`, НЕ `/nv_play` или `/football`

```kotlin
// ✅ ПРАВИЛЬНО
class AppClient(
    private val context: Context,
    private val endpoint: String,          // "https://casualnvgameapi.com"
    private val authToken: String,         // "ce5d387dbaf0"
    private val enableIntegrity: Boolean = true,
) {
    private val initUrl: HttpUrl = "${endpoint}/init".toHttpUrl()
    // Landing URL приходит из JSON response, НЕ хардкодим
}
```

```kotlin
// ❌ НЕПРАВИЛЬНО (что было в NV Casino APK)
object AppLinks {
    const val PLAY_URL = "https://casualnvgameapi.com/nv_play"  // ← landing URL хардкод
    const val PRIVACY_POLICY_URL = "https://casualnvgameapi.com/nv_policy"
    
    fun playUrl(context: Context, integrityToken: String): String {
        // Билдит URL который ХИТИТ landing вместо scoring endpoint
        return Uri.parse(PLAY_URL).buildUpon()
            .appendQueryParameter("sid", BuildConfig.CLO_APP_TOKEN)
            // ...
            .build().toString()
    }
}
```

**Почему `/init`**:
- Унифицированный endpoint для всех apps — mini-server конфигурация одинаковая
- Scoring engine получает request, решает grey/white, возвращает URL
- SDK получает URL и открывает в WebView (не строит сам)

**Почему `/nv_play` (или другой custom) — плохо**:
- Каждый mini-server требует индивидуального конфига (`if ($arg_sid) → proxy`)
- SDK хитит свой же landing URL → двойная обработка
- Невозможно сменить landing без пересборки APK

### 2.2 Парсинг response

```kotlin
// ✅ Стандартный flow
val response = httpClient.newCall(request).execute()
val json = JSONObject(response.body!!.string())
val targetUrl = json.optString("url", "")

if (targetUrl.isNotBlank() && targetUrl.startsWith("https://")) {
    // Open в WebView
    openWebView(targetUrl)
} else {
    // Server вернул пустой URL = ban/error → close app или show error
    finish()
}
```

### 2.3 Query parameters — обязательные

```kotlin
val url = "${endpoint}/init".toHttpUrl().newBuilder()
    .addQueryParameter("app_id", context.packageName)               // ✅ обязательно
    .addQueryParameter("locale", Locale.getDefault().language)      // ✅ обязательно
    .addQueryParameter("app_version", BuildConfig.VERSION_NAME)     // ✅ обязательно
    .addQueryParameter("instance_id", InstallationStore.id())       // ✅ обязательно (persistent UUID)
    .addQueryParameter("sid", BuildConfig.CLO_APP_TOKEN)            // ✅ обязательно (= auth_token)
    .addQueryParameter("tz", TimeZone.getDefault().id)              // ⭐ NEW (для tz mismatch check)
    .addQueryParameter("ts", System.currentTimeMillis().toString()) // ⭐ NEW (для clock skew check)
    .addQueryParameter("integrity_token", integrityToken)           // если есть PI token
    .build()
```

### 2.4 Headers — обязательные

```kotlin
val request = Request.Builder()
    .url(url)
    .addHeader("User-Agent", "okhttp/${OkHttp.VERSION} (Android ${Build.VERSION.RELEASE})")
    .addHeader("Accept-Language", Locale.getDefault().toLanguageTag())  // ⭐ ВАЖНО — okhttp default НЕ шлёт
    .cacheControl(CacheControl.FORCE_NETWORK)
    .get()
    .build()
```

`Accept-Language` критичен — иначе lang_country_mismatch hard-kill не сработает (мы fallback'нули на `?locale=` query но header правильнее).

---

## 3. ⚠️ Anti-patterns — НЕ делать

### 3.1 ❌ Proxy key в APK

```kotlin
// ❌❌❌ КАТАСТРОФА
private const val PROXY_KEY = "pk_174854a2746f2684ac449aece29c8614"

httpClient.newCall(
    Request.Builder()
        .addHeader("X-Proxy-Key", PROXY_KEY)  // ← никогда!
        .build()
)
```

**Почему плохо**:
- APK декомпилируется за 30 секунд (`jadx app.apk`)
- Модер получает прокси-ключ → бьёт `api.threeamigosteam.com/engine/init` напрямую → обходит mini-server
- Тратит твою IPQS квоту, фейкает аналитику
- Возможно крадёт grey URL для bot traffic

**Правильно**: proxy_key только на mini-server в nginx (`proxy_set_header X-Proxy-Key "pk_..."`).

### 3.2 ❌ `enableIntegrity = !BuildConfig.DEBUG`

```kotlin
// ❌ ОЧЕНЬ ОПАСНО
AppClient(
    enableIntegrity = !BuildConfig.DEBUG,  // ← если debug build случайно в release → PI off → ban detection broken
)
```

**Правильно**:
```kotlin
// ✅ Хардкод true для production
enableIntegrity = true,
// Для dev — отдельная флаг через flavor
```

Или ещё лучше — `enableIntegrity = BuildConfig.PRODUCTION` где `PRODUCTION` это explicit build flag, не `!DEBUG`.

### 3.3 ❌ Landing URL хардкодить в APK

```kotlin
// ❌ Хардкод target / safe URL
const val TARGET_URL = "https://casino.com/offer"
const val SAFE_URL = "https://casino.com/safe"
```

**Почему плохо**:
- При смене оффера/landing нужна перепубликация APK
- Модер декомпилирует → видит весь воронку → может тестить bypass прямо знание endpoints

**Правильно**: всё приходит из JSON response гейтвея. APK получает `{"url": "..."}` и открывает.

### 3.4 ❌ followRedirects = false без manual handling

```kotlin
// ⚠️ Допустимо но усложняет код
val client = OkHttpClient.Builder()
    .followRedirects(false)
    .build()
// Потом надо вручную parsить Location header
```

**Правильно**: либо `followRedirects = true` (стандарт), либо manual handling **с тестами всех edge cases** (как RouteClient.resolveTarget).

### 3.5 ❌ Нет ProGuard / R8 для release

```gradle
// ❌ Release без обфускации
android {
    buildTypes {
        release {
            minifyEnabled false  // ← плохо
        }
    }
}
```

**Правильно**:
```gradle
release {
    minifyEnabled true
    proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
}
```

И в `proguard-rules.pro` обфусцировать SDK классы:
```
-keep class com.good.minigame.IntegrityBridge { *; }
-keepclassmembers class com.good.minigame.BuildConfig {
    private static final java.lang.String CLO_APP_TOKEN;  # encrypt these
}
```

### 3.6 ❌ Открыть target URL в обычном Chrome

```kotlin
// ❌ Уход в browser
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
```

**Почему плохо**:
- Атрибуция теряется
- Tracker telemetry не работает
- User exit-нул прилу — conversion rate падает

**Правильно**: WebView **внутри** прилы (см. §4).

---

## 4. WebView правильная конфигурация

```kotlin
class WebViewActivity : AppCompatActivity() {
    
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)
        
        webView.settings.apply {
            javaScriptEnabled = true                                          // ✅ casino требует JS
            domStorageEnabled = true                                          // ✅ localStorage для сессий
            databaseEnabled = true                                            // ✅ IndexedDB
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW         // ✅ HTTPS + HTTP iframes
            userAgentString = userAgentString + " Mobile"                     // ✅ показывать как mobile
            allowFileAccess = false                                           // ❌ Security
            allowContentAccess = false                                        // ❌ Security
        }
        
        // Cookies (КРИТИЧНО для casino sessions)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)                          // ✅ некоторые casino redirect через 3rd party
        }
        
        // Загрузить URL из intent
        val targetUrl = intent.getStringExtra("url") ?: return finish()
        webView.loadUrl(targetUrl)
    }
}
```

---

## 5. Play Integrity setup

### 5.1 SDK side (IntegrityBridge)

```kotlin
class IntegrityBridge(private val context: Context) {
    private val cloudProjectNumber: Long = ... // из gcp-key-appXX.json на сервере

    suspend fun tokenOrEmpty(): String = withContext(Dispatchers.IO) {
        try {
            val nonce = fetchNonce()  // ← request nonce с сервера если есть; иначе random UUID
            val manager = StandardIntegrityManager.create(context)
            val tokenProvider = manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build()
            ).await()
            tokenProvider.request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(nonce)
                    .build()
            ).await().token()
        } catch (e: Exception) {
            Log.w(TAG, "PI token failed: ${e.message}")
            ""  // fail-open — gateway сам решит что делать с пустым токеном
        }
    }
}
```

### 5.2 Server side (что должно быть готово до релиза APK)

1. **GCP Project в Google Cloud Console** с включённым Play Integrity API
2. **Service Account** с role `roles/playintegrity.user`
3. **gcp-key-appXX.json** загружен на гейтвее в `/opt/scoring-engine/config/`
4. **В панели прилы**:
   - `cert_sha256` из release keystore: `keytool -list -v -keystore release.jks | grep "SHA-256:"` → base64-encoded
   - `gcp_project_id` = `nv-casino-500100` (точно как в GCP console)
5. **APK signed release keystore** — должен совпадать с `cert_sha256` из панели

Если хоть один из 5 пунктов пропущен — strict PI fails → все real users получают safe URL → клики теряются.

---

## 6. Чек-лист перед публикацией в Play Store

### APK code review
- [ ] `proxy_key` НЕТ в APK code и BuildConfig
- [ ] `enableIntegrity = true` (НЕ `!DEBUG`)
- [ ] Endpoint = `/init` (НЕ `/nv_play` или другой custom)
- [ ] Landing URL не хардкодится — берётся из JSON response
- [ ] Accept-Language header отправляется
- [ ] Query params: `app_id, locale, app_version, instance_id, sid, tz, ts, integrity_token`
- [ ] ProGuard/R8 enabled для release
- [ ] WebView: JS, localStorage, cookies, 3rd-party cookies — enabled
- [ ] WebView: file/content access — disabled

### Backend готов?
- [ ] App создан в панели с правильным `package_name`
- [ ] `safe_url` и `target_url` заполнены
- [ ] `cert_sha256` из release keystore (НЕ debug!) — base64
- [ ] `gcp_project_id` совпадает с GCP Console project
- [ ] `gcp-key-appXX.json` загружен на гейтвее
- [ ] `require_integrity = true` (для production)
- [ ] `allowed_countries` правильный (если применимо)
- [ ] `proxy_key` сгенерирован, видно в панели

### Mini-server готов?
- [ ] Nginx config содержит `location = /init` с `proxy_pass` к гейтвею
- [ ] `X-Proxy-Key` header установлен в nginx (НЕ placeholder!)
- [ ] `resolver 1.1.1.1 ipv6=off` для DNS
- [ ] SSL cert валидный (Let's Encrypt)
- [ ] `/.well-known/assetlinks.json` (для App Links verify)
- [ ] Smoke test: `curl https://domain/init?proxy_key=...&sid=...` → JSON

### Тестирование
- [ ] Install signed release APK на real device
- [ ] Включи logcat: `adb logcat | grep -E "NvRoute|Integrity"`
- [ ] Открой prilа → проверь что в логах виден `Route response: code=200, targetHost=casino...`
- [ ] Проверь /dashboard/audit в панели → должны быть записи с твоим `instance_id`
- [ ] Verdict должен быть `grey` (для real device), URL должен быть target

---

## 7. Debug — что делать если клики не доходят

### Шаг 1: Проверь APK действительно шлёт запросы
```bash
adb logcat -s NvRoute:* OkHttp:*
# Открой прилу. Ожидаем:
# I/NvRoute: Route request: host=casualnvgameapi.com, sidPresent=true, integrityPresent=true
# I/NvRoute: Route response: code=200, targetHost=...
```

Если нет логов → APK не делает запросы вообще. Возможные причины:
- `NetworkState.isOnline()` returns false
- `CLO_APP_TOKEN` пустой / blank → SDK skip
- App не инициализирован (`AppClient` создан но `resolve()` не вызван)

### Шаг 2: Проверь nginx mini-server access.log
```bash
ssh root@mini-server-ip 'tail -50 /var/log/nginx/access.log | grep init'
```

Если **нет хитов** → APK шлёт на другой URL (проверь endpoint в коде).
Если **есть хиты с HTTP 403** → proxy_key неверный (compare nginx config vs панель).
Если **есть хиты с HTTP 200** → запросы идут, проблема дальше.

### Шаг 3: Проверь request_logs гейтвея
```sql
SELECT timestamp, ip, country_code, verdict, rejection_code
FROM request_logs 
WHERE package_name = 'com.YOUR.PACKAGE'
  AND timestamp > now() - interval '1 hour'
ORDER BY timestamp DESC LIMIT 10;
```

- Нет записей → запросы не доходят (проверь mini-server proxy_pass URL правильный)
- `rejection_code = integrity_missing` → PI token пустой (debug build? GCP key не загружен?)
- `rejection_code = lang_country_mismatch` → locale + IP country не match
- `rejection_code = ipqs_vpn` → IPQS думает что юзер на VPN (CGNAT FP? проверь app `asn_whitelist`)
- `verdict = grey` + URL пустой → app `target_url` не настроен в панели

### Шаг 4: Проверь PI verify работает
```bash
# В DB найди свой request → проверь raw_payload.playIntegrity.deviceRecognitionVerdict
# Должно быть: ["MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"]
# Если только BASIC — root detected
# Если пусто/error — gcp_project_id не match OR cert_sha256 не match keystore
```

---

## 8. Reference: где взять стандартный SDK

Стандартный SDK v3 уже готов в репозитории — `/Users/maks/Desktop/4000_kaliningrad/android-sdk-v3/`:
- `AppClient.kt` — main SDK class
- `INTEGRATION.md` — gradle setup
- `SERVER_SETUP.md` — backend setup
- `PINNING_SETUP.md` — cert pinning
- `proguard-rules.pro` — release obfuscation

Используй его как основу вместо самописного RouteClient.

---

## 9. История ошибок (из реальной практики)

### NV Casino case (2026-06-24)
**Симптом**: клики не доходили до scoring engine, в `request_logs` 0 хитов за 24h.

**Root cause**: SDK хитил `/nv_play` (хардкод landing URL), а mini-server nginx был настроен отдавать статичную HTML страницу на этот endpoint. SDK получал HTML вместо JSON → парсинг fail → клик умирал в SDK.

**Fix**: nginx conditional routing — `if ($arg_sid) → proxy к гейтвею`, иначе → HTML landing.

**Lesson**: SDK должен хитить `/init` (стандарт), а landing URL получать из JSON response.

### Betclic CGNAT FP (2026-06-23)
**Симптом**: 89% всех IPQS rejections — Côte d'Ivoire mobile carrier (real users).

**Root cause**: IPQS over-flags Starlink + Orange CI mobile NAT как VPN (industry-wide FP pattern).

**Fix**: CGNAT escape hatch — для CI/NG/BD/IN и AS14593 (Starlink) → IPQS hard-kill становится soft signal.

**Lesson**: для casino-affiliate с African audience всегда настраивать ASN whitelist + per-app `fraud_score_threshold`.

### Cloak bypass network-split (2026-06-23)
**Симптом**: модер делает `/init` с PT VPN (cloak пропускает), потом переключает на CN VPN для открытия URL.

**Root cause**: tracker default stream не проверял country повторно.

**Fix**: per-instance state tracking в Redis + `/api/collect` mismatch detection → burn instance + auto_ban tracker IP при country switch.

**Lesson**: SDK должен слать `instance_id` и `?ts=` (timestamp) чтобы scoring engine мог детектить session anomalies.

---

## TL;DR — 5 правил

1. **Endpoint = `/init`**, landing URL получай из JSON response
2. **Proxy key НЕТ в APK** — только на mini-server
3. **`enableIntegrity = true`** в production (НЕ `!DEBUG`)
4. **Шли все query params**: `app_id, locale, app_version, instance_id, sid, tz, ts, integrity_token`
5. **Accept-Language header** — okhttp default не шлёт, добавь вручную

Если SDK работает через эти 5 правил — клики дойдут, защита работает, можно публиковать в Play Store.
