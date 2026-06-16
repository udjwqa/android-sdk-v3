# SDK v3 — Полная инструкция интеграции

## Что это

Лёгкий клиент — один класс, один файл, один запрос. Приложение открывает URL, сервер решает что показать. В APK нет ничего подозрительного.

---

## 1. Зависимости

В `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    
    // Play Integrity (официальный Google API)
    implementation("com.google.android.play:integrity:1.6.0")
    
    // Push-уведомления (обязательно для Google Play)
    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    implementation("com.google.firebase:firebase-messaging")
    
    // Crashlytics (рекомендуется)
    implementation("com.google.firebase:firebase-crashlytics")
}
```

В корневом `build.gradle.kts`:
```kotlin
plugins {
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
}
```

В `app/build.gradle.kts` plugins:
```kotlin
plugins {
    id("com.google.firebase.crashlytics")
}
```

## 2. Скопировать AppClient.kt

Скопировать `AppClient.kt` в проект. Пакет можно переименовать.

## 3. Инициализация в Application

```kotlin
class MyApp : Application() {
    lateinit var appClient: AppClient
        private set

    override fun onCreate() {
        super.onCreate()
        appClient = AppClient(
            context = this,
            endpoint = "https://YOUR-DOMAIN.com",   // Домен мини-сервера
            path = "/football",                      // Путь к странице
            authToken = "YOUR_SECRET_TOKEN",         // Секретный токен (из .env мини-сервера)
            enableIntegrity = !BuildConfig.DEBUG,    // PI: выкл в debug, вкл в release
        )
    }
}
```

### Параметры:

| Параметр | Описание | Обязательный |
|---|---|---|
| `context` | Application context | Да |
| `endpoint` | URL мини-сервера (домен клиента) | Да |
| `path` | Путь (default: `/football`) | Нет |
| `authToken` | Секретный токен для авторизации | Да |
| `enableIntegrity` | Включить Play Integrity | Нет (default: true) |

> 🔑 **Ключи по конкретным прилам** (endpoint / path / authToken для каждого пакета) — в файле `APPS_KEYS.md`.

## 3.1 Debug / тест-режим (ВАЖНО — читать перед тестом)

В debug-сборке `enableIntegrity = !BuildConfig.DEBUG` = **false** → SDK **не шлёт** Play Integrity токен.
А сервер настроен на `require_integrity` → «нет токена» он трактует как провал проверки устройства и
отдаёт **белую** (заглушечную) страницу. **Это не баг** — так и задумано: модератор/бот без валидного PI
не должен видеть оффер.

➡️ Поэтому в debug-сборке ты по умолчанию **всегда увидишь белую страницу**. Чтобы протестировать оффер
на своём устройстве без PI — занеси себя во whitelist:

1. Панель → **Настройки** → блок **«Debug / тест-режим»**.
2. Впиши свой тестовый **IP** или **instance_id** (instance_id виден в деталях клика в дашборде).
3. Сохрани → запросы с этого IP/instance идут в обычный скоринг и на чистом девайсе вернут оффер.
4. **После теста очисти список.**

⚠️ Никогда не выключай `require_integrity` на сервере ради теста — это открывает дыру для модераторов.
Для теста используй только whitelist. В **release**-сборке `enableIntegrity` = `true` — PI работает штатно.

## 4. Получение URL при запуске

```kotlin
// В ViewModel или coroutine scope
lifecycleScope.launch {
    val url = (application as MyApp).appClient.resolve()
    
    if (url.isNotEmpty()) {
        // Серый или белый — решает сервер
        webView.loadUrl(url)
    } else {
        // Сервер недоступен → показать нативный контент
        showMainContent()
    }
}
```

## 5. Открытие URL

> ✅ **Рекомендуется: Chrome Custom Tabs**, а не WebView. Custom Tabs — это системный браузер, он безопаснее для прохождения модерации Google Play (нет встроенного веб-контейнера с гемблингом). WebView ниже — только если Custom Tabs не подходит под задачу. В обоих случаях НИКОГДА не добавляйте `addJavascriptInterface`/`evaluateJavascript`.
>
> ```kotlin
> // Chrome Custom Tabs (предпочтительно)
> val intent = androidx.browser.customtabs.CustomTabsIntent.Builder().build()
> intent.launchUrl(context, android.net.Uri.parse(url))
> ```
> Зависимость: `implementation("androidx.browser:browser:1.8.0")`

### WebView (альтернатива) — настройки

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

## 6. Offline Handling (ОБЯЗАТЕЛЬНО)

```kotlin
webView.webViewClient = object : WebViewClient() {
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame == true) {
            webView.visibility = View.GONE
            offlineLayout.visibility = View.VISIBLE
        }
    }
}
```

## 7. Навигация (ОБЯЗАТЕЛЬНО)

```kotlin
// Back button
onBackPressedDispatcher.addCallback {
    if (webView.canGoBack()) {
        webView.goBack()
    } else {
        finish()
    }
}
```

## 8. Push-уведомления (ОБЯЗАТЕЛЬНО)

```kotlin
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

---

## Что SDK передаёт серверу

URL который приложение открывает выглядит так:
```
https://your-domain.com/football?app_id=com.package.name&locale=it&app_version=1.0.4&instance_id=uuid-xxx&sid=token&integrity_token=XYZ
```

| Параметр | Откуда | Зачем |
|---|---|---|
| `app_id` | `context.packageName` | Идентификация приложения |
| `locale` | `Locale.getDefault().language` | Язык устройства |
| `app_version` | `PackageInfo.versionName` | Версия сборки |
| `instance_id` | UUID в SharedPreferences | Уникальный ID установки |
| `sid` | `authToken` параметр | Авторизация |
| `integrity_token` | Play Integrity API | Проверка устройства (опционально) |

Всё это стандартные данные которые любое приложение может передавать.

---

## Что НЕЛЬЗЯ делать

- ❌ `addJavascriptInterface` — нарушение политики Google Play
- ❌ `evaluateJavascript` — динамическая загрузка кода
- ❌ Собирать GPU, codename, build product — фингерпринтинг
- ❌ Кастомные X-заголовки (X-Client-Secret, X-Device-Info)
- ❌ Проверки `isEmulator()`, `isRooted()` в коде приложения
- ❌ Сканирование установленных приложений
- ❌ Хардкодить домен бекенд-сервера (только домен мини-сервера)

---

## Privacy Policy

Используйте шаблон из файла `PRIVACY_POLICY.html`. Разместить:
1. На домене: `https://your-domain.com/privacy`
2. В Google Play Console → Store listing → Privacy Policy URL

## Data Safety

Подробная инструкция в файле `DATA_SAFETY.md`.

---

## Чеклист перед заливкой

| # | Пункт |
|---|---|
| 1 | Новый аккаунт разработчика (не связан с забаненными) |
| 2 | SDK v3 интегрирован (AppClient.kt) |
| 3 | Свой уникальный домен мини-сервера |
| 4 | authToken прописан (из .env мини-сервера) |
| 5 | `enableIntegrity = !BuildConfig.DEBUG` |
| 6 | WebView БЕЗ addJavascriptInterface |
| 7 | Push-уведомления (Firebase FCM) |
| 8 | Crashlytics подключен |
| 9 | Offline handling (WebView error → нативный контент) |
| 10 | Back button навигация |
| 11 | Privacy Policy (URL в Play Console + в приложении) |
| 12 | Data Safety заполнен корректно |
| 13 | Нет палевных строк в DEX (нет "casino", "bet", "verdict") |
| 14 | HTTPS only |
| 15 | targetSdk >= 35 |
| 16 | Pre-launch report проверен в Play Console |
