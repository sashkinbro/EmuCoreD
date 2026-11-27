#include "ds_diagnostics.h"
#include "ds_emulator.h"

#include <android/log.h>
#include <aaudio/AAudio.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <map>
#include <mutex>
#include <sys/resource.h>
#include <sys/system_properties.h>
#include <vector>
#include <unistd.h>

#include "Args.h"
#include "GPU.h"
#include "GPU3D_Vulkan.h"
#include "GPU2D_Soft.h"
#include "renderer/FrameQueue.h"
#include "renderer/VulkanOutput.h"
#include "renderer/VulkanSurfacePresenter.h"
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

bool ReadFileSized(const std::string& path, std::unique_ptr<u8[]>& out, size_t& out_size, size_t max_size) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) return false;
    const auto size = file.tellg();
    if (size <= 0 || static_cast<size_t>(size) > max_size) return false;
    out_size = static_cast<size_t>(size);
    out = std::make_unique<u8[]>(out_size);
    file.seekg(0, std::ios::beg);
    file.read(reinterpret_cast<char*>(out.get()), size);
    return file.good() || file.eof();
}

void WriteSaveFile(const std::string& path, const u8* data, u32 length) {
    if (path.empty() || !data || length == 0) return;
    std::lock_guard lock(g_save_mutex);
    const std::string tmp = path + ".tmp";
    std::FILE* file = std::fopen(tmp.c_str(), "wb");
    if (!file) return;
    bool ok = std::fwrite(data, 1, length, file) == length && std::fflush(file) == 0 && fsync(fileno(file)) == 0;
    ok = std::fclose(file) == 0 && ok;
    if (ok) ok = std::rename(tmp.c_str(), path.c_str()) == 0;
    if (!ok) std::remove(tmp.c_str());
}

uint64_t HashSaveBytes(const u8* data, size_t size) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < size; ++i) {
        h ^= data[i];
        h *= 1099511628211ULL;
    }
    return h;
}

class DebouncedSaveWriter {
public:
    ~DebouncedSaveWriter() { Shutdown(); }

    void Enqueue(const std::string& path, const u8* data, u32 length) {
        if (path.empty() || !data || length == 0) return;
        std::vector<u8> copy(data, data + length);
        std::lock_guard lock(mutex_);
        EnsureWorkerLocked();
        pending_[path] = std::move(copy);
        dirty_since_ = std::chrono::steady_clock::now();
        cv_.notify_all();
    }

    void Flush() {
        std::unique_lock lock(mutex_);
        if (!worker_.joinable() && pending_.empty()) return;
        flush_now_ = true;
        cv_.notify_all();
        done_cv_.wait(lock, [this] { return pending_.empty() && !writing_; });
        flush_now_ = false;
    }

    void Shutdown() {
        Flush();
        {
            std::lock_guard lock(mutex_);
            stop_ = true;
        }
        cv_.notify_all();
        if (worker_.joinable()) worker_.join();
        stop_ = false;
    }

private:
    void EnsureWorkerLocked() {
        if (!worker_.joinable()) {
            stop_ = false;
            worker_ = std::thread(&DebouncedSaveWriter::Run, this);
        }
    }

    void Run() {
        std::unique_lock lock(mutex_);
        while (true) {
            cv_.wait(lock, [this] { return stop_ || flush_now_ || !pending_.empty(); });
            if (stop_ && pending_.empty()) break;
            if (!flush_now_ && !stop_) {
                const auto target = dirty_since_ + std::chrono::milliseconds(500);
                cv_.wait_until(lock, target, [this] { return stop_ || flush_now_; });
                if (!stop_ && !flush_now_ &&
                    std::chrono::steady_clock::now() < dirty_since_ + std::chrono::milliseconds(500)) {
                    continue;
                }
            }
            if (pending_.empty()) {
                done_cv_.notify_all();
                continue;
            }
            std::map<std::string, std::vector<u8>> batch;
            batch.swap(pending_);
            writing_ = true;
            lock.unlock();
            for (const auto& [path, bytes] : batch) {
                const uint64_t h = HashSaveBytes(bytes.data(), bytes.size());
                auto prev = written_hashes_.find(path);
                if (prev != written_hashes_.end() && prev->second.first == bytes.size() && prev->second.second == h) {
                    continue;
                }
                WriteSaveFile(path, bytes.data(), static_cast<u32>(bytes.size()));
                written_hashes_[path] = {bytes.size(), h};
            }
            lock.lock();
            writing_ = false;
            if (pending_.empty()) done_cv_.notify_all();
        }
    }

    std::mutex mutex_;
    std::condition_variable cv_;
    std::condition_variable done_cv_;
    std::map<std::string, std::vector<u8>> pending_;
    std::map<std::string, std::pair<size_t, uint64_t>> written_hashes_;
    std::chrono::steady_clock::time_point dirty_since_{};
    std::thread worker_;
    bool flush_now_ = false;
    bool writing_ = false;
    bool stop_ = false;
};

DebouncedSaveWriter g_save_writer;

} // namespace

void RequestStop(melonDS::Platform::StopReason) {
    g_stop_requested = true;
}

void WriteNdsSave(const u8* data, u32 length, u32, u32) {
    g_save_writer.Enqueue(EmuCoreDS::g_sram_path, data, length);
}

