# Tuya Smart App SDK (aus der Tuya-Doku; bei SDK-Updates dort gegenprüfen)
-keep class com.thingclips.** { *; }
-dontwarn com.thingclips.**
-keep class com.tuya.** { *; }
-dontwarn com.tuya.**
-keep class com.alibaba.fastjson.** { *; }
-dontwarn com.alibaba.fastjson.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class org.apache.mina.** { *; }
-dontwarn org.apache.mina.**
-keep class com.facebook.** { *; }
-dontwarn com.facebook.**

# Firebase, RevenueCat, Google Mobile Ads, UMP und WorkManager bringen eigene Regeln mit.
