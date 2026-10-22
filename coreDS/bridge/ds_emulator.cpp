#include "ds_emulator.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <vector>

#include "Args.h"
#include "GPU.h"
#include "GPU3D_Vulkan.h"
#include "GPU2D_Soft.h"
#include "renderer/FrameQueue.h"
#include "renderer/VulkanOutput.h"
#include "NDS.h"
#include "NDSCart.h"
#include "SPI_Firmware.h"
#include "ds_presenter.h"

namespace EmuCoreDS {

using melonDS::u8;
using melonDS::u32;

extern std::string g_user_dir;
extern std::string g_internal_dir;
extern std::string g_sram_path;
extern std::string g_firmware_path;

namespace {
std::atomic<bool> g_stop_requested{false};
std::mutex g_save_mutex;
std::string g_rom_path;

constexpr size_t kMaxRomSize = 512ull * 1024ull * 1024ull;
constexpr size_t kMaxSaveSize = 8ull * 1024ull * 1024ull;

bool ReadFile(const std::string& path, std::vector<u8>& out, size_t max_size) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;
    file.seekg(0, std::ios::end);
    const auto size = file.tellg();
    if (size < 0 || static_cast<size_t>(size) > max_size) return false;
    out.resize(static_cast<size_t>(size));
    file.seekg(0, std::ios::beg);
    if (!out.empty()) file.read(reinterpret_cast<char*>(out.data()), size);
    return file.good() || file.eof();
}

void WriteSaveFile(const std::string& path, const u8* data, u32 length) {
    if (path.empty() || !data || length == 0) return;
    std::lock_guard lock(g_save_mutex);
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file) return;
    file.write(reinterpret_cast<const char*>(data), length);
}

} // namespace

void RequestStop(melonDS::Platform::StopReason) {
    g_stop_requested = true;
}

void WriteNdsSave(const u8* data, u32 length, u32, u32) {
    WriteSaveFile(EmuCoreDS::g_sram_path, data, length);
}

void WriteFirmwareData(const u8* data, u32 length, u32, u32) {
    WriteSaveFile(EmuCoreDS::g_firmware_path, data, length);
}

DsEmulator& Emulator() {
    static DsEmulator instance;
    return instance;
}

DsEmulator::DsEmulator() = default;
DsEmulator::~DsEmulator() {
    Stop();
}

void DsEmulator::SetUserDirectory(const std::string& path) {
    user_dir_ = path;
    g_user_dir = path;
    g_internal_dir = path;
}

bool DsEmulator::Start(const std::string& rom_path, const std::string& sram_path) {
    Stop();
    rom_path_ = rom_path;
    sram_path_ = sram_path;
    g_rom_path = rom_path;
    g_sram_path = sram_path;
    g_stop_requested = false;

    std::vector<u8> rom;
    if (!ReadFile(rom_path, rom, kMaxRomSize)) {
        __android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS", "Failed to read ROM: %s",
                            rom_path.c_str());
        return false;
    }
    auto rom_data = std::make_unique<u8[]>(rom.size());
    std::memcpy(rom_data.get(), rom.data(), rom.size());

    melonDS::NDSCart::NDSCartArgs cart_args;
    std::vector<u8> save;
    if (ReadFile(sram_path, save, kMaxSaveSize) && !save.empty()) {
        auto save_data = std::make_unique<u8[]>(save.size());
        std::memcpy(save_data.get(), save.data(), save.size());
        cart_args.SRAM = std::move(save_data);
        cart_args.SRAMLength = static_cast<u32>(save.size());
    }

    melonDS::NDSArgs args;
    args.Renderer3D = melonDS::VulkanRenderer3D::New();
    args.JIT = melonDS::JITArgs{};
    args.JIT->FastMemory = false;
    frame_count_ = 0;

    nds_ = std::make_unique<melonDS::NDS>(std::move(args), this);
    auto cart = melonDS::NDSCart::ParseROM(std::move(rom_data), static_cast<u32>(rom.size()), this,
                                           std::move(cart_args));
    if (!cart) {
        __android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS", "Failed to parse ROM");
        nds_.reset();
        return false;
    }
    nds_->SetNDSCart(std::move(cart));
    if (auto* renderer3d = dynamic_cast<melonDS::VulkanRenderer3D*>(&nds_->GPU.GetRenderer3D())) {
        renderer3d->SetRenderSettings(false, true, 1, false, 0.0f, 0.0f, false, false, false,
                                      nds_->GPU);
    }
    vulkan_output_ = std::make_unique<MelonDSAndroid::VulkanOutput>();
    if (!vulkan_output_->init()) {
        __android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS",
                            "VulkanOutput initialization failed; falling back to software");
        vulkan_output_.reset();
        nds_->GPU.SetRenderer3D(std::make_unique<melonDS::SoftRenderer>());
    } else {
        vulkan_frame_ = std::make_unique<Frame>();
        vulkan_readback_.assign(256ull * 386ull, 0u);
    }
    nds_->Reset();
    nds_->SetupDirectBoot(rom_path_);
    nds_->Start();

    running_ = true;
    thread_ = std::thread(&DsEmulator::Run, this);
    return true;
}