void WriteFirmwareData(const u8* data, u32 length, u32, u32) {
    g_save_writer.Enqueue(EmuCoreDS::g_firmware_path, data, length);
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

bool DsEmulator::Start(const std::string& rom_path, const std::string& sram_path, bool start_paused) {
    std::lock_guard lifecycle_lock(lifecycle_mutex_);
    StopLocked();
    rom_path_ = rom_path;
    sram_path_ = sram_path;
    g_rom_path = rom_path;
    g_sram_path = sram_path;
    g_stop_requested = false;
    frame_trace_.reset();

    std::unique_ptr<u8[]> rom_data;
    size_t rom_size = 0;
    if (!ReadFileSized(rom_path, rom_data, rom_size, kMaxRomSize)) {
        EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS", "Failed to read ROM: %s",
                            rom_path.c_str()));
        return false;
    }

    melonDS::NDSCart::NDSCartArgs cart_args;
    std::vector<u8> save;
    if (ReadFile(sram_path, save, kMaxSaveSize) && !save.empty()) {
        auto save_data = std::make_unique<u8[]>(save.size());
        std::memcpy(save_data.get(), save.data(), save.size());
        cart_args.SRAM = std::move(save_data);
        cart_args.SRAMLength = static_cast<u32>(save.size());
    }

    const bool use_vulkan = requested_renderer_.load(std::memory_order_relaxed) != 0;
    melonDS::NDSArgs args;
    if (use_vulkan) {
        args.Renderer3D = melonDS::VulkanRenderer3D::New();
    } else {
        args.Renderer3D = std::make_unique<melonDS::SoftRenderer>();
    }
    args.JIT = melonDS::JITArgs{};
    args.JIT->FastMemory = melonDS::ARMJIT_Memory::IsFastMemSupported();
    frame_count_ = 0;

    nds_ = std::make_unique<melonDS::NDS>(std::move(args), this);
    auto cart = melonDS::NDSCart::ParseROM(std::move(rom_data), static_cast<u32>(rom_size), this,
                                           std::move(cart_args));
    if (!cart) {
        EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS", "Failed to parse ROM"));
        nds_.reset();
        return false;
    }
    nds_->SetNDSCart(std::move(cart));
    const int scale = std::clamp(requested_scale_.load(std::memory_order_relaxed), 1, 8);
    active_scale_.store(scale, std::memory_order_relaxed);
    if (auto* renderer3d = dynamic_cast<melonDS::VulkanRenderer3D*>(&nds_->GPU.GetRenderer3D())) {
        renderer3d->SetRenderSettings(false, true, scale, false, 0.0f, 0.0f, false, false, false,
                                      nds_->GPU);
    }
    if (use_vulkan) {
        // This bridge publishes the just-completed scanout registers each frame.
        // Stage VRAM into the slot consumed by that same frame, not the delayed frontend slot.
        vulkan_output_ = std::make_unique<MelonDSAndroid::VulkanOutput>(true);
        if (!vulkan_output_->init()) {
            EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS",
                                "VulkanOutput initialization failed; falling back to software"));
            vulkan_output_.reset();
            auto soft = std::make_unique<melonDS::SoftRenderer>();
            soft->SetThreaded(true, nds_->GPU);
            nds_->GPU.SetRenderer3D(std::move(soft));
        } else {
            for (auto& frame : vulkan_frames_) frame = std::make_unique<Frame>();
            vulkan_frame_cursor_ = 0;
            vulkan_frame_id_ = 0;
            vulkan_readback_.clear();
            surface_presenter_ = std::make_unique<MelonDSAndroid::VulkanSurfacePresenter>(true);
            if (!surface_presenter_->init()) {
                EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                                    "VulkanSurfacePresenter initialization failed; using readback"));
                surface_presenter_.reset();
            }
        }
    } else {
        if (auto* soft = dynamic_cast<melonDS::SoftRenderer*>(&nds_->GPU.GetRenderer3D())) {
            soft->SetThreaded(true, nds_->GPU);
        }
        EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_INFO, "EmuCoreDS", "Using DS software renderer"));
    }
    nds_->Reset();
    nds_->SetupDirectBoot(rom_path_);
    nds_->Start();
    paused_ = start_paused;
    presentation_dirty_ = false;
    StartAudio();
    if (start_paused) SetPaused(true);

    {
        std::lock_guard lock(surface_mutex_);
        if (vulkan_output_) {
            AttachVulkanSurfaceLocked();
            AttachVulkanSecondarySurfaceLocked();
        } else {
            if (!presenter_) presenter_ = std::make_unique<DsPresenter>();
            presenter_->SetSurface(window_);
        }
    }

    running_ = true;
    state_pending_ = 0;
    pending_cheats_.clear();
    pending_key_mask_.store(0xFFF, std::memory_order_relaxed);
    {
        std::lock_guard input_lock(input_mutex_);
        touch_dirty_ = false;
        pending_touch_active_ = false;
    }
    thread_ = std::thread(&DsEmulator::Run, this);
    return true;
}

void DsEmulator::RequestStopAsync() {
    stop_requested_.store(true, std::memory_order_release);
    g_stop_requested.store(true, std::memory_order_release);
    state_cv_.notify_all();
}

void DsEmulator::Stop() {
    std::lock_guard lifecycle_lock(lifecycle_mutex_);
    StopLocked();
}

void DsEmulator::StopLocked() {
    const bool was_running = running_.exchange(false);
    stop_requested_ = true;
    g_stop_requested = true;
    state_cv_.notify_all();
    if (thread_.joinable()) thread_.join();
    stop_requested_ = false;
    g_save_writer.Flush();
    if (!was_running) {
        {
            std::lock_guard lock(surface_mutex_);
            DetachVulkanSurfaceLocked();
            surface_presenter_.reset();
            if (presenter_) presenter_->ReleaseGraphicsContext();
            if (secondary_presenter_) secondary_presenter_->ReleaseGraphicsContext();
        }
        StopAudio();
        vulkan_output_.reset();
        for (auto& frame : vulkan_frames_) frame.reset();
        if (nds_) nds_.reset();
        return;
    }
    StopAudio();
    {
        std::lock_guard lock(surface_mutex_);
        DetachVulkanSurfaceLocked();
        surface_presenter_.reset();
    }
    vulkan_output_.reset();
    for (auto& frame : vulkan_frames_) frame.reset();
    if (nds_) {
        nds_->Stop();
        nds_.reset();
    }
}

