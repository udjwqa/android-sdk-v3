# F8 (2026-06-22): SDK v3 ProGuard / R8 rules
# Защита: модератор не может декомпилировать APK через jadx и увидеть:
#  - endpoint URL (api-stkapp.com etc.)
#  - cloak-домен (api.threeamigosteam.com)
#  - sid / proxy_key / auth_token
#  - PI nonce генерация, instance_id storage logic
#
# Прогеру клиента: добавить в module/build.gradle:
#   buildTypes {
#     release {
#       isMinifyEnabled = true
#       isShrinkResources = true
#       proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
#                     'proguard-rules.pro'
#     }
#   }

# ==== Required keeps (без них рантайм упадёт) ====

# OkHttp 4.x — Platform classes (нужны для proper SSL)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# Play Integrity API — Google library
-keep class com.google.android.play.core.integrity.** { *; }
-keep interface com.google.android.play.core.integrity.** { *; }

# JSON (используется в AppClient для parse response)
-keep class org.json.** { *; }
-keepclassmembers class org.json.** { *; }

# Kotlin coroutines — Continuation
-keep class kotlin.coroutines.Continuation { *; }

# ==== AppClient public API (called from MyApp) ====
-keep public class com.app.client.AppClient {
    public <init>(...);
    public *** resolve(...);
    public static *** addPins(...);
    public *** resetOnboardingForTesting(...);
}

# ==== AppClientBuilder (SDK v4) — uses Firebase RC via reflection ====
-keep public class com.app.client.AppClientBuilder {
    public static *** fetchEndpointOrFallback(...);
}
# NOTE: Firebase RC classes accessed via reflection — если Firebase не подключён,
# reflection тихо fail-open. Никаких rules для com.google.firebase.remoteconfig.**
# намеренно НЕТ, чтобы SDK не тянул Firebase as hard dep.

# ==== ОБФУСКАЦИЯ + СОКРАЩЕНИЕ ====

# Включить максимальную обфускацию (по умолчанию proguard-android-optimize)
-allowaccessmodification
-repackageclasses ''

# Удалить debug-логи в release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# String pool encryption — НЕ встроено в R8. Если нужно (для скрытия strings типа
# "api.threeamigosteam.com"), используй paranoid plugin:
#   plugins { id "com.github.megatronking.stringfog" version "5.0.1" }
#   stringfog { implementation 'com.github.megatronking.stringfog.xor' }
# https://github.com/MegatronKing/StringFog
