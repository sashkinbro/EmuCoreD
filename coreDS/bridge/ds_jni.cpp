#include <jni.h>
#include <android/native_window_jni.h>

#include "ds_emulator.h"
#include "VulkanDispatch.h"
#include "../../core-common/typed_cheats.h"

namespace {
std::string gpuDriverString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" {
JNIEXPORT jboolean JNICALL Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeIsCustomGpuDriverActive(
    JNIEnv*, jobject) {
    return melonDS::VulkanDispatch::UsingCustomDriver() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeConfigureGpuDriver(
    JNIEnv* env, jobject, jstring tmp, jstring hook, jstring directory, jstring name) {
    melonDS::VulkanDispatch::DriverConfiguration config;
    config.TmpLibDir = gpuDriverString(env, tmp);
    config.HookLibDir = gpuDriverString(env, hook);
    config.CustomDriverDir = gpuDriverString(env, directory);
    config.CustomDriverName = gpuDriverString(env, name);
    config.DisplayName = config.CustomDriverName;
    config.UseCustomDriver = !config.CustomDriverDir.empty() && !config.CustomDriverName.empty();
    melonDS::VulkanDispatch::ConfigureDriver(config);
}
JNIEXPORT jboolean JNICALL Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeReplaceCheats(JNIEnv* env,jobject,jobjectArray formats,jobjectArray codes) {
    std::vector<TypedCheatInput> inputs;if(!typedCheatInputs(env,formats,codes,inputs))return false;
    std::vector<std::vector<uint32_t>> words;
    for(const auto& input:inputs) {
        if(input.format!="nds-action-replay")return false;
        std::vector<uint32_t> block;
        for(const auto& line:input.lines) {
            if(!cheatLineMatches(line,"[0-9A-F]{8} [0-9A-F]{8}"))return false;
            block.push_back(std::stoul(line.substr(0,8),nullptr,16));block.push_back(std::stoul(line.substr(9,8),nullptr,16));
        }
        words.push_back(std::move(block));
    }
    return EmuCoreDS::Emulator().ReplaceCheats(std::move(words));
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeGetVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("melonDS 1.1");
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeStart(JNIEnv* env, jobject,
                                                         jstring rom_path,
                                                         jstring sram_path,
                                                         jstring user_dir,
                                                         jboolean start_paused) {
    const char* rom = env->GetStringUTFChars(rom_path, nullptr);
    const char* sram = env->GetStringUTFChars(sram_path, nullptr);
    const char* user = env->GetStringUTFChars(user_dir, nullptr);
    const bool result = EmuCoreDS::Emulator().Start(rom, sram, start_paused == JNI_TRUE);
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

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSetRenderScale(JNIEnv*, jobject,
                                                                    jint scale) {
    EmuCoreDS::Emulator().SetRenderScale(static_cast<int>(scale));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSetPaused(JNIEnv*, jobject,
                                                               jboolean paused) {
    EmuCoreDS::Emulator().SetPaused(paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSetRenderer(JNIEnv*, jobject,
                                                                 jint renderer) {
    EmuCoreDS::Emulator().SetRenderer(static_cast<int>(renderer));
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeSaveState(JNIEnv* env, jobject,
                                                               jstring path) {
    const char* state_path = env->GetStringUTFChars(path, nullptr);
    const bool result = EmuCoreDS::Emulator().SaveState(state_path);
    env->ReleaseStringUTFChars(path, state_path);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucored_core_ds_EmuCoreDsNative_nativeLoadState(JNIEnv* env, jobject,
                                                               jstring path) {
    const char* state_path = env->GetStringUTFChars(path, nullptr);
    const bool result = EmuCoreDS::Emulator().LoadState(state_path);
    env->ReleaseStringUTFChars(path, state_path);
    return result ? JNI_TRUE : JNI_FALSE;
}

jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

} // extern "C"
