# SDK v3 — Полная инструкция интеграции и чеклист Google Play

## Что это

Лёгкий клиент для подключения к системе. Один класс, один запрос. В APK нет ничего подозрительного.

---

## 1. Зависимости

В `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Push-уведомления (обязательно для Google Play)
    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    implementation("com.google.firebase:firebase-messaging")
}
```

## 2. Скопировать AppClient.kt

Скопировать файл `AppClient.kt` в свой проект. Можно переименовать пакет.

## 3. Инициализация в Application

```kotlin
class MyApp : Application() {
    val appClient = AppClient("https://YOUR-DOMAIN.com")
}
```

## 4. Получение URL при запуске

```kotlin
// В ViewModel
val url = (application as MyApp).appClient.resolve()

if (url.isNotEmpty()) {
    webView.loadUrl(url)
} else {
    showMainContent() // Нативный контент — заглушка
}
```

## 5. WebView настройки

```kotlin
webView.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
}

// ВАЖНО: НЕ ДОБАВЛЯТЬ addJavascriptInterface!
// ВАЖНО: НЕ ДОБАВЛЯТЬ evaluateJavascript!

webView.loadUrl(url)
```

## 6. Offline Handling (ОБЯЗАТЕЛЬНО для Google Play)

Если WebView не может загрузить страницу — показать нативный контент:

```kotlin
webView.webViewClient = object : WebViewClient() {
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame == true) {
            // Показать нативный offline экран
            webView.visibility = View.GONE
            offlineLayout.visibility = View.VISIBLE
        }
    }
}
```

## 7. Нативная навигация (ОБЯЗАТЕЛЬНО)

```kotlin
// Back button — WebView назад или выход
onBackPressedDispatcher.addCallback {
    if (webView.canGoBack()) {
        webView.goBack()
    } else {
        finish()
    }
}

// Swipe to refresh (опционально)
swipeRefresh.setOnRefreshListener {
    webView.reload()
    swipeRefresh.isRefreshing = false
}
```

## 8. Push-уведомления (ОБЯЗАТЕЛЬНО для Google Play)

Приложение без push-уведомлений может быть забанено за "minimum functionality".

```kotlin
// MyFirebaseService.kt
class MyFirebaseService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "Update"
        val body = message.notification?.body ?: ""

        val notification = NotificationCompat.Builder(this, "main")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(1, notification)
    }
}
```

AndroidManifest.xml:
```xml
<service android:name=".MyFirebaseService" android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

## 9. ProGuard

```
-dontwarn okhttp3.**
-dontwarn okio.**
```

Больше ничего. Нет JS interface, нет SDK-классов для keep.

---

## Что НЕЛЬЗЯ делать

- ❌ `addJavascriptInterface` — триггер RISKWARE
- ❌ `evaluateJavascript` — динамическая загрузка кода
- ❌ Собирать GPU, codename, build product — фингерпринтинг
- ❌ Использовать Play Integrity API в приложении — не нужно
- ❌ Хардкодить домен основного сервера кло
- ❌ Кастомные X-заголовки (X-Client-Secret, X-Device-Info)
- ❌ Несколько запросов перед показом контента
- ❌ Проверки isEmulator(), isRooted() в коде
- ❌ Сканирование установленных приложений (PackageManager)

---

## Privacy Policy

Приложение ОБЯЗАНО иметь Privacy Policy. Используйте шаблон из файла `PRIVACY_POLICY.html`.

Где разместить:
1. На вашем домене: `https://your-domain.com/privacy`
2. В Google Play Console → Store listing → Privacy Policy URL

---

## Data Safety (Google Play Console)

При заполнении Data Safety в Play Console укажите:

### Данные которые собираются:

| Тип данных | Собирается? | Для чего | Делится? |
|---|---|---|---|
| IP Address | Да (автоматически) | Аналитика, безопасность | Нет |
| Device/OS info (User-Agent) | Да (автоматически) | Совместимость | Нет |
| App interactions | Да | Аналитика | Нет |
| Crash logs | Да (Firebase) | Стабильность | Нет |

### Что отметить:
- ✅ "My app collects data" — Yes
- ✅ "Data is encrypted in transit" — Yes (HTTPS)
- ✅ "Users can request data deletion" — Yes
- ❌ "Data is NOT sold to third parties"
- ❌ "Data is NOT shared for advertising"

### Что НЕ собирается:
- ❌ Location
- ❌ Phone number
- ❌ Email
- ❌ Contacts
- ❌ Photos/Videos
- ❌ Financial data

---

## Чеклист перед заливкой в Google Play

| # | Пункт | Проверить |
|---|---|---|
| 1 | Новый аккаунт разработчика (не связан с забаненными) | Другая карта, другой IP, другой email |
| 2 | SDK v3 (AppClient.kt) — только один GET запрос | Нет JS bridge, нет fingerprinting |
| 3 | Свой уникальный домен | Не threeamigosteam.com |
| 4 | Нет палевных строк в DEX | Нет "casino", "bet", "gambling", "verdict", "score" |
| 5 | WebView БЕЗ addJavascriptInterface | Проверить grep по коду |
| 6 | Нативные фичи | Push-уведомления, offline screen, back navigation |
| 7 | Privacy Policy | URL в Google Play Console + в приложении |
| 8 | Data Safety заполнен | Корректно, без лишнего |
| 9 | Приложение работает если сервер недоступен | Показывает нативный контент |
| 10 | HTTPS only | Нет HTTP запросов |
| 11 | Нет опасных permissions | Нет LOCATION, PHONE_STATE, READ_CONTACTS |
| 12 | CAMERA только если нужна (file upload) | С объяснением в описании |
| 13 | Минимум контента в приложении | Не пустая обёртка — есть UI, навигация, контент |
| 14 | targetSdk >= 35 | Требование 2025+ |
| 15 | Pre-launch report | Проверить в Play Console после загрузки |
