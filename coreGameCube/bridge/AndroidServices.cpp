// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <algorithm>
#include <ios>
#include <string>
#include <span>
#include <vector>
#include "jni/AndroidCommon/AndroidCommon.h"
#include "jni/AndroidCommon/IDCache.h"

namespace {
JavaVM* vm;
jclass services;
jmethodID rate_method, frames_method, permission_method, request_method;
jmethodID open_content_method, content_size_method, content_name_method, delete_content_method;
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
  open_content_method = env->GetStaticMethodID(services, "openContentFd",
                                               "(Ljava/lang/String;Ljava/lang/String;)I");
  content_size_method = env->GetStaticMethodID(services, "contentSizeAndIsDirectory",
                                               "(Ljava/lang/String;)J");
  content_name_method = env->GetStaticMethodID(services, "contentDisplayName",
                                               "(Ljava/lang/String;)Ljava/lang/String;");
  delete_content_method = env->GetStaticMethodID(services, "deleteContent",
                                                 "(Ljava/lang/String;)Z");
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
// Dolphin's own file layer uses content:// paths for SAF documents. All access
// must go through the app's ContentResolver; resolving a document to a raw
// /storage path loses the URI grant on scoped storage.
bool IsPathAndroidContent(std::string_view path) { return path.starts_with("content://"); }
std::string OpenModeToAndroid(std::string mode) {
  std::erase(mode, 'b');
  if (mode == "r" || mode == "w" || mode == "wt" || mode == "wa" || mode == "rw" ||
      mode == "rwt" || mode == "rwa")
    return mode;
  return {};
}
std::string OpenModeToAndroid(std::ios_base::openmode mode) {
  mode &= ~std::ios_base::binary;
  if (mode == std::ios_base::in) return "r";
  if (mode == std::ios_base::out || mode == (std::ios_base::out | std::ios_base::trunc)) return "wt";
  if (mode == (std::ios_base::out | std::ios_base::app)) return "wa";
  if (mode == (std::ios_base::in | std::ios_base::out)) return "rw";
  if (mode == (std::ios_base::in | std::ios_base::out | std::ios_base::trunc)) return "rwt";
  if (mode == (std::ios_base::in | std::ios_base::out | std::ios_base::app)) return "rwa";
  return {};
}
int OpenAndroidContent(std::string_view uri, std::string_view mode) {
  JNIEnv* env = IDCache::GetEnvForThread();
  if (!env || !services) return -1;
  jstring j_uri = ToJString(env, uri);
  jstring j_mode = ToJString(env, mode);
  jint result = env->CallStaticIntMethod(services, open_content_method, j_uri, j_mode);
  env->DeleteLocalRef(j_uri);
  env->DeleteLocalRef(j_mode);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return -1;
  }
  return result;
}
bool DeleteAndroidContent(std::string_view uri) {
  JNIEnv* env = IDCache::GetEnvForThread();
  if (!env || !services) return false;
  jstring j_uri = ToJString(env, uri);
  jboolean result = env->CallStaticBooleanMethod(services, delete_content_method, j_uri);
  env->DeleteLocalRef(j_uri);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  return result == JNI_TRUE;
}
jlong GetAndroidContentSizeAndIsDirectory(std::string_view uri) {
  JNIEnv* env = IDCache::GetEnvForThread();
  if (!env || !services) return -1;
  jstring j_uri = ToJString(env, uri);
  jlong result = env->CallStaticLongMethod(services, content_size_method, j_uri);
  env->DeleteLocalRef(j_uri);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return -1;
  }
  return result;
}
std::string GetAndroidContentDisplayName(std::string_view uri) {
  JNIEnv* env = IDCache::GetEnvForThread();
  if (!env || !services) return {};
  jstring j_uri = ToJString(env, uri);
  jstring j_result = static_cast<jstring>(
      env->CallStaticObjectMethod(services, content_name_method, j_uri));
  env->DeleteLocalRef(j_uri);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return {};
  }
  if (!j_result) return {};
  std::string result = GetJString(env, j_result);
  env->DeleteLocalRef(j_result);
  return result;
}
std::vector<std::string> GetAndroidContentChildNames(std::string_view) { return {}; }
std::vector<std::string> DoFileSearchAndroidContent(std::string_view, std::span<const std::string_view>, bool) { return {}; }
int GetNetworkIpAddress() { return 0; }
int GetNetworkPrefixLength() { return 0; }
int GetNetworkGateway() { return 0; }
