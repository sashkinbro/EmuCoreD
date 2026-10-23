// SPDX-License-Identifier: GPL-3.0-or-later
// EmuCoreD host for the unmodified, pinned Dolphin engine. No upstream UI/JNI.
#include <jni.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <charconv>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "Common/Config/Config.h"
#include "Common/FileUtil.h"
#include "Common/MsgHandler.h"
#include "Common/WindowSystemInfo.h"
#include "Core/Boot/Boot.h"
#include "Core/BootManager.h"
#include "Core/Config/GraphicsSettings.h"
#include "Core/Config/MainSettings.h"
#include "Core/Config/WiimoteSettings.h"
#include "Core/ConfigManager.h"
#include "Core/Core.h"
#include "Core/DolphinAnalytics.h"
#include "Core/Host.h"
#include "Core/HW/GCPad.h"
#include "Core/HW/Wiimote.h"
#include "Core/HW/WiimoteEmu/WiimoteEmu.h"
#include "Core/HW/WiimoteEmu/Extension/Extension.h"
#include "Core/PowerPC/PowerPC.h"
#include "Core/System.h"
#include "DiscIO/Volume.h"
#include "InputCommon/ControllerEmu/ControllerEmu.h"
#include "InputCommon/ControllerEmu/ControlGroup/Attachments.h"
#include "InputCommon/ControllerInterface/Touch/InputOverrider.h"
#include "UICommon/UICommon.h"
#include "VideoCommon/Present.h"
#include "VideoCommon/VideoBackendBase.h"
#include "VideoCommon/VideoEvents.h"
#include "jni/AndroidCommon/AndroidCommon.h"

