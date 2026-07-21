-keep class eu.darken.amply.charging.core.access.shizuku.ChargingControlUserService { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# WorkManager instantiates its Room database reflectively; R8 full mode strips the generated
# impl's no-arg constructor without this, crashing every minified build at startup with
# NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> [].
-keep class * extends androidx.room.RoomDatabase { <init>(); }

