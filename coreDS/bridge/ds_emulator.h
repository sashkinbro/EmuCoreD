#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

struct ANativeWindow;

namespace melonDS {
class NDS;
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
    uint64_t FrameCount() const { return frame_count_.load(); }
    void SetSecondarySurface(ANativeWindow* window);
    void SetKeyMask(uint32_t mask);
    void TouchScreen(uint16_t x, uint16_t y);
    void ReleaseTouch();

    bool IsRunning() const { return running_.load(); }
    void SetUserDirectory(const std::string& path);

private:
    void Run();
    void ApplyDefaultBios();
    void PresentFrame();

    std::unique_ptr<melonDS::NDS> nds_;
    std::unique_ptr<DsPresenter> presenter_;
    std::unique_ptr<DsPresenter> secondary_presenter_;
    std::thread thread_;
    std::mutex surface_mutex_;
    ANativeWindow* window_ = nullptr;
    ANativeWindow* secondary_window_ = nullptr;
    std::atomic<bool> running_{false};
    std::atomic<bool> stop_requested_{false};
    std::atomic<bool> swap_screens_{false};
    std::atomic<uint64_t> frame_count_{0};
    std::string rom_path_;
    std::string sram_path_;
    std::string user_dir_;
    uint32_t pending_key_mask_ = 0xFFF;
};

DsEmulator& Emulator();

} // namespace EmuCoreDS