void DsEmulator::Reset() {
    std::lock_guard lifecycle_lock(lifecycle_mutex_);
    if (!nds_ || !running_.load()) return;
    stop_requested_ = true;
    state_cv_.notify_all();
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
    setpriority(PRIO_PROCESS, 0, -8);
    using clock = std::chrono::steady_clock;
    auto next_frame = clock::now();
    while (running_.load() && !stop_requested_.load()) {
        const int pending_state = state_pending_.exchange(0, std::memory_order_acquire);
        if (pending_state != 0 && nds_) {
            bool state_ok=false;
            if(pending_state==3) {
                std::vector<melonDS::ARCode> staged;
                for(auto& words:pending_cheats_) {
                    melonDS::ARCode code{};code.Name="EmuCoreD";code.Enabled=true;code.Code=std::move(words);
                    staged.push_back(std::move(code));
                }
                nds_->AREngine.Cheats=std::move(staged);pending_cheats_.clear();state_ok=true;
            }else state_ok = pending_state == 1 ? WriteStateFile(state_path_) : ReadStateFile(state_path_);
            {
                std::lock_guard state_lock(state_mutex_);
                state_success_ = state_ok;
                state_finished_ = true;
            }
            state_cv_.notify_all();
        }
        if (pending_scale_.load(std::memory_order_relaxed) != 0) {
            ApplyRenderScaleLocked();
        }
        if (paused_.load(std::memory_order_relaxed)) {
            if (presentation_dirty_.exchange(false, std::memory_order_relaxed)) {
                // Menu display actions must redraw without advancing the paused guest.
                if (vulkan_output_) PresentVulkanFrame();
                else PresentFrame();
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            next_frame = clock::now();
            continue;
        }
        if (nds_ && nds_->IsRunning()) {
            nds_->SetKeyMask(pending_key_mask_.load(std::memory_order_relaxed));
            {
                std::lock_guard input_lock(input_mutex_);
                if (touch_dirty_) {
                    touch_dirty_ = false;
                    if (pending_touch_active_) nds_->TouchScreen(pending_touch_x_, pending_touch_y_);
                    else nds_->ReleaseScreen();
                }
            }
            if (vulkan_output_) {
                if (vulkan_output_->ensureFaithfulAtlas()) {
                    vulkan_output_->uploadFaithfulAtlasPreFrame(nds_->GPU);
                    const auto terminal_scale = static_cast<melonDS::u32>(
                        std::clamp(active_scale_.load(std::memory_order_relaxed), 1, 8));
                    vulkan_output_->publishFaithfulCertifiedCaptureTerminals(nds_->GPU, terminal_scale);
                }
            }
            nds_->RunFrame();
            frame_count_.fetch_add(1, std::memory_order_relaxed);
            DrainAudio();
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
        next_frame += std::chrono::microseconds(16715);
        const auto now = clock::now();
        if (next_frame > now) {
            std::this_thread::sleep_for(next_frame - now);
        } else if (now - next_frame > std::chrono::milliseconds(250)) {
            // A long stall (pause, load, or a missed window) must not create a
            // catch-up burst. Normal late frames keep the original deadline so
            // the scheduler recovers the lost time over the next few frames
            // instead of permanently resetting to the late wake-up time.
            next_frame = now;
        }
    }
    // Destroy EGL resources on their drawing thread before Stop joins it and
    // the next renderer connects Vulkan to the same retained native window.
    std::lock_guard surface_lock(surface_mutex_);
    if (presenter_) presenter_->ReleaseGraphicsContext();
    if (secondary_presenter_) secondary_presenter_->ReleaseGraphicsContext();
    {std::lock_guard state_lock(state_mutex_);state_success_=false;state_finished_=true;state_pending_=0;pending_cheats_.clear();}
    state_cv_.notify_all();
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
        std::memcpy(composite_.data(), second, kScreenPixels * sizeof(uint32_t));
        std::memcpy(composite_.data() + kScreenPixels, first,
                    kScreenPixels * sizeof(uint32_t));
        presenter_->Present(composite_.data(), kWidth, kHeight * 2, DsPixelFormat::Bgra8);
        break;
    }
    case 1:
        presenter_->Present(first, kWidth, kHeight, DsPixelFormat::Bgra8);
        if (secondary_presenter_) secondary_presenter_->Present(second, kWidth, kHeight, DsPixelFormat::Bgra8);
        break;
    case 2:
        presenter_->Present(first, kWidth, kHeight, DsPixelFormat::Bgra8);
        break;
    case 3:
        presenter_->Present(second, kWidth, kHeight, DsPixelFormat::Bgra8);
        break;
    case 4: {
        composite_.resize(kScreenPixels * 2);
        for (int y = 0; y < kHeight; y++) {
            std::memcpy(composite_.data() + static_cast<size_t>(y) * kWidth * 2,
                        first + static_cast<size_t>(y) * kWidth, kWidth * sizeof(uint32_t));
            std::memcpy(composite_.data() + static_cast<size_t>(y) * kWidth * 2 + kWidth,
                        second + static_cast<size_t>(y) * kWidth, kWidth * sizeof(uint32_t));
        }
        presenter_->Present(composite_.data(), kWidth * 2, kHeight, DsPixelFormat::Bgra8);
        break;
    }
    default:
        presenter_->Present(first, kWidth, kHeight, DsPixelFormat::Bgra8);
        break;
    }
}