namespace State {
bool EmuCoreDSaveCompleted(Core::System&, const std::string&);
bool EmuCoreDLoadCompleted(Core::System&, const std::string&);
}
jint EmuCoreDInitializeAndroid(JavaVM*);
namespace {
using namespace std::chrono_literals;
struct Runtime {
  std::mutex api_mutex, mutex;
  std::condition_variable changed;
  std::thread host;
  std::deque<std::function<void()>> commands;
  std::atomic<bool> stop{false}, active{false}, paused{false};
  std::atomic<long long> frames{0};
  int start_result = -1;
  ANativeWindow* surface = nullptr;
  std::vector<ANativeWindow*> retained_windows;
  bool initialized = false;
  bool wii = false;
  bool user_paused = false;
  int profile = 1;
  std::atomic<bool> alert{false};
} runtime;

void Post(std::function<void()> work) {
  std::lock_guard lock(runtime.mutex);
  runtime.commands.emplace_back(std::move(work));
  runtime.changed.notify_all();
}
bool OnHost(std::function<bool()> work) {
  std::lock_guard api_lock(runtime.api_mutex);
  auto result = std::make_shared<std::promise<bool>>();
  auto completed = result->get_future();
  {
    std::lock_guard lock(runtime.mutex);
    if (!runtime.active || runtime.stop) return false;
    runtime.commands.emplace_back([result, work = std::move(work)] { result->set_value(work()); });
    runtime.changed.notify_all();
  }
  return completed.get();
}
void DrainCommands() {
  std::deque<std::function<void()>> work;
  { std::lock_guard lock(runtime.mutex); work.swap(runtime.commands); }
  for (auto& item : work) item();
}
void StartResult(int code) {
  std::lock_guard lock(runtime.mutex);
  runtime.start_result = code;
  runtime.changed.notify_all();
}
bool Alert(const char*, const char*, bool, Common::MsgType) {
  // Engine errors are returned as stable codes; upstream English dialogs never
  // become app UI. Reject prompts which would require a user decision.
  runtime.alert = true;
  return false;
}
std::string OptionKey(std::string key) {
  if (key.starts_with("dolphin.")) key.erase(0, 8);
  return key;
}
bool Option(std::string key, const std::string& value, bool boot) {
  key = OptionKey(std::move(key));
  const auto boolean = [&]() { return value == "true" || value == "1"; };
  const bool valid_bool = value == "true" || value == "false" || value == "1" || value == "0";
  if (key == "graphics_backend" && boot) {
    if (value != "vulkan" && value != "opengl") return false;
    Config::SetCurrent(Config::MAIN_GFX_BACKEND, std::string(value == "vulkan" ? "Vulkan" : "OGL"));
  } else if (key == "cpu_backend" && boot) {
    if (value != "jit" && value != "interpreter") return false;
    Config::SetCurrent(Config::MAIN_CPU_CORE, value == "jit" ? PowerPC::CPUCore::JITARM64 : PowerPC::CPUCore::Interpreter);
  } else if (key == "dual_core" && boot && valid_bool) {
    Config::SetCurrent(Config::MAIN_CPU_THREAD, boolean());
  } else if (key == "dsp_hle" && boot && valid_bool) {
    Config::SetCurrent(Config::MAIN_DSP_HLE, boolean());
  } else if (key == "audio_enabled" && valid_bool) {
    Config::SetCurrent(Config::MAIN_AUDIO_MUTED, !boolean());
  } else if (key == "volume") {
    int volume = 0;
    const auto parsed = std::from_chars(value.data(), value.data() + value.size(), volume);
    if (parsed.ec != std::errc{} || parsed.ptr != value.data() + value.size() || volume < 0 || volume > 100) return false;
    Config::SetCurrent(Config::MAIN_AUDIO_VOLUME, volume);
  } else if (key == "resolution") {
    if (value.size() != 1 || value[0] < '1' || value[0] > '4') return false;
    Config::SetCurrent(Config::GFX_EFB_SCALE, value[0] - '0');
  } else if (key == "speed") {
    float speed = 0;
    const auto parsed = std::from_chars(value.data(), value.data() + value.size(), speed);
    if (parsed.ec != std::errc{} || parsed.ptr != value.data() + value.size() || !std::isfinite(speed) || speed < .1f || speed > 4.f) return false;
    Config::SetCurrent(Config::MAIN_EMULATION_SPEED, speed);
  } else if (key == "wii_profile" && boot) {
    if (value != "remote" && value != "nunchuk" && value != "classic") return false;
    runtime.profile = value == "remote" ? 0 : value == "nunchuk" ? 1 : 2;
  } else {
    return false;
  }
  return true;
}
void ApplySurface() {
  std::lock_guard lock(runtime.mutex);
  if (!runtime.active || !g_presenter) return;
  auto& system = Core::System::GetInstance();
  if (!runtime.surface && Core::GetState(system) == Core::State::Running)
    Core::SetState(system, Core::State::Paused);
  g_presenter->ChangeSurface(runtime.surface);
  g_presenter->ResizeSurface();
  if (runtime.surface && !runtime.user_paused && Core::GetState(system) == Core::State::Paused)
    Core::SetState(system, Core::State::Running);
}
void HostRun(std::string path, std::string user, std::string sys, bool wii, bool paused,
             std::vector<std::pair<std::string, std::string>> options) {
  auto& system = Core::System::GetInstance();
  runtime.wii = wii;
  runtime.profile = 1;
  runtime.user_paused = paused;
  runtime.frames = 0;
  runtime.alert = false;
  static std::once_flag sys_once;
  std::call_once(sys_once, [&] { File::SetSysDirectory(sys + "/"); });
  UICommon::SetUserDirectory(user);
  UICommon::CreateDirectories();
  Common::RegisterMsgAlertHandler(Alert);
  UICommon::Init();
  runtime.initialized = true;
  // Upstream calls this host hook even when reporting is disabled. Our own
  // frontend supplies no device analytics and never installs the HTTP backend.
  DolphinAnalytics::AndroidSetGetValFunc([](std::string) { return std::string{}; });
  Config::SetCurrent(Config::MAIN_ANALYTICS_ENABLED, false);
  Config::SetCurrent(Config::MAIN_GFX_BACKEND, std::string("Vulkan"));
  Config::SetCurrent(Config::MAIN_AUDIO_BACKEND, std::string(BACKEND_CUBEB));
  Config::SetCurrent(Config::MAIN_CPU_CORE, PowerPC::CPUCore::JITARM64);
  Config::SetCurrent(Config::MAIN_DSP_HLE, true);
  Config::SetCurrent(Config::MAIN_SKIP_IPL, true);
  Config::SetCurrent(Config::MAIN_ENABLE_SAVESTATES, true);
  Config::SetCurrent(Config::MAIN_WIIMOTE_CONTINUOUS_SCANNING, false);
  Config::SetCurrent(Config::GFX_SHOW_FPS, false);
  Config::SetCurrent(Config::MAIN_OSD_MESSAGES, false);
  for (const auto& [key, value] : options) {
    if (!Option(key, value, true)) {
      UICommon::Shutdown(); runtime.initialized = false; StartResult(6); return;
    }
  }
  for (int i = 0; i < 4; ++i)
    Config::SetCurrent(Config::GetInfoForWiimoteSource(i), wii && i == 0 ? WiimoteSource::Emulated : WiimoteSource::None);
  VideoBackendBase::ActivateBackend(Config::Get(Config::MAIN_GFX_BACKEND));
  UICommon::InitControllers(WindowSystemInfo(WindowSystemType::Android, nullptr, nullptr, nullptr));
  {
    const auto state_lock = ControllerEmu::EmulatedController::GetStateLock();
    for (int i = 0; i < 4; ++i) {
      ciface::Touch::RegisterGameCubeInputOverrider(i);
      ciface::Touch::RegisterWiiInputOverrider(i);
      auto* attachments = static_cast<ControllerEmu::Attachments*>(Wiimote::GetWiimoteGroup(i, WiimoteEmu::WiimoteGroup::Attachments));
      attachments->SetSelectedAttachment(runtime.profile);
      for (int control = 0; control < ciface::Touch::NUMBER_OF_CONTROLS; ++control)
        ciface::Touch::SetControlState(i, static_cast<ciface::Touch::ControlID>(control), 0);
    }
  }
  auto state_hook = Core::AddOnStateChangedCallback([](Core::State state) {
    runtime.paused = state == Core::State::Paused;
    runtime.changed.notify_all();
  });
  auto frame_hook = GetVideoEvents().after_present_event.Register([](PresentInfo& info) { runtime.frames = info.present_count + 1; });
  SConfig::GetInstance().bBootToPause = paused;
  auto boot = BootParameters::GenerateFromFile(path);
  bool booted = false;
  {
    std::unique_lock lock(runtime.mutex);
    runtime.changed.wait_for(lock, 20s, [] { return runtime.surface || runtime.stop; });
    if (boot && runtime.surface && !runtime.stop) {
      WindowSystemInfo wsi(WindowSystemType::Android, nullptr, runtime.surface, runtime.surface);
      lock.unlock();
      booted = BootManager::BootCore(system, std::move(boot), wsi);
      while (booted && Core::GetState(system) == Core::State::Starting && !runtime.stop) {
        Core::HostDispatchJobs(system);
        std::this_thread::sleep_for(5ms);
      }
    }
  }
  booted = booted && Core::IsRunning(system) && !runtime.stop;
  runtime.active = booted;
  StartResult(booted ? 0 : (runtime.stop ? 5 : 3));
  while (runtime.active && Core::IsRunning(system) && !runtime.stop) {
    DrainCommands();
    Core::HostDispatchJobs(system);
    std::unique_lock lock(runtime.mutex);
    runtime.changed.wait_for(lock, 8ms, [] { return !runtime.commands.empty() || runtime.stop; });
  }
  runtime.active = false;
  // Fulfill accepted synchronous commands before ending their host worker.
  DrainCommands();
  if (Core::IsRunningOrStarting(system)) Core::Stop(system);
  Core::Shutdown(system);
  frame_hook.reset(); state_hook.reset();
  UICommon::ShutdownControllers();
  UICommon::Shutdown();
  runtime.initialized = false;
  runtime.paused = false;
  runtime.changed.notify_all();
}
}

