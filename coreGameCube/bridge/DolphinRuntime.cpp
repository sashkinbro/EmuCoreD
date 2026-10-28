// SPDX-License-Identifier: GPL-3.0-or-later
// EmuCoreD host for the unmodified, pinned Dolphin engine. No upstream UI/JNI.
#include <jni.h>
#include <android/log.h>
#include "../../core-common/typed_cheats.h"
#include <android/native_window_jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <charconv>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "Common/Config/Config.h"
#include "Common/FileUtil.h"
#include "Common/IOFile.h"
#include "Common/MsgHandler.h"
#include "Common/WindowSystemInfo.h"
#include "Core/Boot/Boot.h"
#include "Core/ActionReplay.h"
#include "Core/GeckoCode.h"
#include "Core/Boot/DolReader.h"
#include "Core/Boot/ElfReader.h"
#include "Core/BootManager.h"
#include "Core/Config/GraphicsSettings.h"
#include "Core/Config/MainSettings.h"
#include "Core/Config/WiimoteSettings.h"
#include "Core/ConfigManager.h"
#include "Core/Core.h"
#include "Core/DolphinAnalytics.h"
#include "Core/Host.h"
#include "Core/HW/GCPad.h"
#include "Core/HW/VideoInterface.h"
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
#include "VideoCommon/PerformanceMetrics.h"
#include "VideoCommon/AsyncRequests.h"
#include "VideoCommon/VideoBackendBase.h"
#include "VideoCommon/VideoConfig.h"
#include "VideoCommon/VideoEvents.h"
#ifdef HAS_VULKAN
#include "VideoBackends/Vulkan/VulkanContext.h"
#endif
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
  std::atomic<long long> unique_frames{0};
  std::atomic<double> target_refresh{0};
  std::atomic<unsigned> output_width{0}, output_height{0};
  std::atomic<int> backend{0};
  int start_result = -1;
  ANativeWindow* surface = nullptr;
  std::vector<ANativeWindow*> retained_windows;
  bool initialized = false;
  bool wii = false;
  bool user_paused = false;
  std::string gpu_driver_name;
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
// Bind accepted public values directly to typed upstream Config entries. Readback
// always queries Config::Get; no shadow option map can report a phantom success.
struct OptionBinding {
  std::string key;
  bool restart;
  std::vector<std::string> values;
  std::function<void(const std::string&)> write;
  std::function<std::string()> read;
};
template <typename T>
OptionBinding BindOption(const char* key, const Config::Info<T>& info,
                         std::initializer_list<std::pair<const char*, T>> choices,
                         bool restart = false) {
  std::vector<std::pair<std::string, T>> values;
  std::vector<std::string> names;
  for (const auto& [name, value] : choices) { values.emplace_back(name, value); names.emplace_back(name); }
  return {key, restart, std::move(names),
          [&info, values](const std::string& name) {
            for (const auto& [candidate, value] : values)
              if (name == candidate) { Config::SetCurrent(info, value); return; }
          },
          [&info, values] {
            const T value = Config::Get(info);
            for (const auto& [name, candidate] : values) if (candidate == value) return name;
            return std::string{};
          }};
}
OptionBinding BoolOption(const char* key, const Config::Info<bool>& info, bool restart = false,
                         bool invert = false) {
  return BindOption<bool>(key, info, {{"true", !invert}, {"false", invert}}, restart);
}
OptionBinding IntOption(const char* key, const Config::Info<int>& info, int first, int last,
                        int step = 1, bool restart = false) {
  std::vector<std::string> values;
  for (int value = first; value <= last; value += step) values.push_back(std::to_string(value));
  return {key, restart, std::move(values),
          [&info](const std::string& value) { Config::SetCurrent(info, std::stoi(value)); },
          [&info] { return std::to_string(Config::Get(info)); }};
}
const std::vector<OptionBinding>& OptionBindings() {
  static const std::vector<OptionBinding> bindings = [] {
    std::vector<OptionBinding> b{
      BindOption<std::string>("graphics_backend", Config::MAIN_GFX_BACKEND, {{"vulkan", "Vulkan"}, {"opengl", "OGL"}}, true),
      IntOption("resolution", Config::GFX_EFB_SCALE, 1, 12),
      BindOption<AspectMode>("aspect_ratio", Config::GFX_ASPECT_RATIO,
          {{"auto", AspectMode::Auto}, {"4:3", AspectMode::ForceStandard}, {"16:9", AspectMode::ForceWide},
           {"stretch", AspectMode::Stretch}, {"pixel", AspectMode::Raw}}),
      BoolOption("widescreen_hack", Config::GFX_WIDESCREEN_HACK),
      BindOption<u32>("msaa", Config::GFX_MSAA, {{"1", 1}, {"2", 2}, {"4", 4}, {"8", 8}}),
      BindOption<AnisotropicFilteringMode>("anisotropy", Config::GFX_ENHANCE_MAX_ANISOTROPY,
          {{"default", AnisotropicFilteringMode::Default}, {"1", AnisotropicFilteringMode::Force1x},
           {"2", AnisotropicFilteringMode::Force2x}, {"4", AnisotropicFilteringMode::Force4x},
           {"8", AnisotropicFilteringMode::Force8x}, {"16", AnisotropicFilteringMode::Force16x}}),
      BindOption<TextureFilteringMode>("texture_filtering", Config::GFX_ENHANCE_FORCE_TEXTURE_FILTERING,
          {{"default", TextureFilteringMode::Default}, {"nearest", TextureFilteringMode::Nearest}, {"linear", TextureFilteringMode::Linear}}),
      BindOption<ShaderCompilationMode>("shader_compilation", Config::GFX_SHADER_COMPILATION_MODE,
          {{"specialized", ShaderCompilationMode::Synchronous}, {"hybrid", ShaderCompilationMode::AsynchronousUberShaders},
           {"exclusive", ShaderCompilationMode::SynchronousUberShaders}, {"skip", ShaderCompilationMode::AsynchronousSkipRendering}}, true),
      BoolOption("shader_cache", Config::GFX_SHADER_CACHE, true),
      BoolOption("wait_for_shaders", Config::GFX_WAIT_FOR_SHADERS_BEFORE_STARTING, true),
      BoolOption("vsync", Config::GFX_VSYNC),
      BoolOption("backend_multithreading", Config::GFX_BACKEND_MULTITHREADING, true),
      BoolOption("skip_efb_access", Config::GFX_HACK_EFB_ACCESS_ENABLE, false, true),
      BoolOption("efb_texture_only", Config::GFX_HACK_SKIP_EFB_COPY_TO_RAM),
      BoolOption("xfb_texture_only", Config::GFX_HACK_SKIP_XFB_COPY_TO_RAM),
      BoolOption("defer_efb_copies", Config::GFX_HACK_DEFER_EFB_COPIES),
      BoolOption("immediate_xfb", Config::GFX_HACK_IMMEDIATE_XFB),
      BoolOption("skip_duplicate_xfbs", Config::GFX_HACK_SKIP_DUPLICATE_XFBS),
      BoolOption("scaled_efb_copy", Config::GFX_HACK_COPY_EFB_SCALED),
      BoolOption("ignore_format_changes", Config::GFX_HACK_EFB_EMULATE_FORMAT_CHANGES, false, true),
      BindOption<int>("texture_cache_accuracy", Config::GFX_SAFE_TEXTURE_CACHE_COLOR_SAMPLES,
          {{"safe", 0}, {"balanced", 128}, {"fast", 512}}),
      BoolOption("gpu_texture_decoding", Config::GFX_ENABLE_GPU_TEXTURE_DECODING),
      BoolOption("fast_depth", Config::GFX_FAST_DEPTH_CALC),
      BoolOption("disable_bbox", Config::GFX_HACK_BBOX_ENABLE, false, true),
      BoolOption("vertex_rounding", Config::GFX_HACK_VERTEX_ROUNDING),
      BoolOption("disable_fog", Config::GFX_DISABLE_FOG),
      BoolOption("disable_copy_filter", Config::GFX_ENHANCE_DISABLE_COPY_FILTER),
      BoolOption("force_true_color", Config::GFX_ENHANCE_FORCE_TRUE_COLOR),
      BoolOption("per_pixel_lighting", Config::GFX_ENABLE_PIXEL_LIGHTING),
      BoolOption("hires_textures", Config::GFX_HIRES_TEXTURES),
      BoolOption("cache_hires_textures", Config::GFX_CACHE_HIRES_TEXTURES),
      BoolOption("save_texture_cache", Config::GFX_SAVE_TEXTURE_CACHE_TO_STATE),
      BindOption<PowerPC::CPUCore>("cpu_backend", Config::MAIN_CPU_CORE,
          {{"jit", PowerPC::CPUCore::JITARM64}, {"cached_interpreter", PowerPC::CPUCore::CachedInterpreter},
           {"interpreter", PowerPC::CPUCore::Interpreter}}, true),
      BoolOption("dual_core", Config::MAIN_CPU_THREAD, true),
      BoolOption("mmu", Config::MAIN_MMU, true),
      BoolOption("accurate_cpu_cache", Config::MAIN_ACCURATE_CPU_CACHE, true),
      BoolOption("fprf", Config::MAIN_FPRF, true),
      BoolOption("accurate_nans", Config::MAIN_ACCURATE_NANS, true),
      BoolOption("accurate_fmadds", Config::MAIN_ACCURATE_FMADDS, true),
      BoolOption("fastmem", Config::MAIN_FASTMEM, true),
      BoolOption("fast_disc", Config::MAIN_FAST_DISC_SPEED, true),
      BoolOption("dsp_hle", Config::MAIN_DSP_HLE, true),
      BoolOption("dsp_jit", Config::MAIN_DSP_JIT, true),
      BoolOption("dsp_thread", Config::MAIN_DSP_THREAD, true),
      BindOption<std::string>("audio_backend", Config::MAIN_AUDIO_BACKEND,
          {{"cubeb", BACKEND_CUBEB}, {"opensles", BACKEND_OPENSLES}}, true),
      BoolOption("audio_enabled", Config::MAIN_AUDIO_MUTED, false, true),
      IntOption("volume", Config::MAIN_AUDIO_VOLUME, 0, 100),
      IntOption("audio_buffer_size", Config::MAIN_AUDIO_BUFFER_SIZE, 20, 200, 10),
      BoolOption("audio_preserve_pitch", Config::MAIN_AUDIO_PRESERVE_PITCH),
      BoolOption("audio_fill_gaps", Config::MAIN_AUDIO_FILL_GAPS),
      BindOption<int>("gc_language", Config::MAIN_GC_LANGUAGE,
          {{"english", 0}, {"german", 1}, {"french", 2}, {"spanish", 3}, {"italian", 4}, {"dutch", 5}}, true),
      BoolOption("wii_speaker", Config::MAIN_WIIMOTE_ENABLE_SPEAKER, true),
    };
    std::vector<std::string> speeds, clocks;
    for (int i = 1; i <= 40; ++i) speeds.push_back(std::to_string(i / 10) + "." + std::to_string(i % 10));
    b.push_back({"speed", false, std::move(speeds),
      [](const std::string& v) { Config::SetCurrent(Config::MAIN_EMULATION_SPEED, std::stof(v)); },
      [] { const int tenth = std::lround(Config::Get(Config::MAIN_EMULATION_SPEED) * 10);
           return std::to_string(tenth / 10) + "." + std::to_string(tenth % 10); }});
    for (int i = 10; i <= 400; i += 10) clocks.push_back(std::to_string(i));
    b.push_back({"cpu_clock", true, std::move(clocks),
      [](const std::string& v) {
        Config::SetCurrent(Config::MAIN_OVERCLOCK_ENABLE, v != "100");
        Config::SetCurrent(Config::MAIN_OVERCLOCK, std::stof(v) / 100.f);
      }, [] { return std::to_string(Config::Get(Config::MAIN_OVERCLOCK_ENABLE) ?
                       int(std::lround(Config::Get(Config::MAIN_OVERCLOCK) * 100)) : 100); }});
    return b;
  }();
  return bindings;
}
const OptionBinding* FindOption(const std::string& key) {
  for (const auto& binding : OptionBindings()) if (binding.key == key) return &binding;
  return nullptr;
}
bool OptionPlatform(const std::string& key) {
  return !(key == "gc_language" && runtime.wii) &&
         !((key == "wii_speaker" || key == "wii_profile") && !runtime.wii);
}
bool Option(std::string key, const std::string& value, bool boot) {
  key = OptionKey(std::move(key));
  if (!OptionPlatform(key)) return false;
  if (key == "wii_profile") {
    if (!boot || (value != "remote" && value != "nunchuk" && value != "classic")) return false;
    runtime.profile = value == "remote" ? 0 : value == "nunchuk" ? 1 : 2;
    return true;
  }
  const auto* binding = FindOption(key);
  if (!binding || (!boot && binding->restart) ||
      std::find(binding->values.begin(), binding->values.end(), value) == binding->values.end()) return false;
  // Device-dependent graphics choices must never acknowledge a live value
  // which VideoConfig::VerifyValidity would silently replace.
  if (!boot && key == "msaa" && std::find(g_backend_info.AAModes.begin(), g_backend_info.AAModes.end(),
                                         u32(std::stoi(value))) == g_backend_info.AAModes.end()) return false;
  if (!boot && key == "gpu_texture_decoding" && value == "true" && !g_backend_info.bSupportsGPUTextureDecoding) return false;
  if (boot) {
    binding->write(value);
    return binding->read() == value;
  }
  // Config's CPU callback refreshes g_Config, but the normal GPU promotion to
  // g_ActiveConfig is driven by GX EFB copies. VI-only titles can keep scanning
  // the same RAM XFB forever without issuing a GX frame. Apply on the CPU first
  // (so its Config callbacks run synchronously), then ask the GPU to consume the
  // change. AsyncRequests also services jobs while paused. Never write video
  // configuration or recreate GPU resources directly from this host thread.
  auto result = std::make_shared<std::promise<bool>>();
  auto completed = result->get_future();
  Core::RunOnCPUThread(Core::System::GetInstance(), [binding, value, result] {
    binding->write(value);
    AsyncRequests::GetInstance()->PushBlockingEvent([] { CheckForConfigChanges(); });
    result->set_value(binding->read() == value);
  });
  return completed.get();
}
std::string ReadOption(std::string key) {
  key = OptionKey(std::move(key));
  if (!OptionPlatform(key)) return {};
  if (key == "wii_profile") {
    const auto state_lock = ControllerEmu::EmulatedController::GetStateLock();
    auto* attachment = static_cast<ControllerEmu::Attachments*>(Wiimote::GetWiimoteGroup(0, WiimoteEmu::WiimoteGroup::Attachments));
    const int profile = attachment->GetSelectedAttachment();
    return profile == 0 ? "remote" : profile == 1 ? "nunchuk" : profile == 2 ? "classic" : "";
  }
  const auto* binding = FindOption(key);
  return binding ? binding->read() : std::string{};
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
std::unique_ptr<BootParameters> BootFromImage(const std::string& path) {
  // SAF descriptors deliberately have no extension. Let real container headers
  // identify compressed discs and WAD packages, without copying gigabytes.
  if (auto disc = DiscIO::CreateDiscForCore(path))
    return std::make_unique<BootParameters>(BootParameters::Disc{path, std::move(disc), {}});
  if (auto wad = DiscIO::CreateWAD(path))
    return std::make_unique<BootParameters>(std::move(*wad));

  File::IOFile input(path, "rb");
  if (!input) return {};
  const auto length = input.GetSize();
  std::array<u8, 256> header{};
  const auto count = static_cast<size_t>(std::min<u64>(header.size(), length));
  if (count < 52 || !input.ReadBytes(header.data(), count)) return {};
  if (header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F' &&
      header[4] == 1 && header[5] == 2 && header[18] == 0 && header[19] == 20) {
    auto reader = std::make_unique<ElfReader>(path);
    if (reader->IsValid())
      return std::make_unique<BootParameters>(BootParameters::Executable{path, std::move(reader)});
    return {};
  }
  if (count < header.size()) return {};
  const auto word = [&header](size_t offset) {
    return (u32(header[offset]) << 24) | (u32(header[offset + 1]) << 16) |
           (u32(header[offset + 2]) << 8) | u32(header[offset + 3]);
  };
  const auto ram = [](u32 address) {
    const u32 physical = address & 0x3fffffff;
    return (address & 0xc0000000) != 0x40000000 &&
           (physical < 0x01800000 || (physical >= 0x10000000 && physical < 0x14000000));
  };
  if (!ram(word(0xe0))) return {};
  bool hasSection = false;
  for (size_t section = 0; section < 18; ++section) {
    const auto size = word(0x90 + section * 4);
    if (!size) continue;
    hasSection = true;
    const auto offset = word(section * 4), address = word(0x48 + section * 4);
    if (offset < header.size() || u64(offset) + size > length || !ram(address) ||
        u64(address) + size > 0x100000000ULL || !ram(address + size - 1)) return {};
  }
  if (!hasSection) return {};
  auto reader = std::make_unique<DolReader>(path);
  if (!reader->IsValid()) return {};
  return std::make_unique<BootParameters>(BootParameters::Executable{path, std::move(reader)});
}
void HostRun(std::string path, std::string user, std::string sys, bool wii, bool paused,
             std::vector<std::pair<std::string, std::string>> options) {
  auto& system = Core::System::GetInstance();
  runtime.wii = wii;
  runtime.profile = 1;
  runtime.user_paused = paused;
  runtime.frames = 0;
  runtime.unique_frames = 0;
  runtime.target_refresh = 0;
  runtime.output_width = 0;
  runtime.output_height = 0;
  runtime.backend = 0;
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
  Config::SetCurrent(Config::MAIN_ENABLE_CHEATS, false);
  Config::SetCurrent(Config::MAIN_GFX_BACKEND, std::string("Vulkan"));
  Config::SetCurrent(Config::MAIN_AUDIO_BACKEND, std::string(BACKEND_CUBEB));
  Config::SetCurrent(Config::MAIN_CPU_CORE, PowerPC::CPUCore::JITARM64);
  Config::SetCurrent(Config::MAIN_DSP_HLE, true);
  Config::SetCurrent(Config::MAIN_SKIP_IPL, true);
  Config::SetCurrent(Config::MAIN_ENABLE_SAVESTATES, true);
  Config::SetCurrent(Config::MAIN_WIIMOTE_CONTINUOUS_SCANNING, false);
  Config::SetCurrent(Config::GFX_SHOW_FPS, false);
  Config::SetCurrent(Config::GFX_DRIVER_LIB_NAME, runtime.gpu_driver_name);
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
  auto frame_hook = GetVideoEvents().after_present_event.Register([](PresentInfo& info) {
    runtime.frames = info.present_count + 1;
    runtime.unique_frames = info.frame_count;
    runtime.output_width = info.frame_buffer_width;
    runtime.output_height = info.frame_buffer_height;
  });
  auto refresh_hook = GetVideoEvents().vi_end_field_event.Register([] {
    runtime.target_refresh = Core::System::GetInstance().GetVideoInterface().GetTargetRefreshRate();
  });
  SConfig::GetInstance().bBootToPause = paused;
  auto boot = BootFromImage(path);
  bool booted = false, unsupported_hardware_option = false;
  {
    std::unique_lock lock(runtime.mutex);
    runtime.changed.wait_for(lock, 20s, [] { return runtime.surface || runtime.stop; });
    if (boot && runtime.surface && !runtime.stop) {
      WindowSystemInfo wsi(WindowSystemType::Android, nullptr, runtime.surface, runtime.surface);
      lock.unlock();
      // Query this selected backend before starting CPU/video emulation. A
      // requested hardware feature must not silently become a different value.
      VideoBackendBase::PopulateBackendInfo(wsi);
      for (const auto& [raw_key, value] : options) {
        const auto key = OptionKey(raw_key);
        if (key == "msaa" && value != "1" &&
            std::find(g_backend_info.AAModes.begin(), g_backend_info.AAModes.end(),
                      u32(std::stoi(value))) == g_backend_info.AAModes.end())
          unsupported_hardware_option = true;
        if (key == "gpu_texture_decoding" && value == "true" && !g_backend_info.bSupportsGPUTextureDecoding)
          unsupported_hardware_option = true;
      }
      if (!unsupported_hardware_option)
        booted = BootManager::BootCore(system, std::move(boot), wsi);
      while (booted && Core::GetState(system) == Core::State::Starting && !runtime.stop) {
        Core::HostDispatchJobs(system);
        std::this_thread::sleep_for(5ms);
      }
    }
  }
  booted = booted && Core::IsRunning(system) && !runtime.stop;
  runtime.active = booted;
  if (booted && g_video_backend) {
    const auto name = g_video_backend->GetConfigName();
    runtime.backend = name == "Vulkan" ? 1 : name == "OGL" ? 2 : 0;
  }
  StartResult(booted ? 0 : (runtime.stop ? 5 : unsupported_hardware_option ? 6 : 3));
  while (runtime.active && Core::IsRunning(system) && !runtime.stop) {
    DrainCommands();
    Core::HostDispatchJobs(system);
    std::unique_lock lock(runtime.mutex);
    runtime.changed.wait_for(lock, 8ms, [] { return !runtime.commands.empty() || runtime.stop; });
  }
  runtime.active = false;
  // Fulfill accepted synchronous commands before ending their host worker.
  DrainCommands();
#if EMUCORED_DIAGNOSTICS
  __android_log_print(ANDROID_LOG_INFO, "DolphinRuntime", "HostRun stop: Core::Stop begin");
#endif
  if (Core::IsRunningOrStarting(system)) Core::Stop(system);
#if EMUCORED_DIAGNOSTICS
  __android_log_print(ANDROID_LOG_INFO, "DolphinRuntime", "HostRun stop: Core::Shutdown begin");
#endif
  Core::Shutdown(system);
#if EMUCORED_DIAGNOSTICS
  __android_log_print(ANDROID_LOG_INFO, "DolphinRuntime", "HostRun stop: UICommon::Shutdown begin");
#endif
  refresh_hook.reset(); frame_hook.reset(); state_hook.reset();
  UICommon::ShutdownControllers();
  UICommon::Shutdown();
#if EMUCORED_DIAGNOSTICS
  __android_log_print(ANDROID_LOG_INFO, "DolphinRuntime", "HostRun stop: complete");
#endif
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
JNIEXPORT jint JNICALL JNI_METHOD(activeGpuDriverId)(JNIEnv*, jobject) {
  int id = -1;
#ifdef HAS_VULKAN
  OnHost([&] {
    if (Vulkan::g_vulkan_context) id = static_cast<int>(Vulkan::g_vulkan_context->GetDeviceInfo().driverID);
    return true;
  });
#endif
  return id;
}
JNIEXPORT jboolean JNICALL JNI_METHOD(configureGpuDriver)(JNIEnv* env, jobject, jstring root, jstring hook, jstring name) {
  std::lock_guard api_lock(runtime.api_mutex);
  if (runtime.active || runtime.host.joinable()) return false;
  const auto directory = GetJString(env, root);
  const auto hook_directory = GetJString(env, hook);
  if (directory.empty() || hook_directory.empty()) return false;
  static std::once_flag driver_once;
  std::call_once(driver_once, [&] { File::SetGpuDriverDirectories(directory, hook_directory); });
  runtime.gpu_driver_name = GetJString(env, name);
  return true;
}
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
#if EMUCORED_DIAGNOSTICS
  __android_log_print(ANDROID_LOG_INFO, "DolphinRuntime", "stop: request");
#endif
  runtime.stop = true; runtime.changed.notify_all();
  std::lock_guard api_lock(runtime.api_mutex);
  if (runtime.host.joinable()) runtime.host.join();
#if EMUCORED_DIAGNOSTICS
  __android_log_print(ANDROID_LOG_INFO, "DolphinRuntime", "stop: joined");
#endif
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
JNIEXPORT jstring JNICALL JNI_METHOD(optionValue)(JNIEnv* env, jobject, jstring key) {
  const auto k = GetJString(env, key);
  std::string value;
  if (!OnHost([&] { value = ReadOption(k); return !value.empty(); })) return nullptr;
  return env->NewStringUTF(value.c_str());
}
// Each row is key<TAB>boot|live<TAB>comma-separated accepted values.
// Device-dependent capabilities come from the initialized video backend.
JNIEXPORT jobjectArray JNICALL JNI_METHOD(optionCapabilities)(JNIEnv* env, jobject) {
  std::vector<std::string> rows;
  OnHost([&] {
    for (const auto& binding : OptionBindings()) {
      if (!OptionPlatform(binding.key)) continue;
      std::string row = binding.key + (binding.restart ? "\tboot\t" : "\tlive\t");
      bool first = true;
      for (const auto& value : binding.values) {
        if (binding.key == "msaa" && std::find(g_backend_info.AAModes.begin(), g_backend_info.AAModes.end(),
                                              u32(std::stoi(value))) == g_backend_info.AAModes.end()) continue;
        if (binding.key == "gpu_texture_decoding" && value == "true" && !g_backend_info.bSupportsGPUTextureDecoding) continue;
        if (!first) row += ',';
        row += value;
        first = false;
      }
      rows.push_back(std::move(row));
    }
    if (runtime.wii) rows.emplace_back("wii_profile\tboot\tremote,nunchuk,classic");
    return true;
  });
  auto result = env->NewObjectArray(jsize(rows.size()), env->FindClass("java/lang/String"), nullptr);
  for (jsize i = 0; i < jsize(rows.size()); ++i) {
    auto row = env->NewStringUTF(rows[i].c_str());
    env->SetObjectArrayElement(result, i, row);
    env->DeleteLocalRef(row);
  }
  return result;
}
JNIEXPORT jlong JNICALL JNI_METHOD(frames)(JNIEnv*, jobject) { return runtime.frames; }
// Atomic presentation/VI observations and upstream's thread-safe emulated-clock speed.
JNIEXPORT jdoubleArray JNICALL JNI_METHOD(performance)(JNIEnv* env, jobject) {
  if (!runtime.active || runtime.paused) return nullptr;
  const jdouble values[] = {static_cast<double>(runtime.unique_frames.load()),
      runtime.target_refresh.load(), Core::System::GetInstance().GetPerfMetrics().GetSpeed() * 100.0,
      static_cast<double>(runtime.output_width.load()), static_cast<double>(runtime.output_height.load()),
      static_cast<double>(runtime.backend.load())};
  auto result = env->NewDoubleArray(6);
  if (result) env->SetDoubleArrayRegion(result, 0, 6, values);
  return result;
}
JNIEXPORT jboolean JNICALL JNI_METHOD(cheats)(JNIEnv* env,jobject,jobjectArray formats,jobjectArray codes) {
  std::vector<TypedCheatInput> inputs;if(!typedCheatInputs(env,formats,codes,inputs))return false;
  std::vector<ActionReplay::ARCode> ar;std::vector<Gecko::GeckoCode> gecko;
  for(const auto& block:inputs) {
    if(block.format!="dolphin-action-replay" && block.format!="dolphin-gecko")return false;
    ActionReplay::ARCode a;Gecko::GeckoCode g;a.enabled=g.enabled=true;a.user_defined=g.user_defined=true;
    a.name=g.name="EmuCoreD";
    for(const auto& line:block.lines) {
      if(!cheatLineMatches(line,"[0-9A-F]{8} [0-9A-F]{8}"))return false;
      const auto address=static_cast<u32>(std::stoul(line.substr(0,8),nullptr,16));
      const auto value=static_cast<u32>(std::stoul(line.substr(9,8),nullptr,16));
      if(block.format=="dolphin-action-replay")a.ops.emplace_back(address,value);
      else g.codes.push_back({address,value,line});
    }
    if(block.format=="dolphin-action-replay")ar.push_back(std::move(a));else gecko.push_back(std::move(g));
  }
  return OnHost([ar=std::move(ar),gecko=std::move(gecko)] {
    const auto& config=SConfig::GetInstance();
    // AR refuses a clear while the global master switch is false.
    Config::SetCurrent(Config::MAIN_ENABLE_CHEATS,true);
    ActionReplay::ApplyCodes(ar,config.GetGameID(),config.GetRevision());
    Gecko::SetActiveCodes(gecko,config.GetGameID(),config.GetRevision());
    Config::SetCurrent(Config::MAIN_ENABLE_CHEATS,!ar.empty() || !gecko.empty());
    return true;
  });
}
JNIEXPORT jobjectArray JNICALL JNI_METHOD(probeIdentity)(JNIEnv* env,jobject,jstring path) {
  auto volume=DiscIO::CreateVolume(GetJString(env,path));if(!volume)return nullptr;
  const auto platform=volume->GetVolumeType();
  if(platform!=DiscIO::Platform::GameCubeDisc && platform!=DiscIO::Platform::WiiDisc && platform!=DiscIO::Platform::WiiWAD)return nullptr;
  const auto revision=volume->GetRevision();const auto title=volume->GetTitleID();
  char titleId[17]{};if(title)snprintf(titleId,sizeof(titleId),"%016llX",static_cast<unsigned long long>(*title));
  const std::array<std::string,4> values{platform==DiscIO::Platform::GameCubeDisc?"GAMECUBE":"WII",volume->GetGameID(),revision?std::to_string(*revision):"",titleId};
  auto cls=env->FindClass("java/lang/String");if(!cls)return nullptr;
  auto result=env->NewObjectArray(values.size(),cls,nullptr);env->DeleteLocalRef(cls);
  if(!result)return nullptr;
  for(jsize i=0;i<jsize(values.size());++i){auto item=env->NewStringUTF(values[i].c_str());env->SetObjectArrayElement(result,i,item);env->DeleteLocalRef(item);}
  return result;
}
JNIEXPORT jint JNICALL JNI_METHOD(probe)(JNIEnv* env, jobject, jstring path) {
  const auto file = GetJString(env, path);
  auto volume = DiscIO::CreateVolume(file);
  if (!volume) {
    auto boot = BootFromImage(file);
    if (!boot) return 0;
    if (auto* executable = std::get_if<BootParameters::Executable>(&boot->parameters))
      return executable->reader->IsWii() ? 2 : 1;
    return 0;
  }
  return volume->GetVolumeType() == DiscIO::Platform::GameCubeDisc ? 1 :
         (volume->GetVolumeType() == DiscIO::Platform::WiiDisc ||
          volume->GetVolumeType() == DiscIO::Platform::WiiWAD) ? 2 : 0;
}
}
