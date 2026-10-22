#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct ANativeWindow;
struct AAudioStreamStruct;
using AAudioStream = AAudioStreamStruct;

namespace melonDS {
class NDS;
}

struct Frame;

namespace MelonDSAndroid {
class VulkanOutput;
class VulkanSurfacePresenter;
}

namespace EmuCoreDS {

class DsPresenter;

class DsEmulator {
public:
    DsEmulator();
    ~DsEmulator();

    bool Start(const std::string& rom_path, const std::string& sram_path);
    void Stop();
    void Reset();

    void SetSurface(ANativeWindow* window);
    void SetSwapScreens(bool swap);
    void SetScreenLayout(int mode);
    uint64_t FrameCount() const { return frame_count_.load(); }
    void SetSecondarySurface(ANativeWindow* window);
    void SetKeyMask(uint32_t mask);
    void TouchScreen(uint16_t x, uint16_t y);
    void ReleaseTouch();
    void SetRenderScale(int scale);
    void SetPaused(bool paused);
    bool SaveState(const std::string& path);
    bool LoadState(const std::string& path);

    bool IsRunning() const { return running_.load(); }
    void SetUserDirectory(const std::string& path);

private:
    void Run();
    void StartAudio();
    void StopAudio();
    void DrainAudio();
    void ApplyDefaultBios();
    void PresentFrame();
    void PresentVulkanFrame();
    void ApplyRenderScaleLocked();
    bool PerformStateRequest(const std::string& path, bool save);
    bool WriteStateFile(const std::string& path);
    bool ReadStateFile(const std::string& path);
    void AttachVulkanSurfaceLocked();
    void DetachVulkanSurfaceLocked();
    void ConfigureVulkanSurfaceLocked();

    std::unique_ptr<melonDS::NDS> nds_;
    std::unique_ptr<MelonDSAndroid::VulkanOutput> vulkan_output_;
    std::unique_ptr<MelonDSAndroid::VulkanSurfacePresenter> surface_presenter_;
    std::unique_ptr<Frame> vulkan_frame_;
    std::vector<uint32_t> vulkan_readback_;
    AAudioStream* audio_stream_ = nullptr;
    std::vector<int16_t> audio_buffer_;
    std::atomic<float> audio_volume_{1.0f};
    std::unique_ptr<DsPresenter> presenter_;
    std::unique_ptr<DsPresenter> secondary_presenter_;
    std::thread thread_;
    std::mutex surface_mutex_;
    std::mutex vulkan_present_mutex_;
    ANativeWindow* window_ = nullptr;
    ANativeWindow* secondary_window_ = nullptr;
    int vulkan_surface_id_ = 0;
    std::atomic<bool> running_{false};
    std::atomic<bool> stop_requested_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> swap_screens_{false};
    std::atomic<int> screen_layout_{0};
    std::atomic<int> requested_scale_{1};
    std::atomic<int> active_scale_{1};
    std::atomic<int> pending_scale_{0};
    std::atomic<uint64_t> frame_count_{0};
    std::mutex state_call_mutex_;
    std::mutex state_mutex_;
    std::condition_variable state_cv_;
    std::atomic<int> state_pending_{0};
    bool state_finished_ = false;
    bool state_success_ = false;
    std::string state_path_;
    std::string rom_path_;
    std::string sram_path_;
    std::string user_dir_;
    std::vector<uint32_t> composite_;
    uint32_t pending_key_mask_ = 0xFFF;
};

DsEmulator& Emulator();

} // namespace EmuCoreDS