void DsEmulator::Stop() {
    if (!running_.exchange(false)) {
        vulkan_output_.reset();
        vulkan_frame_.reset();
        if (nds_) nds_.reset();
        return;
    }
    stop_requested_ = true;
    g_stop_requested = true;
    if (thread_.joinable()) thread_.join();
    stop_requested_ = false;
    vulkan_output_.reset();
    vulkan_frame_.reset();
    if (nds_) {
        nds_->Stop();
        nds_.reset();
    }
}

void DsEmulator::Reset() {
    if (!nds_ || !running_.load()) return;
    stop_requested_ = true;
    if (thread_.joinable()) thread_.join();
    stop_requested_ = false;
    g_stop_requested = false;
    nds_->Reset();
    nds_->SetupDirectBoot(rom_path_);
    nds_->Start();
    running_ = true;
    thread_ = std::thread(&DsEmulator::Run, this);
}

void DsEmulator::Run() {
    using clock = std::chrono::steady_clock;
    auto next_frame = clock::now();
    while (running_.load() && !stop_requested_.load()) {
        if (nds_ && nds_->IsRunning()) {
            if (vulkan_output_) {
                if (auto* renderer2d =
                        dynamic_cast<melonDS::GPU2D::SoftRenderer*>(&nds_->GPU.GetRenderer2D())) {
                    renderer2d->BeginStructuredVulkan2DFrame();
                }
                if (vulkan_output_->ensureFaithfulAtlas()) {
                    vulkan_output_->uploadFaithfulAtlasPreFrame(nds_->GPU);
                    vulkan_output_->publishFaithfulCertifiedCaptureTerminals(nds_->GPU, 1);
                }
            }
            nds_->RunFrame();
            frame_count_.fetch_add(1, std::memory_order_relaxed);
            if (vulkan_output_) {
                if (auto* renderer2d =
                        dynamic_cast<melonDS::GPU2D::SoftRenderer*>(&nds_->GPU.GetRenderer2D())) {
                    renderer2d->PublishCompletedFaithfulFrame();
                }
                vulkan_output_->uploadFaithfulAtlas(nds_->GPU);
                PresentVulkanFrame();
            } else {
                PresentFrame();
            }
        }
        next_frame += std::chrono::microseconds(16683);
        const auto now = clock::now();
        if (next_frame > now) {
            std::this_thread::sleep_for(next_frame - now);
        } else {
            next_frame = now;
        }
    }
}

void DsEmulator::PresentFrame() {
    if (!nds_ || !presenter_) return;
    constexpr int kWidth = 256;
    constexpr int kHeight = 192;
    constexpr size_t kScreenPixels = static_cast<size_t>(kWidth) * kHeight;

    const int front = nds_->GPU.FrontBuffer;
    auto* top = nds_->GPU.Framebuffer[front][0].get();
    auto* bottom = nds_->GPU.Framebuffer[front][1].get();
    const bool swap = swap_screens_.load();
    auto* first = swap ? bottom : top;
    auto* second = swap ? top : bottom;

    switch (screen_layout_.load()) {
    case 0: {
        composite_.resize(kScreenPixels * 2);
        std::memcpy(composite_.data(), first, kScreenPixels * sizeof(uint32_t));
        std::memcpy(composite_.data() + kScreenPixels, second,
                    kScreenPixels * sizeof(uint32_t));
        presenter_->Present(composite_.data(), kWidth, kHeight * 2);
        break;
    }
    case 1:
        presenter_->Present(first, kWidth, kHeight);
        if (secondary_presenter_) secondary_presenter_->Present(second, kWidth, kHeight);
        break;
    case 2:
        presenter_->Present(first, kWidth, kHeight);
        break;
    case 3:
        presenter_->Present(second, kWidth, kHeight);
        break;
    case 4: {
        composite_.resize(kScreenPixels * 2);
        for (int y = 0; y < kHeight; y++) {
            std::memcpy(composite_.data() + static_cast<size_t>(y) * kWidth * 2,
                        first + static_cast<size_t>(y) * kWidth, kWidth * sizeof(uint32_t));
            std::memcpy(composite_.data() + static_cast<size_t>(y) * kWidth * 2 + kWidth,
                        second + static_cast<size_t>(y) * kWidth, kWidth * sizeof(uint32_t));
        }
        presenter_->Present(composite_.data(), kWidth * 2, kHeight);
        break;
    }
    default:
        presenter_->Present(first, kWidth, kHeight);
        break;
    }
}