void DsEmulator::PresentVulkanFrame() {
    if (!nds_ || !vulkan_output_) return;
    Frame* frame = vulkan_frames_[vulkan_frame_cursor_].get();
    if (frame == nullptr) return;
    vulkan_frame_cursor_ = (vulkan_frame_cursor_ + 1) % vulkan_frames_.size();

    auto* renderer3d = dynamic_cast<melonDS::VulkanRenderer3D*>(&nds_->GPU.GetRenderer3D());
    if (renderer3d == nullptr) return;

    const int scale = std::clamp(active_scale_.load(std::memory_order_relaxed), 1, 8);
    const uint32_t frame_width = static_cast<uint32_t>(256 * scale);
    const uint32_t frame_height = static_cast<uint32_t>(386 * scale);

    frame->backend = FrameBackend::VulkanImage;
    frame->width = frame_width;
    frame->height = frame_height;
    frame->frameId = ++vulkan_frame_id_;
    frame->publicationGeneration = frame->frameId;

    static int vulkan_log = 0;
    const bool log_now = (vulkan_log++ % 120) == 0;
    if (!vulkan_output_->ensureFrameResources(frame, frame_width, frame_height)) {
        if (log_now) EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan frame resources failed"));
        return;
    }

    vulkan_output_->solicitarSnapshotEnCompose(renderer3d, false);
    MelonDSAndroid::VulkanCompositionInputs inputs{};
    if (!vulkan_output_->buildCompositionInputs(
            frame, *renderer3d, scale, MelonDSAndroid::VulkanFilterMode::Nearest,
            false, false, false, inputs)) {
        vulkan_output_->solicitarSnapshotEnCompose(nullptr, false);
        if (log_now) {
            EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan composition inputs failed"));
        }
        return;
    }

    // Opt-in diagnostics must use logcat: native stderr probes are not visible
    // in Android runs. Keep this bounded even when the property remains enabled.
    char trace_property[PROP_VALUE_MAX]{};
    if (frame_trace_.shouldPoll(log_now)) {
        const int length = EMUCORED_DIAGNOSTIC_PROPERTY("debug.emucored.ds_trace", trace_property);
        frame_trace_.observe(length > 0 ? trace_property[0] - '0' : 0);
    }
    if (frame_trace_.take(log_now)) {
        const bool snapshot_captured = vulkan_output_->frameHasOwnRenderer3dSnapshot(frame);
        melonDS::CaptureSourceIdentity published{}, served{};
        melonDS::LiveRenderProductIdentity live{};
        (void)renderer3d->GetPublishedRenderIdentity(published);
        (void)renderer3d->GetLastServedCaptureSourceIdentity(served);
        (void)renderer3d->GetLiveRenderProductIdentity(live);
        u32 causal_lines = 0, causal_matches = 0;
        bool causal_ambiguous = false;
        const bool causal_available = vulkan_output_->liveCausalSourceAvailable(
            frame, static_cast<u32>(scale), &causal_lines, &causal_matches, &causal_ambiguous);
        EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
            "DSFrame3D frame=%llu frameRing=%u scale=%d rawPolys=%u submittedPolys=%u disp=%08X capture=%08X "
            "published=%u:%llu:%llu/%u/%u live=%u:%llu:%llu served=%u:%llu:%llu/%u "
            "snapshot=%u source=%ux%u captureBuffer=%u captureValid=%u causal=%u/%u/%u/%u",
            static_cast<unsigned long long>(frame->frameId),
            static_cast<unsigned>((vulkan_frame_cursor_ + vulkan_frames_.size() - 1u) % vulkan_frames_.size()),
            scale, nds_->GPU.GPU3D.RenderNumPolygons,
            renderer3d->GetLastSubmittedRenderPolygonCount(), nds_->GPU.GPU2D_A.DispCnt,
            nds_->GPU.GPU2D_A.CaptureCnt, published.Valid ? 1u : 0u,
            static_cast<unsigned long long>(published.RenderProductEpoch),
            static_cast<unsigned long long>(published.Sequence), published.PolygonCount,
            published.ScreenSwap ? 1u : 0u, live.Valid ? 1u : 0u,
            static_cast<unsigned long long>(live.Epoch), static_cast<unsigned long long>(live.Sequence),
            served.Valid ? 1u : 0u, static_cast<unsigned long long>(served.RenderProductEpoch),
            static_cast<unsigned long long>(served.Sequence), served.ScreenSwap ? 1u : 0u,
            snapshot_captured ? 1u : 0u, inputs.rendererWidth, inputs.rendererHeight,
            inputs.capture3dBuffer != VK_NULL_HANDLE ? 1u : 0u, inputs.capture3dSourceValid ? 1u : 0u,
            causal_available ? 1u : 0u, causal_lines, causal_matches, causal_ambiguous ? 1u : 0u));
        if (const auto* soft =
                dynamic_cast<melonDS::GPU2D::SoftRenderer*>(&nds_->GPU.GetRenderer2D())) {
            const auto* meta = soft->GetFaithfulPrevFrameMeta();
            const auto& capture = soft->GetFaithfulPrevCaptureProduct();
            unsigned operand_pixels = 0;
            const auto* operands = soft->GetFaithfulPrevLine3DOperands();
            for (size_t pixel = 0; pixel < 256u * 192u; ++pixel)
                operand_pixels += operands[pixel] != 0u ? 1u : 0u;
            EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                "DSFrame3D metadata frame=%u swap=%u flags=%08X operands=%u "
                "capture=%u/%u/%u key=%llu:%llu source=%u:%llu:%llu",
                meta[8], meta[0], meta[6], operand_pixels,
                capture.Valid ? 1u : 0u, capture.Complete ? 1u : 0u, capture.Uses3d ? 1u : 0u,
                static_cast<unsigned long long>(capture.ProductEpoch),
                static_cast<unsigned long long>(capture.ProductId), capture.SourceIdentity.Valid ? 1u : 0u,
                static_cast<unsigned long long>(capture.SourceIdentity.RenderProductEpoch),
                static_cast<unsigned long long>(capture.SourceIdentity.Sequence)));
            for (const auto screen : {melonDS::GPU2D::PhysicalScreen::Top,
                                      melonDS::GPU2D::PhysicalScreen::Bottom}) {
                const auto* lines = soft->GetFaithfulPrevLiveRenderProductLines(screen);
                melonDS::LiveRenderProductIdentity first{};
                unsigned direct_lines = 0, keyed_lines = 0, mismatched_lines = 0;
                for (size_t line = 0; line < 192u; ++line) {
                    if (!lines[line].Valid || !lines[line].Direct3DEnabled) continue;
                    ++direct_lines;
                    if (!lines[line].Product.Valid) continue;
                    ++keyed_lines;
                    if (!first.Valid) first = lines[line].Product;
                    if (lines[line].Product.Epoch != published.RenderProductEpoch
                        || lines[line].Product.Sequence != published.Sequence) ++mismatched_lines;
                }
                EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                    "DSFrame3D screen=%u directLines=%u keyedLines=%u publishedMismatch=%u first=%llu:%llu",
                    static_cast<unsigned>(screen), direct_lines, keyed_lines, mismatched_lines,
                    static_cast<unsigned long long>(first.Epoch), static_cast<unsigned long long>(first.Sequence)));
            }
        }
    }

    bool presented = false;
    bool using_vulkan_presenter = false;
    {
        std::unique_lock present_lock(vulkan_present_mutex_, std::try_to_lock);
        using_vulkan_presenter = surface_presenter_ && vulkan_surface_id_ != 0;
        if (present_lock.owns_lock() && using_vulkan_presenter) {
            const auto wait_runner = [](const char*,
                                        const MelonDSAndroid::VulkanCausalWaitOperation& operation) {
                return operation() ? MelonDSAndroid::VulkanCausalWaitResult::Ready
                                   : MelonDSAndroid::VulkanCausalWaitResult::TimedOut;
            };
            const auto result = surface_presenter_->presentFrame(
                frame, *vulkan_output_, inputs, 100'000'000ull, 16'000'000ull,
                wait_runner);
            vulkan_output_->solicitarSnapshotEnCompose(nullptr, false);
            presented = result == MelonDSAndroid::VulkanPresentationResult::Presented;
            if (!presented) {
                static int presenter_log = 0;
                if ((presenter_log++ % 120) == 0) {
                    EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                                        "VulkanSurfacePresenter result=%d frame=%llu",
                                        static_cast<int>(result),
                                        (unsigned long long)frame->frameId));
                }
            }
        }
    }
    // Never fall through to GLES DsPresenter while Vulkan owns this ANativeWindow.
    if (presented || using_vulkan_presenter) return;

    if (!presenter_) return;
    const size_t readback_size = static_cast<size_t>(frame_width) * frame_height;
    if (vulkan_readback_.size() != readback_size) vulkan_readback_.assign(readback_size, 0u);
    inputs.needsReadback = true;
    const bool composed = vulkan_output_->composeAndSubmitFrame(frame, inputs);
    vulkan_output_->solicitarSnapshotEnCompose(nullptr, false);
    if (!composed) {
        if (log_now) {
            EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan compose failed frame=%llu",
                                (unsigned long long)frame->frameId));
        }
        return;
    }
    if (!vulkan_output_->readFramePixels(frame, vulkan_readback_.data(),
                                         vulkan_readback_.size(), 100'000'000ull)) {
        if (log_now) {
            EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan readback failed frame=%llu",
                                (unsigned long long)frame->frameId));
        }
        return;
    }
    if (log_now) {
        EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                            "Vulkan readback present frame=%llu scale=%d px=%08X",
                            (unsigned long long)frame->frameId, scale,
                            vulkan_readback_.empty() ? 0u : vulkan_readback_[0]));
    }
    if (!swap_screens_.load()) {
        // The composed atlas is top-first; match the main presenter's bottom-first default.
        // Preserve the native atlas's two scaled gap rows between the screens.
        const size_t screen_pixels = static_cast<size_t>(frame_width) * 192u * scale;
        const size_t second_screen = static_cast<size_t>(frame_width) * 194u * scale;
        std::swap_ranges(vulkan_readback_.begin(), vulkan_readback_.begin() + screen_pixels,
                         vulkan_readback_.begin() + second_screen);
    }
    presenter_->Present(vulkan_readback_.data(), static_cast<int>(frame_width),
                        static_cast<int>(frame_height), DsPixelFormat::Bgra8);
}

