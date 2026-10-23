#include "ds_emulator.h"

#include <android/log.h>
#include <aaudio/AAudio.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sys/system_properties.h>
#include <vector>

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

bool DsEmulator::Start(const std::string& rom_path, const std::string& sram_path, bool start_paused) {
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

    const bool use_vulkan = requested_renderer_.load(std::memory_order_relaxed) != 0;
    melonDS::NDSArgs args;
    if (use_vulkan) {
        args.Renderer3D = melonDS::VulkanRenderer3D::New();
    } else {
        args.Renderer3D = std::make_unique<melonDS::SoftRenderer>();
    }
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
    const int scale = std::clamp(requested_scale_.load(std::memory_order_relaxed), 1, 8);
    active_scale_.store(scale, std::memory_order_relaxed);
    if (auto* renderer3d = dynamic_cast<melonDS::VulkanRenderer3D*>(&nds_->GPU.GetRenderer3D())) {
        renderer3d->SetRenderSettings(false, true, scale, false, 0.0f, 0.0f, false, false, false,
                                      nds_->GPU);
    }
    if (use_vulkan) {
        // Match just-completed scanout registers with the same frame VRAM slot.
        vulkan_output_ = std::make_unique<MelonDSAndroid::VulkanOutput>(true);
        if (!vulkan_output_->init()) {
            __android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS",
                                "VulkanOutput initialization failed; falling back to software");
            vulkan_output_.reset();
            nds_->GPU.SetRenderer3D(std::make_unique<melonDS::SoftRenderer>());
        } else {
            for (auto& frame : vulkan_frames_) frame = std::make_unique<Frame>();
            vulkan_frame_cursor_ = 0;
            vulkan_frame_id_ = 0;
            vulkan_readback_.assign(
                static_cast<size_t>(256 * scale) * static_cast<size_t>(386 * scale), 0u);
            surface_presenter_ = std::make_unique<MelonDSAndroid::VulkanSurfacePresenter>(true);
            if (!surface_presenter_->init()) {
                __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                                    "VulkanSurfacePresenter initialization failed; using readback");
                surface_presenter_.reset();
            }
        }
    } else {
        __android_log_print(ANDROID_LOG_INFO, "EmuCoreDS", "Using DS software renderer");
    }
    nds_->Reset();
    nds_->SetupDirectBoot(rom_path_);
    nds_->Start();
    paused_ = start_paused;
    StartAudio();
    if (start_paused) SetPaused(true);

    {
        std::lock_guard lock(surface_mutex_);
        AttachVulkanSurfaceLocked();
    }

    running_ = true;
    thread_ = std::thread(&DsEmulator::Run, this);
    return true;
}

