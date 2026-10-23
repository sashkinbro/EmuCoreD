# EmuCoreD R8 rules.
#
# This Compose/Kotlin frontend uses Azahar's Android bridge (libemucore_core.so),
# melonDS through EmuCoreD's DS bridge (libemucored_ds.so), and the frontend
# shader bridge (libemucore_shaders.so). Runtime name lookups need explicit keeps:
# JNI entry points, classes resolved by the Discord partner SDK,
# and kotlinx.serialization serializers used for persisted JSON and
# typed Navigation Compose routes. Everything else is shrunk normally.

# Keep readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Signatures/annotations are read at runtime by Room and the Discord SDK.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# --- JNI: frontend shader bridge and Discord --------------------------------
# Native entry points are resolved by their mangled names
# (Java_com_sbro_emucored_core_NativeCoreBridge_*,
#  Java_com_sbro_emucored_discord_DiscordNative_*), so class and method names
# must stay stable in release builds.
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucored.core.NativeCoreBridge {
    native <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucored.discord.DiscordNative {
    native <methods>;
}

# --- JNI: bundled melonDS core ----------------------------------------------
# ds_jni.cpp exports Java_com_sbro_emucored_core_ds_EmuCoreDsNative_* symbols.
# Keep this exact class and its native methods even if generic rules or @Keep
# annotations change. DS has no Java callbacks resolved through FindClass.
-keep,includedescriptorclasses class com.sbro.emucored.core.ds.EmuCoreDsNative {
    native <methods>;
}

# New framebuffer cores and Dolphin export JNI names from separate libraries.
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucored.core.gba.MgbaSessionEngine { native <methods>; }
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucored.core.gb.SameBoySessionEngine { native <methods>; }
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucored.core.nes.NestopiaSessionEngine { native <methods>; }
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucored.core.snes.BsnesSessionEngine { native <methods>; }
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucored.core.dolphin.DolphinNative { native <methods>; }
# Android audio/microphone callbacks are looked up by name in AndroidServices.cpp.
-keep,includedescriptorclasses class com.sbro.emucored.core.dolphin.DolphinServices { *; }

# --- JNI: bundled Azahar core -----------------------------------------------
# libemucore_core.so is the vendored Android core. It resolves these Kotlin
# classes, fields, constructors and static callbacks by name from C++
# (FindClass/GetMethodID/GetFieldID in core/src/android/app/src/main/jni).
# Keep the whole surface explicitly instead of relying on the default
# "native <methods>" rule or @Keep annotations alone.
-keepclasseswithmembernames,includedescriptorclasses class com.sbro.emucored.core.** {
    native <methods>;
}
-keep,includedescriptorclasses class com.sbro.emucored.core.EmuCoreNative { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.EmuCoreNative$* { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.Cheat { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.CheatEngine { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.GameInfo { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.Log { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.MiiSelector { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.MiiSelector$* { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.SoftwareKeyboard { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.SoftwareKeyboard$* { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.StillImageCameraHelper { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.DiskShaderCacheProgress { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.DiskShaderCacheProgress$* { *; }
-keep,includedescriptorclasses class com.sbro.emucored.core.CiaInstallWorker { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.sbro.emucored.core.NetPlayManager { *; }

# --- RetroAchievements ------------------------------------------------------
# achievements_bridge.cpp resolves the HTTP bridge object, its static request
# method and the response fields by name from the native HTTP worker thread.
-keep class com.sbro.emucored.core.AchievementsHttp { *; }
-keep class com.sbro.emucored.core.AchievementsHttpResponse { *; }

# --- Discord Social SDK -----------------------------------------------------
# libdiscord_partner_sdk.so instantiates its Java models and activity classes
# by name.
-keep class com.discord.socialsdk.** { *; }

# --- Persisted enums --------------------------------------------------------
# Enum constant names are written to DataStore/JSON (drawer items, game menu
# tabs/sections, texture download status) and matched by name on the next
# launch, so they must not be renamed by R8.
-keepclassmembers enum com.sbro.emucored.** {
    *;
}

# --- kotlinx.serialization --------------------------------------------------
# Persisted JSON models and typed Navigation routes decode through generated
# serializers/companions that R8 cannot see statically.
-keepclassmembers class com.sbro.emucored.** {
    *** Companion;
}
-keepclasseswithmembers class com.sbro.emucored.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sbro.emucored.**$$serializer { *; }
-keepclassmembers class **$$serializer {
    public static ** INSTANCE;
}

# The release APK does not emit application logcat diagnostics. R8 also drops
# message construction for these calls when its result has no other consumer.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