void DsEmulator::ApplyRenderScaleLocked() {
    const int requested = std::clamp(pending_scale_.exchange(0, std::memory_order_relaxed), 1, 8);
    if (requested == active_scale_.load(std::memory_order_relaxed) || !nds_) return;

    auto* renderer3d = dynamic_cast<melonDS::VulkanRenderer3D*>(&nds_->GPU.GetRenderer3D());
    if (renderer3d == nullptr) return;

    renderer3d->SetRenderSettings(false, true, requested, false, 0.0f, 0.0f, false, false, false,
                                  nds_->GPU);
    active_scale_.store(requested, std::memory_order_relaxed);
    EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "DS render scale set to %dx", requested));
}

void DsEmulator::AttachVulkanSurfaceLocked() {
    if (!surface_presenter_ || !window_ || vulkan_surface_id_ != 0) return;

    ANativeWindow_acquire(window_);
    const uint32_t width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window_)));
    const uint32_t height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window_)));
    vulkan_surface_id_ = surface_presenter_->attachSurface(window_, width, height);
    if (vulkan_surface_id_ == 0) {
        ANativeWindow_release(window_);
        EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan presenter surface attach failed"));
        return;
    }
    ConfigureVulkanSurfaceLocked();
    EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_INFO, "EmuCoreDS", "Vulkan presenter surface=%d %ux%u",
                        vulkan_surface_id_, width, height));
}

void DsEmulator::DetachVulkanSurfaceLocked() {
    DetachVulkanSecondarySurfaceLocked();
    if (!surface_presenter_ || vulkan_surface_id_ == 0) return;
    {
        std::lock_guard present_lock(vulkan_present_mutex_);
        surface_presenter_->detachSurface(vulkan_surface_id_);
    }
    vulkan_surface_id_ = 0;
}

void DsEmulator::AttachVulkanSecondarySurfaceLocked() {
    if (!surface_presenter_ || !secondary_window_ || vulkan_secondary_surface_id_ != 0
        || screen_layout_.load(std::memory_order_relaxed) != 1) {
        return;
    }

    ANativeWindow_acquire(secondary_window_);
    const uint32_t width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(secondary_window_)));
    const uint32_t height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(secondary_window_)));
    vulkan_secondary_surface_id_ = surface_presenter_->attachSurface(secondary_window_, width, height);
    if (vulkan_secondary_surface_id_ == 0) {
        ANativeWindow_release(secondary_window_);
        EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan secondary presenter surface attach failed"));
        return;
    }
    ConfigureVulkanSecondarySurfaceLocked();
}

void DsEmulator::DetachVulkanSecondarySurfaceLocked() {
    if (!surface_presenter_ || vulkan_secondary_surface_id_ == 0) return;
    {
        std::lock_guard present_lock(vulkan_present_mutex_);
        surface_presenter_->detachSurface(vulkan_secondary_surface_id_);
    }
    vulkan_secondary_surface_id_ = 0;
}

void DsEmulator::ConfigureVulkanSecondarySurfaceLocked() {
    if (!surface_presenter_ || !secondary_window_ || vulkan_secondary_surface_id_ == 0) return;

    const int window_width = std::max(1, ANativeWindow_getWidth(secondary_window_));
    const int window_height = std::max(1, ANativeWindow_getHeight(secondary_window_));
    const float window_aspect = static_cast<float>(window_width) / static_cast<float>(window_height);
    const float aspect = 256.0f / 192.0f;
    int w = window_width;
    int h = window_height;
    if (window_aspect > aspect) {
        w = static_cast<int>(static_cast<float>(window_height) * aspect + 0.5f);
    } else {
        h = static_cast<int>(static_cast<float>(window_width) / aspect + 0.5f);
    }
    w = std::max(2, w);
    h = std::max(2, h);
    const int x = (window_width - w) / 2;
    const int y = (window_height - h) / 2;

    MelonDSAndroid::VulkanSurfaceConfig config{};
    const bool swap = swap_screens_.load(std::memory_order_relaxed);
    if (!swap) {
        config.bottomScreen = {true, x, y, w, h};
        config.topScreen = {false, 0, 0, 0, 0};
    } else {
        config.topScreen = {true, x, y, w, h};
        config.bottomScreen = {false, 0, 0, 0, 0};
    }
    config.filtering = MelonDSAndroid::VulkanFilterMode::Linear;
    const MelonDSAndroid::VulkanBackgroundImage background{};
    surface_presenter_->configureSurface(vulkan_secondary_surface_id_, config, background);
}