void DsEmulator::Stop() {
    if (!running_.exchange(false)) {
        {
            std::lock_guard lock(surface_mutex_);
            DetachVulkanSurfaceLocked();
            surface_presenter_.reset();
        }
        StopAudio();
        vulkan_output_.reset();
        for (auto& frame : vulkan_frames_) frame.reset();
        if (nds_) nds_.reset();
        return;
    }
    stop_requested_ = true;
    g_stop_requested = true;
    if (thread_.joinable()) thread_.join();
    stop_requested_ = false;
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
        const int pending_state = state_pending_.exchange(0, std::memory_order_acquire);
        if (pending_state != 0 && nds_) {
            const bool state_ok = pending_state == 1 ? WriteStateFile(state_path_)
                                                     : ReadStateFile(state_path_);
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
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            next_frame = clock::now();
            continue;
        }
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
        if (log_now) __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan frame resources failed");
        return;
    }

    const bool snapshot_captured =
        vulkan_output_->captureRenderer3dSnapshot(frame, *renderer3d, false);
    MelonDSAndroid::VulkanCompositionInputs inputs{};
    if (!vulkan_output_->buildCompositionInputs(
            frame, *renderer3d, scale, MelonDSAndroid::VulkanFilterMode::Nearest,
            false, false, false, inputs)) {
        if (log_now) {
            __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan composition inputs failed");
        }
        return;
    }

    // Opt-in diagnostics must use logcat: native stderr probes are not visible
    // in Android runs. Keep this bounded even when the property remains enabled.
    char trace_property[PROP_VALUE_MAX]{};
    if (log_now && __system_property_get("debug.emucored.ds_trace", trace_property) > 0
        && trace_property[0] == '1') {
        melonDS::CaptureSourceIdentity published{}, served{};
        melonDS::LiveRenderProductIdentity live{};
        (void)renderer3d->GetPublishedRenderIdentity(published);
        (void)renderer3d->GetLastServedCaptureSourceIdentity(served);
        (void)renderer3d->GetLiveRenderProductIdentity(live);
        u32 causal_lines = 0, causal_matches = 0;
        bool causal_ambiguous = false;
        const bool causal_available = vulkan_output_->liveCausalSourceAvailable(
            frame, static_cast<u32>(scale), &causal_lines, &causal_matches, &causal_ambiguous);
        __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
            "DSFrame3D frame=%llu rawPolys=%u submittedPolys=%u disp=%08X capture=%08X "
            "published=%u:%llu:%llu/%u/%u live=%u:%llu:%llu served=%u:%llu:%llu/%u "
            "snapshot=%u source=%ux%u captureBuffer=%u captureValid=%u causal=%u/%u/%u/%u",
            static_cast<unsigned long long>(frame->frameId), nds_->GPU.GPU3D.RenderNumPolygons,
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
            causal_available ? 1u : 0u, causal_lines, causal_matches, causal_ambiguous ? 1u : 0u);
        if (const auto* soft =
                dynamic_cast<melonDS::GPU2D::SoftRenderer*>(&nds_->GPU.GetRenderer2D())) {
            const auto* meta = soft->GetFaithfulPrevFrameMeta();
            const auto& capture = soft->GetFaithfulPrevCaptureProduct();
            unsigned operand_pixels = 0;
            const auto* operands = soft->GetFaithfulPrevLine3DOperands();
            for (size_t pixel = 0; pixel < 256u * 192u; ++pixel)
                operand_pixels += operands[pixel] != 0u ? 1u : 0u;
            __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                "DSFrame3D metadata frame=%u swap=%u flags=%08X operands=%u "
                "capture=%u/%u/%u key=%llu:%llu source=%u:%llu:%llu",
                meta[8], meta[0], meta[6], operand_pixels,
                capture.Valid ? 1u : 0u, capture.Complete ? 1u : 0u, capture.Uses3d ? 1u : 0u,
                static_cast<unsigned long long>(capture.ProductEpoch),
                static_cast<unsigned long long>(capture.ProductId), capture.SourceIdentity.Valid ? 1u : 0u,
                static_cast<unsigned long long>(capture.SourceIdentity.RenderProductEpoch),
                static_cast<unsigned long long>(capture.SourceIdentity.Sequence));
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
                __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                    "DSFrame3D screen=%u directLines=%u keyedLines=%u publishedMismatch=%u first=%llu:%llu",
                    static_cast<unsigned>(screen), direct_lines, keyed_lines, mismatched_lines,
                    static_cast<unsigned long long>(first.Epoch), static_cast<unsigned long long>(first.Sequence));
            }
        }
    }

    bool presented = false;
    {
        std::unique_lock present_lock(vulkan_present_mutex_, std::try_to_lock);
        if (present_lock.owns_lock() && surface_presenter_ && vulkan_surface_id_ != 0) {
            const auto wait_runner = [](const char*,
                                        const MelonDSAndroid::VulkanCausalWaitOperation& operation) {
                return operation() ? MelonDSAndroid::VulkanCausalWaitResult::Ready
                                   : MelonDSAndroid::VulkanCausalWaitResult::TimedOut;
            };
            const auto result = surface_presenter_->presentFrame(
                frame, *vulkan_output_, inputs, 100'000'000ull, 16'000'000ull,
                wait_runner);
            presented = result == MelonDSAndroid::VulkanPresentationResult::Presented;
            if (!presented) {
                static int presenter_log = 0;
                if ((presenter_log++ % 120) == 0) {
                    __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                                        "VulkanSurfacePresenter result=%d frame=%llu",
                                        static_cast<int>(result),
                                        (unsigned long long)frame->frameId);
                }
            }
        }
    }
    if (presented) return;

    if (!presenter_) return;
    const size_t readback_size = static_cast<size_t>(frame_width) * frame_height;
    if (vulkan_readback_.size() != readback_size) vulkan_readback_.assign(readback_size, 0u);
    inputs.needsReadback = true;
    if (!vulkan_output_->composeAndSubmitFrame(frame, inputs)) {
        if (log_now) {
            __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan compose failed frame=%llu",
                                (unsigned long long)frame->frameId);
        }
        return;
    }
    if (!vulkan_output_->readFramePixels(frame, vulkan_readback_.data(),
                                         vulkan_readback_.size(), 100'000'000ull)) {
        if (log_now) {
            __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan readback failed frame=%llu",
                                (unsigned long long)frame->frameId);
        }
        return;
    }
    if (log_now) {
        __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS",
                            "Vulkan readback present frame=%llu scale=%d px=%08X",
                            (unsigned long long)frame->frameId, scale,
                            vulkan_readback_.empty() ? 0u : vulkan_readback_[0]);
    }
    presenter_->Present(vulkan_readback_.data(), static_cast<int>(frame_width),
                        static_cast<int>(frame_height), DsPixelFormat::Rgba8);
}