std::vector<std::string> Host_GetPreferredLocales() { return {}; }
void Host_PPCSymbolsChanged() {}
void Host_PPCBreakpointsChanged() {}
bool Host_UIBlocksControllerState() { return false; }
void Host_Message(HostMessageID id) {
  if (id == HostMessageID::WMUserStop) runtime.stop = true;
  runtime.changed.notify_all();
}
void Host_UpdateTitle(const std::string&) {}
void Host_UpdateDiscordClientID(const std::string&) {}
bool Host_UpdateDiscordPresenceRaw(const std::string&, const std::string&, const std::string&, const std::string&,
                                  const std::string&, const std::string&, int64_t, int64_t, int, int) { return false; }
void Host_UpdateDisasmDialog() {}
void Host_JitCacheInvalidation() {}
void Host_JitProfileDataWiped() {}
void Host_RequestRenderWindowSize(int, int) {}
bool Host_RendererHasFocus() { return true; }
bool Host_RendererHasFullFocus() { return true; }
bool Host_RendererIsFullscreen() { return false; }
bool Host_TASInputHasFocus() { return false; }
void Host_YieldToUI() {}
void Host_TitleChanged() {}
std::unique_ptr<GBAHostInterface> Host_CreateGBAHost(std::weak_ptr<HW::GBA::Core>) { return nullptr; }