void DsEmulator::ConfigureVulkanSurfaceLocked() {
    if (!surface_presenter_ || !window_ || vulkan_surface_id_ == 0) return;

    const int window_width = std::max(1, ANativeWindow_getWidth(window_));
    const int window_height = std::max(1, ANativeWindow_getHeight(window_));
    const int layout = screen_layout_.load(std::memory_order_relaxed);
    const bool swap = swap_screens_.load(std::memory_order_relaxed);
    const float window_aspect = static_cast<float>(window_width) / static_cast<float>(window_height);

    MelonDSAndroid::VulkanSurfaceConfig config{};
    auto fitRect = [&](float aspect, int& out_x, int& out_y, int& out_w, int& out_h) {
        out_w = window_width;
        out_h = window_height;
        if (window_aspect > aspect) {
            out_w = static_cast<int>(static_cast<float>(window_height) * aspect + 0.5f);
        } else {
            out_h = static_cast<int>(static_cast<float>(window_width) / aspect + 0.5f);
        }
        out_w = std::max(2, out_w);
        out_h = std::max(2, out_h);
        out_x = (window_width - out_w) / 2;
        out_y = (window_height - out_h) / 2;
    };

    if (layout == 1 || layout == 2 || layout == 3) {
        // 1 = Separate (main shows primary), 2 = Top/Primary only, 3 = Bottom/Secondary only
        int x = 0, y = 0, w = 2, h = 2;
        fitRect(256.0f / 192.0f, x, y, w, h);
        const bool show_bottom = (layout == 3) ^ swap;
        if (show_bottom) {
            config.bottomScreen = {true, x, y, w, h};
            config.topScreen = {false, 0, 0, 0, 0};
        } else {
            config.topScreen = {true, x, y, w, h};
            config.bottomScreen = {false, 0, 0, 0, 0};
        }
    } else if (layout == 4) {
        // Side-by-side
        int x = 0, y = 0, w = 2, h = 2;
        fitRect(512.0f / 192.0f, x, y, w, h);
        const int first_w = w / 2;
        const int second_w = w - first_w;
        config.topScreen = {true, x, y, first_w, h};
        config.bottomScreen = {true, x + first_w, y, second_w, h};
        if (swap) std::swap(config.topScreen, config.bottomScreen);
    } else {
        // Default stacked layout
        int x = 0, y = 0, w = 2, h = 2;
        fitRect(256.0f / 386.0f, x, y, w, h);
        const int top_height = h / 2;
        const int bottom_height = h - top_height;
        config.bottomScreen = {true, x, y, w, top_height};
        config.topScreen = {true, x, y + top_height, w, bottom_height};
        if (swap) std::swap(config.topScreen, config.bottomScreen);
    }
    config.filtering = MelonDSAndroid::VulkanFilterMode::Linear;
    const MelonDSAndroid::VulkanBackgroundImage background{};
    surface_presenter_->configureSurface(vulkan_surface_id_, config, background);
}

void DsEmulator::SetSurface(ANativeWindow* window) {
    std::lock_guard lock(surface_mutex_);
    if (window) ANativeWindow_acquire(window);
    ANativeWindow* previous = window_;
    window_ = window;

    DetachVulkanSurfaceLocked();
    AttachVulkanSurfaceLocked();

    if (!presenter_) presenter_ = std::make_unique<DsPresenter>();
    // Never bind GLES onto a window owned by VulkanSurfacePresenter.
    presenter_->SetSurface(vulkan_surface_id_ == 0 ? window : nullptr);

    if (previous) ANativeWindow_release(previous);
}

void DsEmulator::SetSwapScreens(bool swap) {
    if (swap_screens_.exchange(swap) == swap) return;
    std::lock_guard surface_lock(surface_mutex_);
    std::lock_guard present_lock(vulkan_present_mutex_);
    ConfigureVulkanSurfaceLocked();
    ConfigureVulkanSecondarySurfaceLocked();
    presentation_dirty_.store(true, std::memory_order_relaxed);
}

void DsEmulator::SetScreenLayout(int mode) {
    if (screen_layout_.exchange(mode) == mode) return;
    std::lock_guard surface_lock(surface_mutex_);
    if (mode == 1 && surface_presenter_ && secondary_window_ && vulkan_surface_id_ != 0) {
        AttachVulkanSecondarySurfaceLocked();
    } else {
        DetachVulkanSecondarySurfaceLocked();
    }
    if (secondary_presenter_) {
        secondary_presenter_->SetSurface(vulkan_secondary_surface_id_ == 0 ? secondary_window_ : nullptr);
    }
    std::lock_guard present_lock(vulkan_present_mutex_);
    ConfigureVulkanSurfaceLocked();
    ConfigureVulkanSecondarySurfaceLocked();
    presentation_dirty_.store(true, std::memory_order_relaxed);
}

void DsEmulator::SetSecondarySurface(ANativeWindow* window) {
    std::lock_guard lock(surface_mutex_);
    DetachVulkanSecondarySurfaceLocked();
    secondary_window_ = window;
    if (surface_presenter_ && vulkan_surface_id_ != 0) {
        AttachVulkanSecondarySurfaceLocked();
    }
    if (!secondary_presenter_) secondary_presenter_ = std::make_unique<DsPresenter>();
    secondary_presenter_->SetSurface(vulkan_secondary_surface_id_ == 0 ? window : nullptr);
}

void DsEmulator::SetKeyMask(uint32_t mask) {
    pending_key_mask_.store(mask, std::memory_order_relaxed);
}

void DsEmulator::TouchScreen(uint16_t x, uint16_t y) {
    std::lock_guard input_lock(input_mutex_);
    pending_touch_x_ = x;
    pending_touch_y_ = y;
    pending_touch_active_ = true;
    touch_dirty_ = true;
}

void DsEmulator::ReleaseTouch() {
    std::lock_guard input_lock(input_mutex_);
    pending_touch_active_ = false;
    touch_dirty_ = true;
}

