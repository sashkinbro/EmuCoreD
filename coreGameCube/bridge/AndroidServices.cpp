// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <string>
#include <span>
#include <vector>
#include "jni/AndroidCommon/AndroidCommon.h"
#include "jni/AndroidCommon/IDCache.h"

namespace {
JavaVM* vm;
jclass services;
jmethodID rate_method, frames_method, permission_method, request_method;
struct ThreadEnv {
  JNIEnv* env = nullptr;
  bool attached = false;
  ~ThreadEnv() { if (attached && vm) vm->DetachCurrentThread(); }
};
}
jint EmuCoreDInitializeAndroid(JavaVM* java_vm) {
  vm = java_vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  const auto local = env->FindClass("com/sbro/emucored/core/dolphin/DolphinServices");
  if (!local) return JNI_ERR;
  services = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  rate_method = env->GetStaticMethodID(services, "sampleRate", "()I");
  frames_method = env->GetStaticMethodID(services, "framesPerBuffer", "()I");
  permission_method = env->GetStaticMethodID(services, "hasMicrophonePermission", "()Z");
  request_method = env->GetStaticMethodID(services, "requestMicrophonePermission", "()V");
  return env->ExceptionCheck() ? JNI_ERR : JNI_VERSION_1_6;
}
namespace IDCache {
JNIEnv* GetEnvForThread() {
  thread_local ThreadEnv thread;
  if (!thread.env) {
    if (vm->GetEnv(reinterpret_cast<void**>(&thread.env), JNI_VERSION_1_6) != JNI_OK) {
      if (vm->AttachCurrentThread(&thread.env, nullptr) != JNI_OK) return nullptr;
      thread.attached = true;
    }
  }
  return thread.env;
}
jclass GetAudioUtilsClass() { return services; }
jmethodID GetAudioUtilsGetSampleRate() { return rate_method; }
jmethodID GetAudioUtilsGetFramesPerBuffer() { return frames_method; }
jclass GetPermissionHandlerClass() { return services; }
jmethodID GetPermissionHandlerHasRecordAudioPermission() { return permission_method; }
jmethodID GetPermissionHandlerRequestRecordAudioPermission() { return request_method; }
}
std::string GetJString(JNIEnv* env, jstring value) {
  if (!value) return {};
  const char* text = env->GetStringUTFChars(value, nullptr);
  if (!text) return {};
  std::string result(text);
  env->ReleaseStringUTFChars(value, text);
  return result;
}
jstring ToJString(JNIEnv* env, std::string_view text) { return env->NewStringUTF(std::string(text).c_str()); }
// SAF paths are materialized by the common launch layer. Never interpret a URI
// as an ordinary file or silently claim successful content-provider operations.
bool IsPathAndroidContent(std::string_view path) { return path.starts_with("content://"); }
std::string OpenModeToAndroid(std::string mode) { return mode; }
std::string OpenModeToAndroid(std::ios_base::openmode mode) { return (mode & std::ios::out) ? "rw" : "r"; }
int OpenAndroidContent(std::string_view, std::string_view) { return -1; }
bool DeleteAndroidContent(std::string_view) { return false; }
jlong GetAndroidContentSizeAndIsDirectory(std::string_view) { return -1; }
std::string GetAndroidContentDisplayName(std::string_view) { return {}; }
std::vector<std::string> GetAndroidContentChildNames(std::string_view) { return {}; }
std::vector<std::string> DoFileSearchAndroidContent(std::string_view, std::span<const std::string_view>, bool) { return {}; }
int GetNetworkIpAddress() { return 0; }
int GetNetworkPrefixLength() { return 0; }
int GetNetworkGateway() { return 0; }