#define JNI_METHOD(name) Java_com_sbro_emucored_core_dolphin_DolphinNative_##name
extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) { return EmuCoreDInitializeAndroid(vm); }
JNIEXPORT jint JNICALL JNI_METHOD(start)(JNIEnv* env, jobject, jstring path, jstring user, jstring sys,
                                       jboolean wii, jboolean paused, jobjectArray keys, jobjectArray values) {
  std::lock_guard api_lock(runtime.api_mutex);
  if (runtime.host.joinable()) {
    if (runtime.active) return 2;
    runtime.host.join();
  }
  std::vector<std::pair<std::string, std::string>> options;
  const auto count = env->GetArrayLength(keys);
  if (env->GetArrayLength(values) != count) return 6;
  for (jsize i = 0; i < count; ++i) {
    auto key = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
    auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
    options.emplace_back(GetJString(env, key), GetJString(env, value));
    env->DeleteLocalRef(key); env->DeleteLocalRef(value);
  }
  runtime.stop = false;
  runtime.start_result = -1;
  runtime.host = std::thread(HostRun, GetJString(env, path), GetJString(env, user), GetJString(env, sys),
                             bool(wii), bool(paused), std::move(options));
  std::unique_lock lock(runtime.mutex);
  runtime.changed.wait(lock, [] { return runtime.start_result != -1; });
  return runtime.start_result;
}
JNIEXPORT void JNICALL JNI_METHOD(requestStop)(JNIEnv*, jobject) { runtime.stop = true; runtime.changed.notify_all(); }
JNIEXPORT void JNICALL JNI_METHOD(stop)(JNIEnv*, jobject) {
  runtime.stop = true; runtime.changed.notify_all();
  std::lock_guard api_lock(runtime.api_mutex);
  if (runtime.host.joinable()) runtime.host.join();
  std::lock_guard lock(runtime.mutex);
  runtime.commands.clear();
  for (auto* window : runtime.retained_windows) ANativeWindow_release(window);
  runtime.retained_windows.clear();
  if (runtime.surface) ANativeWindow_release(runtime.surface);
  runtime.surface = nullptr;
}
JNIEXPORT jint JNICALL JNI_METHOD(status)(JNIEnv*, jobject) { return runtime.active ? (runtime.paused ? 2 : 1) : 0; }
JNIEXPORT jboolean JNICALL JNI_METHOD(pause)(JNIEnv*, jobject, jboolean pause) {
  return OnHost([pause] {
    runtime.user_paused = pause;
    bool has_surface;
    { std::lock_guard lock(runtime.mutex); has_surface = runtime.surface != nullptr; }
    Core::SetState(Core::System::GetInstance(), pause || !has_surface ? Core::State::Paused : Core::State::Running);
    return true;
  });
}
JNIEXPORT void JNICALL JNI_METHOD(surface)(JNIEnv* env, jobject, jobject surface, jint, jint) {
  auto* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
  {
    std::lock_guard lock(runtime.mutex);
    if (runtime.surface) runtime.retained_windows.push_back(runtime.surface);
    runtime.surface = window;
    if (runtime.active) runtime.commands.emplace_back(ApplySurface);
    runtime.changed.notify_all();
  }
}
JNIEXPORT void JNICALL JNI_METHOD(control)(JNIEnv*, jobject, jint player, jint control, jdouble value) {
  if (!runtime.active || player < 0 || player > 3 || control < 0 || control >= ciface::Touch::NUMBER_OF_CONTROLS) return;
  const auto lock = ControllerEmu::EmulatedController::GetStateLock();
  if (runtime.active)
    ciface::Touch::SetControlState(player, static_cast<ciface::Touch::ControlID>(control), std::clamp(double(value), -1., 1.));
}
JNIEXPORT jboolean JNICALL JNI_METHOD(speed)(JNIEnv*, jobject, jfloat speed) {
  if (!std::isfinite(speed) || speed < .1f || speed > 4.f) return false;
  return OnHost([speed] { Config::SetCurrent(Config::MAIN_EMULATION_SPEED, float(speed)); return true; });
}
JNIEXPORT jboolean JNICALL JNI_METHOD(state)(JNIEnv* env, jobject, jstring file, jboolean save) {
  const auto path = GetJString(env, file);
  return OnHost([path, save] {
    if (!Core::IsRunning(Core::System::GetInstance())) return false;
    return save ? State::EmuCoreDSaveCompleted(Core::System::GetInstance(), path)
                : State::EmuCoreDLoadCompleted(Core::System::GetInstance(), path);
  });
}
JNIEXPORT jboolean JNICALL JNI_METHOD(option)(JNIEnv* env, jobject, jstring key, jstring value) {
  const auto k = GetJString(env, key), v = GetJString(env, value);
  return OnHost([k, v] { return Option(k, v, false); });
}
JNIEXPORT jlong JNICALL JNI_METHOD(frames)(JNIEnv*, jobject) { return runtime.frames; }
JNIEXPORT jint JNICALL JNI_METHOD(probe)(JNIEnv* env, jobject, jstring path) {
  auto volume = DiscIO::CreateVolume(GetJString(env, path));
  if (!volume) return 0;
  return volume->GetVolumeType() == DiscIO::Platform::GameCubeDisc ? 1 :
         volume->GetVolumeType() == DiscIO::Platform::WiiDisc ? 2 : 0;
}
}
