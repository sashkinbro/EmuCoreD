// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
#include <jni.h>

#include <string>

#include "shader_chain.h"
#include "shader_effect.h"

namespace {

std::string ToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_NativeCoreBridge_nativeSetShaderEffect(JNIEnv*, jobject,
                                                                  jint effect) {
    emucored::shader_effect::Set(effect);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_core_NativeCoreBridge_nativeSetShaderPreset(JNIEnv* env, jobject,
                                                                  jstring path,
                                                                  jboolean enabled) {
    emucorer::shader_chain::SetPreset(ToString(env, path), enabled == JNI_TRUE);
}

}  // extern "C"