void DsEmulator::ApplyRenderScaleLocked() {
    const int requested = std::clamp(pending_scale_.exchange(0, std::memory_order_relaxed), 1, 8);
    if (requested == active_scale_.load(std::memory_order_relaxed) || !nds_) return;

    auto* renderer3d = dynamic_cast<melonDS::VulkanRenderer3D*>(&nds_->GPU.GetRenderer3D());
    if (renderer3d == nullptr) return;

    renderer3d->SetRenderSettings(false, true, requested, false, 0.0f, 0.0f, false, false, false,
                                  nds_->GPU);
    active_scale_.store(requested, std::memory_order_relaxed);
    const size_t readback_size = static_cast<size_t>(256 * requested) *
        static_cast<size_t>(386 * requested);
    vulkan_readback_.assign(readback_size, 0u);
    __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "DS render scale set to %dx", requested);
}

void DsEmulator::AttachVulkanSurfaceLocked() {
    if (!surface_presenter_ || !window_ || vulkan_surface_id_ != 0) return;

    ANativeWindow_acquire(window_);
    const uint32_t width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window_)));
    const uint32_t height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window_)));
    vulkan_surface_id_ = surface_presenter_->attachSurface(window_, width, height);
    if (vulkan_surface_id_ == 0) {
        __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "Vulkan presenter surface attach failed");
        return;
    }
    ConfigureVulkanSurfaceLocked();
    __android_log_print(ANDROID_LOG_INFO, "EmuCoreDS", "Vulkan presenter surface=%d %ux%u",
                        vulkan_surface_id_, width, height);
}

void DsEmulator::DetachVulkanSurfaceLocked() {
    if (!surface_presenter_ || vulkan_surface_id_ == 0) return;
    {
        std::lock_guard present_lock(vulkan_present_mutex_);
        surface_presenter_->detachSurface(vulkan_surface_id_);
    }
    vulkan_surface_id_ = 0;
}

void DsEmulator::ConfigureVulkanSurfaceLocked() {
    if (!surface_presenter_ || !window_ || vulkan_surface_id_ == 0) return;

    const int window_width = std::max(1, ANativeWindow_getWidth(window_));
    const int window_height = std::max(1, ANativeWindow_getHeight(window_));
    const float content_aspect = 256.0f / 386.0f;
    const float window_aspect = static_cast<float>(window_width) / static_cast<float>(window_height);

    int content_width = window_width;
    int content_height = window_height;
    if (window_aspect > content_aspect) {
        content_width = static_cast<int>(static_cast<float>(window_height) * content_aspect + 0.5f);
    } else {
        content_height = static_cast<int>(static_cast<float>(window_width) / content_aspect + 0.5f);
    }
    content_width = std::max(2, content_width);
    content_height = std::max(2, content_height);
    const int left = (window_width - content_width) / 2;
    const int top = (window_height - content_height) / 2;
    const int top_height = content_height / 2;
    const int bottom_height = content_height - top_height;

    MelonDSAndroid::VulkanSurfaceConfig config{};
    config.bottomScreen = {true, left, top, content_width, top_height};
    config.topScreen = {true, left, top + top_height, content_width, bottom_height};
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
    presenter_->SetSurface(window);

    if (previous) ANativeWindow_release(previous);
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

void DsEmulator::StartAudio() {
    if (audio_stream_ != nullptr) return;

    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) return;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setBufferCapacityInFrames(builder, 1024);
    const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &audio_stream_);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || audio_stream_ == nullptr) {
        audio_stream_ = nullptr;
        __android_log_print(ANDROID_LOG_WARN, "EmuCoreDS", "AAudio stream open failed: %d",
                            static_cast<int>(result));
        return;
    }
    AAudioStream_requestStart(audio_stream_);
    __android_log_print(ANDROID_LOG_INFO, "EmuCoreDS", "AAudio stream %d Hz %d ch",
                        AAudioStream_getSampleRate(audio_stream_),
                        AAudioStream_getChannelCount(audio_stream_));
}

void DsEmulator::StopAudio() {
    if (audio_stream_ == nullptr) return;
    AAudioStream_requestStop(audio_stream_);
    AAudioStream_close(audio_stream_);
    audio_stream_ = nullptr;
}

void DsEmulator::DrainAudio() {
    if (audio_stream_ == nullptr || !nds_) return;
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
    AAudioStream_write(audio_stream_, audio_buffer_.data(), read, 2'000'000);
}

void DsEmulator::SetRenderer(int renderer) {
    requested_renderer_.store(renderer != 0 ? 1 : 0, std::memory_order_relaxed);
}

void DsEmulator::SetPaused(bool paused) {
    paused_.store(paused, std::memory_order_relaxed);
    if (audio_stream_ == nullptr) return;
    if (paused) {
        AAudioStream_requestPause(audio_stream_);
        AAudioStream_requestFlush(audio_stream_);
    } else {
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
    state_cv_.wait(state_lock, [&] { return state_finished_; });
    return state_success_;
}

bool DsEmulator::WriteStateFile(const std::string& path) {
    if (!nds_) return false;

    melonDS::Savestate state;
    if (!nds_->DoSavestate(&state)) return false;
    state.Finish();
    if (state.Error || state.Length() == 0 || state.Buffer() == nullptr) return false;

    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file) return false;
    file.write(reinterpret_cast<const char*>(state.Buffer()), state.Length());
    return file.good();
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
