// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include "libretro.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_sbro_emucored_core_n64_Mupen64Native_apiVersion(JNIEnv*, jobject) {
    return static_cast<jint>(retro_api_version());
}
