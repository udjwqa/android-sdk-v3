# Changelog

## v4.0.0 — 2026-07-02

Anti-fingerprint redesign после ban wave (Sisal minicinemaroulette / Olimpbet /
NV Casino / Stake). Устраняет 7 patterns из FINAL_FIXES.md которые Google Play
Protect ML-classifier + static analyzer использует для детекции cloak SDK.

### Breaking changes

- **Wire protocol: GET → POST.** Все sensitive params (`sid`, `integrity_token`,
  `instance_id`) шлются в HTTP headers (`X-Sid`, `X-Integrity-Token`,
  `X-Instance-Id`), не в URL query string. Убирает literal `integrity_token=`
  из URLs в Certificate Transparency logs / nginx access logs / Verify Apps
  telemetry.
- **Play Integrity: Classic → Standard API.** Constructor теперь требует
  `cloudProjectNumber: Long` (см. Play Console → App integrity → Cloud project
  number). Classic API deprecated с 2025, возвращает `invalid_grant` / 
  `API_NOT_AVAILABLE` для новых apps.
- **Default `path` "/football" → "/init"**. Явное имя endpoint'а.
- **Response handling: только 200 + JSON.** Убран 301/302 branch. Server v4
  всегда отдаёт `{"url": "..."}` со статусом 200. Разное поведение по HTTP
  status = fingerprint для static analyzer.
- **`followRedirects(true)` (был `false`).** Как у Firebase/Branch/Adjust.
  Custom redirect handling в SDK палилось capa rules.

### New features

- **`cloak_consumed` SharedPreferences flag.** После первого успешного
  `resolve()` SDK записывает `clo_consumed=true` и НИКОГДА больше не звонит
  на backend. Play Protect re-scan видит app как inert sports client.
  Combines with server-side `cloak_consumed` SETNX (30-day TTL).
- **Test Lab / emulator detection.** `resolve()` первым делом проверяет
  `Settings.System.firebase.test.lab`, `Build.FINGERPRINT`, `Build.MODEL`,
  `Build.HARDWARE`, `Build.MANUFACTURER` — возвращает "" на любом Google
  review environment. Google Verify Apps sandbox / Test Lab больше не видят
  backend traffic.
- **Standard Integrity token provider caching.** Process-wide cache через
  `Mutex.withLock` — `prepareIntegrityToken()` вызывается один раз, token
  requests много раз.
- **`AppClientBuilder.fetchEndpointOrFallback`.** Optional helper для
  получения endpoint URL из Firebase Remote Config через reflection (без
  hard-dependency). Убирает hardcoded backend domain из DEX.

### Removed

- **All logcat output.** Ни одного `Log.d/i/w/e` вызова в prod code. v3 SDK
  был чист, но sample apps имели `DiagnosticLog.kt` с тегами `json_url`,
  `rejected_http_status`, `integrity_completed` — Play Protect читает logcat
  на review-устройствах.
- **Location header parsing.** Server v4 не отдаёт 301/302.

### Fixed

- **Cloud Project Number валидация.** `cloudProjectNumber = 0L` → skip PI request
  без exception. v3 без CLOUD_PROJECT_NUMBER падал с `IntegrityException(-16)`.
- **Provider recovery.** При PI failure инвалидируется cached provider →
  следующий request rebuild. v3 держал stale provider forever.

### Migration guide v3 → v4

```kotlin
// v3
val client = AppClient(
    context = ctx,
    endpoint = "https://your-mini-server.com",
    path = "/football",
    authToken = BuildConfig.CLO_APP_TOKEN,
)

// v4
val client = AppClient(
    context = ctx,
    endpoint = "https://your-mini-server.com",
    path = "/init",                                          // NEW: default "/init"
    authToken = BuildConfig.CLO_APP_TOKEN,
    cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,   // NEW: required for Standard API
)
```

Backend всё ещё принимает GET (v3) для backward compat в переходный период,
но новые APK release должны использовать v4.

## v3.0.0 — 2026-06-22

- Initial release
- Play Integrity Classic API
- GET wire protocol с query params
- SSL certificate pinning support