aaudio_data_callback_result_t DsEmulator::AudioCallback(
    AAudioStream*, void* user, void* buffer, int32_t count) {
    auto* self = static_cast<DsEmulator*>(user);
    auto* out = static_cast<int16_t*>(buffer);
    const uint32_t gen = self->audio_flush_gen_.load(std::memory_order_acquire);
    uint32_t r = self->audio_read_.load(std::memory_order_relaxed);
    const uint32_t w = self->audio_write_.load(std::memory_order_acquire);
    if (gen != self->audio_flush_seen_) {
        self->audio_flush_seen_ = gen;
        self->audio_last_sample_[0] = 0;
        self->audio_last_sample_[1] = 0;
        const uint32_t discard_until = self->audio_discard_until_.load(std::memory_order_acquire);
        const uint32_t to_discard = discard_until - r;
        const uint32_t avail = w - r;
        r = (to_discard <= avail) ? (r + to_discard) : w;
        self->audio_read_.store(r, std::memory_order_release);
    }
    if (self->paused_.load(std::memory_order_relaxed)) {
        self->audio_last_sample_[0] = 0;
        self->audio_last_sample_[1] = 0;
        std::fill(out, out + count * 2, 0);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    const uint32_t avail = w - r;
    const uint32_t n = std::min<uint32_t>(static_cast<uint32_t>(std::max(0, count)), avail);
    for (uint32_t i = 0; i < n; i++) {
        const uint32_t idx = ((r + i) % kAudioCapacity) * 2;
        const int16_t l = self->audio_ring_[idx];
        const int16_t rch = self->audio_ring_[idx + 1];
        out[i * 2] = l;
        out[i * 2 + 1] = rch;
        self->audio_last_sample_[0] = l;
        self->audio_last_sample_[1] = rch;
    }
    for (uint32_t i = n; i < static_cast<uint32_t>(count); i++) {
        self->audio_last_sample_[0] = static_cast<int16_t>((self->audio_last_sample_[0] * 240) / 256);
        self->audio_last_sample_[1] = static_cast<int16_t>((self->audio_last_sample_[1] * 240) / 256);
        out[i * 2] = self->audio_last_sample_[0];
        out[i * 2 + 1] = self->audio_last_sample_[1];
    }
    self->audio_read_.store(r + n, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void DsEmulator::FlushAudioRing() {
    const uint32_t w = audio_write_.load(std::memory_order_acquire);
    audio_discard_until_.store(w, std::memory_order_release);
    audio_flush_gen_.fetch_add(1, std::memory_order_release);
}

bool DsEmulator::OpenAudioStreamLocked() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) return false;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    if (auto* set_usage = reinterpret_cast<void (*)(AAudioStreamBuilder*, int32_t)>(
            dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setUsage"))) {
        set_usage(builder, 14 /* AAUDIO_USAGE_GAME */);
    }
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, AudioCallback, this);
    const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &audio_stream_);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || audio_stream_ == nullptr) {
        audio_stream_ = nullptr;
        EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "AAudio stream open failed: %d",
                            static_cast<int>(result)));
        return false;
    }
    const int32_t actual_rate = std::max(8000, AAudioStream_getSampleRate(audio_stream_));
    const int32_t burst = AAudioStream_getFramesPerBurst(audio_stream_);
    const int32_t cap = AAudioStream_getBufferCapacityInFrames(audio_stream_);
    if (burst > 0 && cap > 0) {
        const int32_t target = std::max(burst * 4, actual_rate / 30);
        AAudioStream_setBufferSizeInFrames(audio_stream_, std::min(cap, target));
    }
    if (!paused_.load(std::memory_order_relaxed)) {
        if (AAudioStream_requestStart(audio_stream_) != AAUDIO_OK) {
            AAudioStream_close(audio_stream_);
            audio_stream_ = nullptr;
            return false;
        }
    }
    EMUCORED_DIAGNOSTIC_LOG(__android_log_print(ANDROID_LOG_INFO, "EmuCoreDS", "AAudio callback stream %d Hz %d ch",
                        actual_rate,
                        AAudioStream_getChannelCount(audio_stream_)));
    return true;
}

void DsEmulator::StartAudio() {
    std::lock_guard audio_lock(audio_mutex_);
    if (audio_stream_ != nullptr) return;
    audio_read_.store(0, std::memory_order_relaxed);
    audio_write_.store(0, std::memory_order_relaxed);
    audio_flush_gen_.store(0, std::memory_order_relaxed);
    audio_discard_until_.store(0, std::memory_order_relaxed);
    audio_flush_seen_ = 0;
    audio_last_sample_[0] = 0;
    audio_last_sample_[1] = 0;
    OpenAudioStreamLocked();
}

void DsEmulator::StopAudio() {
    std::lock_guard audio_lock(audio_mutex_);
    if (audio_stream_ != nullptr) {
        AAudioStream_requestStop(audio_stream_);
        AAudioStream_close(audio_stream_);
        audio_stream_ = nullptr;
    }
    FlushAudioRing();
}

void DsEmulator::SetVolume(float volume) {
    audio_volume_.store(std::clamp(volume, 0.0f, 1.0f), std::memory_order_relaxed);
}

void DsEmulator::DrainAudio() {
    if (!nds_) return;
    const int available = nds_->SPU.GetOutputSize();
    if (available <= 0) return;
    const int frames = std::min(available, 2048);
    audio_buffer_.resize(static_cast<size_t>(frames) * 2);
    const int read = nds_->SPU.ReadOutput(audio_buffer_.data(), frames);
    if (read <= 0) return;
    const float volume = std::clamp(audio_volume_.load(std::memory_order_relaxed), 0.0f, 1.0f);
    if (volume < 0.999f) {
        for (int i = 0; i < read * 2; i++) {
            audio_buffer_[i] = static_cast<int16_t>(audio_buffer_[i] * volume);
        }
    }
    {
        std::lock_guard audio_lock(audio_mutex_);
        if (audio_stream_ == nullptr ||
            AAudioStream_getState(audio_stream_) == AAUDIO_STREAM_STATE_DISCONNECTED) {
            if (audio_stream_ != nullptr) {
                AAudioStream_close(audio_stream_);
                audio_stream_ = nullptr;
            }
            if (!OpenAudioStreamLocked()) return;
        }
    }
    const uint32_t w = audio_write_.load(std::memory_order_relaxed);
    const uint32_t r = audio_read_.load(std::memory_order_acquire);
    const uint32_t free_frames = kAudioCapacity - (w - r);
    const uint32_t to_copy = std::min<uint32_t>(static_cast<uint32_t>(read), free_frames);
    for (uint32_t i = 0; i < to_copy; i++) {
        const uint32_t idx = ((w + i) % kAudioCapacity) * 2;
        audio_ring_[idx] = audio_buffer_[i * 2];
        audio_ring_[idx + 1] = audio_buffer_[i * 2 + 1];
    }
    audio_write_.store(w + to_copy, std::memory_order_release);
}

