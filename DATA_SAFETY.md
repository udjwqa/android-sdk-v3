# Data Safety — Инструкция заполнения в Google Play Console

## Где заполнять

Google Play Console → ваше приложение → Policy → App content → Data safety

---

## Шаг 1: "Does your app collect or share any of the required user data types?"

Ответ: **Yes**

## Шаг 2: "Is all of the user data collected by your app encrypted in transit?"

Ответ: **Yes** (все запросы через HTTPS)

## Шаг 3: "Do you provide a way for users to request that their data is deleted?"

Ответ: **Yes** (email в Privacy Policy)

## Шаг 4: Типы данных

### Отметить как СОБИРАЕМЫЕ:

| Категория | Тип | Собирается | Делится | Цель |
|---|---|---|---|---|
| Device or other IDs | Device or other IDs | ✅ | ❌ | Analytics, Security |
| App activity | App interactions | ✅ | ❌ | Analytics |
| App info and performance | Crash logs | ✅ | ❌ | Analytics |
| App info and performance | Diagnostics | ✅ | ❌ | Analytics |

### НЕ отмечать (мы это НЕ собираем):

- ❌ Location (precise or approximate)
- ❌ Personal info (name, email, phone, address)
- ❌ Financial info
- ❌ Health and fitness
- ❌ Messages
- ❌ Photos and videos
- ❌ Audio files
- ❌ Files and docs
- ❌ Calendar
- ❌ Contacts
- ❌ Web browsing history
- ❌ Search history
- ❌ Installed apps

## Шаг 5: Для каждого отмеченного типа

### Device or other IDs:
- Is this data collected, shared, or both? → **Collected**
- Is this data processed ephemerally? → **No**
- Is this data required or can users choose? → **Required** (автоматически)
- Why is this data collected? → **Analytics**, **Security, fraud prevention**

### App interactions:
- Is this data collected, shared, or both? → **Collected**
- Is this data processed ephemerally? → **Yes**
- Why is this data collected? → **Analytics**

### Crash logs / Diagnostics:
- Is this data collected, shared, or both? → **Collected**
- Is this data processed ephemerally? → **No**
- Why is this data collected? → **Analytics**

## Шаг 6: Preview и Submit

Проверьте preview — должно выглядеть чисто:
- "No data shared with third parties"
- "Data collected": Device IDs, App interactions, Crash logs
- "Data is encrypted in transit"
- "You can request that data be deleted"

---

## Частые ошибки

1. **Не указали что собирается Device ID** → Google найдёт User-Agent в трафике и отклонит
2. **Указали Location** → но в манифесте нет LOCATION permission → несоответствие → отклонение
3. **Не указали Crash logs** → но Firebase Crashlytics в dependencies → несоответствие
4. **"Data is not collected"** → но приложение делает HTTP запросы → автоматическое несоответствие

## Золотое правило

Data Safety должно **точно** соответствовать тому что приложение реально делает. Не больше, не меньше. Google проверяет автоматически — сканирует APK и сравнивает с декларацией.
