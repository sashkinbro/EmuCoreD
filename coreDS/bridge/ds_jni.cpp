#include <jni.h>
#include <android/native_window_jni.h>

#include "ds_emulator.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeGetVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("melonDS 1.1");
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeStart(JNIEnv* env, jobject,
                                                         jstring rom_path,
                                                         jstring sram_path,
                                                         jstring user_dir) {
    const char* rom = env->GetStringUTFChars(rom_path, nullptr);
    const char* sram = env->GetStringUTFChars(sram_path, nullptr);
    const char* user = env->GetStringUTFChars(user_dir, nullptr);
    const bool result = EmuCoreDS::Emulator().Start(rom, sram);
    if (result) EmuCoreDS::Emulator().SetUserDirectory(user);
    env->ReleaseStringUTFChars(rom_path, rom);
    env->ReleaseStringUTFChars(sram_path, sram);
    env->ReleaseStringUTFChars(user_dir, user);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeStop(JNIEnv*, jobject) {
    EmuCoreDS::Emulator().Stop();
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeReset(JNIEnv*, jobject) {
    EmuCoreDS::Emulator().Reset();
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeIsRunning(JNIEnv*, jobject) {
    return EmuCoreDS::Emulator().IsRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSurfaceChanged(JNIEnv* env, jobject,
                                                                  jobject surface) {
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    EmuCoreDS::Emulator().SetSurface(window);
    if (window) ANativeWindow_release(window);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSurfaceDestroyed(JNIEnv*, jobject) {
    EmuCoreDS::Emulator().SetSurface(nullptr);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSecondarySurfaceChanged(JNIEnv* env, jobject,
                                                                         jobject surface) {
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    EmuCoreDS::Emulator().SetSecondarySurface(window);
    if (window) ANativeWindow_release(window);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSecondarySurfaceDestroyed(JNIEnv*, jobject) {
    EmuCoreDS::Emulator().SetSecondarySurface(nullptr);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSetSwapScreens(JNIEnv*, jobject,
                                                                    jboolean swap) {
    EmuCoreDS::Emulator().SetSwapScreens(swap == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSetScreenLayout(JNIEnv*, jobject,
                                                                     jint mode) {
    EmuCoreDS::Emulator().SetScreenLayout(static_cast<int>(mode));
}

JNIEXPORT jlong JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeGetFrameCount(JNIEnv*, jobject) {
    return static_cast<jlong>(EmuCoreDS::Emulator().FrameCount());
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSetKeyMask(JNIEnv*, jobject, jint mask) {
    EmuCoreDS::Emulator().SetKeyMask(static_cast<uint32_t>(mask));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeTouch(JNIEnv*, jobject, jint x, jint y) {
    EmuCoreDS::Emulator().TouchScreen(static_cast<uint16_t>(x), static_cast<uint16_t>(y));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeReleaseTouch(JNIEnv*, jobject) {
    EmuCoreDS::Emulator().ReleaseTouch();
}

jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

} // extern "C"
