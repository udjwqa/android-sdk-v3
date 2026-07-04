# F7: SSL Certificate Pinning Setup (v4.0.0 update 2026-07-02)

## Зачем

Без cert pinning модератор устанавливает свой root-CA на свой Android и через **Charles/mitmproxy/BurpSuite** видит весь трафик APK → mini-сервер: sid, instance_id, integrity_token, ответ кло, target URL. После этого реплеит запросы.

С pinning OkHttp проверяет что cert от mini-сервера подписан **именно нашим**, а не user-CA → MITM невозможен.

## v4 update

Wire protocol в v4 — **POST /init с headers**, но domain pinning работает точно так же
(pin применяется к TLS handshake на TCP connect, до HTTP слоя). Никаких изменений
в API `AppClient.addPins()` — код совместим с v3.

## Как собрать pin для домена

Для каждого мини-сервера (sisalfootballapp.com, bsonsportapp.com, supercastotalgame.com, totalsupergame.com, olimpcinemapp.com — или свой):

```bash
DOMAIN=свой-домен.com
openssl s_client -servername $DOMAIN -connect $DOMAIN:443 < /dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
```

## Как использовать в Application

В `MyApp.onCreate` (вызывается ДО первого AppClient.resolve()):

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppClient.addPins("свой-домен.com", listOf(
            "sha256/PRIMARY_PIN_FROM_OPENSSL=",
            "sha256/BACKUP_PIN_FROM_OPENSSL="
        ))
    }
}
```

## Rotation план

LE certs renew каждые 60d. Чтобы не сломать APK:
1. **За месяц до rotation**: добавить новый primary pin как **backup** в release APK (выкатить).
2. **На rotation day**: server cert меняется. Старый primary fails → backup активируется.
3. **После rotation**: новый primary pin становится primary, старый pin удаляется в следующей версии.

ВСЕГДА **2 pins минимум** — иначе rotation сломает все live APK.

## Verify

После сборки APK:
1. Установить Charles/mitmproxy на свой Android (user-CA)
2. Запустить APK
3. Должна быть ошибка SSL → запрос НЕ виден в Charles ✅
