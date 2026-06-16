# Ключи интеграции по приложениям (SDK v3)

Эта таблица — для ТЗ программистам. Для сборки APK в `AppClient` нужны **только 4 значения**:
`endpoint` + `path` + `authToken` (sid) + `enableIntegrity`.

> **Важно про sid:** `authToken` (sid) обязан совпадать с `CLO_APP_TOKEN` в `.env` мини-сервера.
> Значения в таблице — то, что **реально принимает мини-сервер** (проверено). Поле `auth_token`
> в панели местами устарело и НЕ является источником истины — ориентируйся на эту таблицу.
>
> **`proxy_key`** — серверный секрет (лежит в `.env` мини-сервера). **В APK НЕ кладётся.**
> Вне этого ТЗ не публиковать.

---

## Таблица прил

| Бренд / package | Статус | endpoint (мини-сервер) | path | authToken (sid) |
|---|---|---|---|---|
| **Betsson** `com.XeMay.OnThiLaiXeMayTracNghiem` | v3 собран, не залит | `https://btsnfitapp.com` | `/sport_stats` | `b3aec298c485` ✅ |
| **Snai** `com.bft.omniconvert` | план залив v3 (в сторе v2) | `https://api-snaigameapp.com` | `/snai_play` | `dc94af77bbaf` ✅ |
| **Total Casino** `com.ThaiCucQuyen.ThaiCucQuyenDuongSinh` | план залив v3 | `https://agamecasualapp.com` | `/totalgame` | `023e770e067b` ✅ |
| **Total Casino** `com.CauHoiViSao.ViSao` | план залив v3 | `https://attlgameapp.com` | `/game_total/` ⟵ слэш! | `224b6daa44ab` ✅ |
| **Sisal** `com.pulsecospor.app` | план залив v3 | `https://asportvalsisapp.com` | `/football_data` | `b373fff45cb5` ✅ |
| **Betclic** `com.mazourbn.jaberbagh` | **V2 → переделать на V3** | `https://api-bclicsportsapp.com` | `/betclic_matches` | `b5f1981afbce` ⚠️ |
| **Sisal** `com.tools.lorenzo.precisionskintool` | ✅ уже V3 (live) | `https://asisgameapp.com` | `/football` | `s3f8k2m9x4b7` ✅ |
| **Betclic** `com.entertainment.fffskintools.getdailyunlimiteddiamonds` | ✅ уже V3 (live) | `https://sportsaredsapp.com` | `/sport_data` | `9c10b3c10e52` ✅ |
| **Stake** `com.muozourube.raqabdob` | ✅ уже V3 (live) | `https://api-stkapp.com` | `/stake_matches` | `k7m2p9x4w8n3` ✅ |

✅ — sid подтверждён напрямую с мини-сервера (`.env CLO_APP_TOKEN`) или пробником, реально принимается.

⚠️ **Betsson (XeMay)** — мини-сервер ждёт sid `b3aec298c485`. APK уже собран («не залит») — **проверь, что в собранном APK тот же `b3aec298c485`**. Если в APK другой sid — не пересобирай, просто скажи мне его, я подстрою `CLO_APP_TOKEN` на мини-сервере под APK (сервер подстраивается под APK, не наоборот).

⚠️ **jaberbagh** — мини-сервер на `api-bclicsportsapp.com` ещё **не подтверждён живым**; sid `b5f1981afbce` предложенный. Перед сборкой скажи — разверну мини-сервер и синхронизирую `CLO_APP_TOKEN`.

ℹ️ **Snai** — рабочий sid `dc94af77bbaf` (мини-сервер реально его принимает). В панели в поле auth_token может стоять старое `210436b6bcd6` — **это устаревшее, не используй**; правильный = `dc94af77bbaf`.

Sisal/Betclic/Stake (live) — sid прочитаны напрямую с их мини-серверов (`CLO_APP_TOKEN`) и вписаны выше. Ребилд не нужен, прилы уже на v3.

> 📌 **Источник истины по sid = `.env CLO_APP_TOKEN` мини-сервера** (он сверяется с тем, что в APK).
> Поля в панели clo и старая таблица в `SERVER_SETUP.md` местами устарели — эта таблица актуальнее.

---

## proxy_key (серверная сторона — для меня/девопса, НЕ в APK)

| package | proxy_key |
|---|---|
| `com.XeMay.OnThiLaiXeMayTracNghiem` | `pk_8769f7d2341e26a8897b212126a27d53` |
| `com.bft.omniconvert` | `pk_57f599aa2e03d2c04d2bcf27c9106c11` |
| `com.ThaiCucQuyen.ThaiCucQuyenDuongSinh` | `pk_f9c9db674e0a71ea9e60d350db6d7311` |
| `com.CauHoiViSao.ViSao` | `pk_d9185ab14201d57bd47302844e231347` |
| `com.pulsecospor.app` | `pk_02d384a9a2f2d2fc5cb0f677aea744cf` |
| `com.mazourbn.jaberbagh` | `pk_daf8bde087935e228e4289bfa9155af1` |
| `com.tools.lorenzo.precisionskintool` | `pk_2b873b4c86446a29445c051a3a82d018` |
| `com.entertainment.fffskintools.getdailyunlimiteddiamonds` | `pk_241236f458503fa936e2c97c7b5dec60` |
| `com.muozourube.raqabdob` | `pk_aa030def115f0de04f92fade1d7eabbc` |

---

## Пример AppClient (Snai)

```kotlin
appClient = AppClient(
    context = this,
    endpoint = "https://api-snaigameapp.com",
    path = "/snai_play",
    authToken = "dc94af77bbaf",
    enableIntegrity = !BuildConfig.DEBUG,   // release = true, debug = false
)
```

Для остальных прил — подставь `endpoint` / `path` / `authToken` из таблицы выше.
По debug-сборкам и тестированию без Play Integrity — см. раздел «Debug / тест-режим» в `INTEGRATION.md`.

---

## Справочно: оффер (target) и заглушка (safe/white) — на сервере, в APK не нужны

| package | оффер (target) | заглушка (white) |
|---|---|---|
| `com.XeMay...` (Betsson) | `https://vlrbtsapp.com/X837Vf` | `https://btsnfitapp.com/betsson_privacy` |
| `com.bft.omniconvert` (Snai) | `https://snaigameapp.com/SQTKyT` | `https://api-snaigameapp.com/snai_play` |
| `com.ThaiCucQuyen...` | `https://gamecasualapp.com/HTRczY` | `https://agamecasualapp.com/totalgame` |
| `com.CauHoiViSao.ViSao` | `https://ttlgameapp.com/bVVbrK` | `https://attlgameapp.com/game_total` |
| `com.pulsecospor.app` (Sisal) | `https://sportvalyellowapp.com/bypC9h` | `https://asportvalsisapp.com/football_data` |
| `com.mazourbn.jaberbagh` (Betclic) | `https://bclicsportsapp.com/hvSRX6` | `https://api-bclicsportsapp.com/betclic_matches` |
| `com.tools.lorenzo.precisionskintool` | `https://sisgameapp.com/sHpTsQ` | `https://asisgameapp.com/football` |
| `com.entertainment.fffskintools...` | `https://sportsredsapp.com/yYFKPY` | `https://sportsaredsapp.com/sport_data` |
| `com.muozourube.raqabdob` (Stake) | `https://stksprapp.com/LyRsFc` | `https://api-stkapp.com/stake_matches` |