void DsEmulator::PresentVulkanFrame() {
    if (!nds_ || !presenter_ || !vulkan_output_ || !vulkan_frame_) return;

    auto* renderer3d = dynamic_cast<melonDS::VulkanRenderer3D*>(&nds_->GPU.GetRenderer3D());
    if (renderer3d == nullptr) return;

    vulkan_frame_->backend = FrameBackend::VulkanImage;
    vulkan_frame_->width = 256;
    vulkan_frame_->height = 386;
    vulkan_frame_->frameId++;
    vulkan_frame_->publicationGeneration = vulkan_frame_->frameId;

    static int vulkan_log = 0;
    const bool log_now = (vulkan_log++ % 120) == 0;
    if (!vulkan_output_->ensureFrameResources(vulkan_frame_.get(), 256, 386)) {
        if (log_now) __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan frame resources failed");
        return;
    }

    MelonDSAndroid::VulkanCompositionInputs inputs{};
    if (!vulkan_output_->buildCompositionInputs(
            vulkan_frame_.get(), *renderer3d, 1, MelonDSAndroid::VulkanFilterMode::Nearest,
            true, false, false, inputs)) {
        if (log_now) {
            __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan composition inputs failed");
        }
        return;
    }
    inputs.needsReadback = true;
    if (!vulkan_output_->composeAndSubmitFrame(vulkan_frame_.get(), inputs)) {
        if (log_now) {
            __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan compose failed frame=%llu",
                                (unsigned long long)vulkan_frame_->frameId);
        }
        return;
    }
    if (!vulkan_output_->readFramePixels(vulkan_frame_.get(), vulkan_readback_.data(),
                                         vulkan_readback_.size())) {
        if (log_now) {
            __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan readback failed frame=%llu",
                                (unsigned long long)vulkan_frame_->frameId);
        }
        return;
    }
    if (log_now) {
        __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan present frame=%llu px=%08X",
                            (unsigned long long)vulkan_frame_->frameId,
                            vulkan_readback_.empty() ? 0u : vulkan_readback_[0]);
    }
    presenter_->Present(vulkan_readback_.data(), 256, 386);
}

void DsEmulator::SetSurface(ANativeWindow* window) {
    std::lock_guard lock(surface_mutex_);
    window_ = window;
    if (!presenter_) presenter_ = std::make_unique<DsPresenter>();
    presenter_->SetSurface(window);
}

void DsEmulator::SetSwapScreens(bool swap) {
    swap_screens_ = swap;
}

void DsEmulator::SetScreenLayout(int mode) {
    screen_layout_ = mode;
}

void DsEmulator::SetSecondarySurface(ANativeWindow* window) {
    std::lock_guard lock(surface_mutex_);
    secondary_window_ = window;
    if (!secondary_presenter_) secondary_presenter_ = std::make_unique<DsPresenter>();
    secondary_presenter_->SetSurface(window);
}

void DsEmulator::SetKeyMask(uint32_t mask) {
    pending_key_mask_ = mask;
    if (nds_) nds_->SetKeyMask(mask);
}

void DsEmulator::TouchScreen(uint16_t x, uint16_t y) {
    if (nds_) nds_->TouchScreen(x, y);
}

void DsEmulator::ReleaseTouch() {
    if (nds_) nds_->ReleaseScreen();
}

} // namespace EmuCoreDS