void DsEmulator::SetRenderer(int renderer) {
    requested_renderer_.store(renderer != 0 ? 1 : 0, std::memory_order_relaxed);
}

void DsEmulator::SetPaused(bool paused) {
    paused_.store(paused, std::memory_order_relaxed);
    if (paused) g_save_writer.Flush();
    std::lock_guard audio_lock(audio_mutex_);
    if (audio_stream_ == nullptr) return;
    const aaudio_stream_state_t state = AAudioStream_getState(audio_stream_);
    if (paused) {
        if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) {
            if (AAudioStream_requestPause(audio_stream_) == AAUDIO_OK) {
                aaudio_stream_state_t next = state;
                AAudioStream_waitForStateChange(audio_stream_, state, &next, 50000000LL);
            }
        }
        FlushAudioRing();
        const aaudio_stream_state_t post_state = AAudioStream_getState(audio_stream_);
        if (post_state == AAUDIO_STREAM_STATE_PAUSED || post_state == AAUDIO_STREAM_STATE_STOPPED) {
            AAudioStream_requestFlush(audio_stream_);
        }
    } else if (state != AAUDIO_STREAM_STATE_STARTED && state != AAUDIO_STREAM_STATE_STARTING &&
               state != AAUDIO_STREAM_STATE_DISCONNECTED && state != AAUDIO_STREAM_STATE_CLOSED) {
        AAudioStream_requestStart(audio_stream_);
    }
}

void DsEmulator::SetRenderScale(int scale) {
    const int normalized = std::clamp(scale, 1, 8);
    requested_scale_.store(normalized, std::memory_order_relaxed);
    pending_scale_.store(normalized, std::memory_order_relaxed);
}

bool DsEmulator::SaveState(const std::string& path) {
    return PerformStateRequest(path, true);
}

bool DsEmulator::LoadState(const std::string& path) {
    return PerformStateRequest(path, false);
}

bool DsEmulator::PerformStateRequest(const std::string& path, bool save) {
    std::lock_guard call_lock(state_call_mutex_);
    if (path.empty() || !running_.load() || !nds_) return false;

    std::unique_lock state_lock(state_mutex_);
    if (state_pending_.load(std::memory_order_relaxed) != 0) return false;
    state_path_ = path;
    state_finished_ = false;
    state_success_ = false;
    state_pending_.store(save ? 1 : 2, std::memory_order_release);
    state_cv_.wait(state_lock, [&] { return state_finished_ || !running_.load(); });
    return state_finished_ && state_success_;
}

bool DsEmulator::ReplaceCheats(std::vector<std::vector<uint32_t>> codes) {
    for(const auto& words:codes) {
        if(words.empty() || words.size()%2)return false;
        for(size_t i=0;i<words.size();) {
            const uint32_t a=words[i++],b=words[i++];const uint32_t op=a>>24;
            if(op>=0xC0 && op<0xD0 && op!=0xC0 && op!=0xC4 && op!=0xC5 && op!=0xC6)return false;
            if(op>=0xDD && op<0xE0)return false;
            if(op==0xD4 && (a&255)>8)return false;
            if(op>=0xE0 && op<=0xEF) {
                const uint64_t payload=((uint64_t(b)+7)/8)*2;
                if(payload>words.size()-i)return false;i+=static_cast<size_t>(payload);
            }
            if(op>=0xF0 && b>1048576)return false;
            if(op==0xC0 && b>65535)return false;
        }
    }
    std::lock_guard call_lock(state_call_mutex_);
    if(!running_.load() || stop_requested_.load())return false;
    std::unique_lock state_lock(state_mutex_);
    if(state_pending_.load()!=0)return false;
    pending_cheats_=std::move(codes);state_finished_=false;state_success_=false;
    state_pending_.store(3,std::memory_order_release);
    state_cv_.wait(state_lock,[&]{return state_finished_ || !running_.load();});
    return state_finished_ && state_success_;
}

bool DsEmulator::WriteStateFile(const std::string& path) {
    if (!nds_) return false;

    melonDS::Savestate state;
    if (!nds_->DoSavestate(&state)) return false;
    state.Finish();
    if (state.Error || state.Length() == 0 || state.Buffer() == nullptr) return false;

    const std::string pending = path + ".pending";
    std::FILE* file = std::fopen(pending.c_str(), "wb");
    if (!file) return false;
    bool success = std::fwrite(state.Buffer(), 1, state.Length(), file) == state.Length();
    // Complete the new file before replacing a valid user slot. Every close path is checked.
    if (std::fflush(file) != 0) success = false;
    if (success && fsync(fileno(file)) != 0) success = false;
    if (std::fclose(file) != 0) success = false;
    if (success) success = std::rename(pending.c_str(), path.c_str()) == 0;
    if (!success) std::remove(pending.c_str());
    return success;
}

bool DsEmulator::ReadStateFile(const std::string& path) {
    if (!nds_) return false;

    std::vector<u8> buffer;
    if (!ReadFile(path, buffer, kMaxRomSize) || buffer.empty()) return false;

    melonDS::Savestate state(buffer.data(), static_cast<u32>(buffer.size()), false);
    if (!nds_->DoSavestate(&state)) return false;
    state.Finish();
    if (state.Error) return false;

    nds_->SetKeyMask(pending_key_mask_);
    if (vulkan_output_) {
        vulkan_output_->invalidateTemporalHistory();
        vulkan_output_->clearStructuredCaptureHistory();
    }
    if (auto* renderer3d = dynamic_cast<melonDS::VulkanRenderer3D*>(&nds_->GPU.GetRenderer3D())) {
        renderer3d->Reset(nds_->GPU);
    }
    return true;
}

} // namespace EmuCoreDS
