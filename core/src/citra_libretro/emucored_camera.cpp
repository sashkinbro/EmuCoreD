// Copyright 2026 EmuCoreD
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

// Frontend-provided still-image camera support for the libretro core. The
// Android frontend decodes the user-selected image, converts it to RGB565 and
// hands the pixels over through the exported functions below.

#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "common/logging/log.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/frontend/camera/factory.h"
#include "core/frontend/camera/interface.h"
#include "core/hle/service/cam/cam.h"

namespace Camera::EmuCoreD {

namespace {

constexpr int NumSlots = 3;

struct StoredFrame {
    int width = 0;
    int height = 0;
    std::vector<u16> pixels; // RGB565
};

std::array<StoredFrame, NumSlots> g_frames;
std::mutex g_mutex;
bool g_registered = false;

u16 Rgb565At(const StoredFrame& frame, int x, int y) {
    const int sx = std::clamp(x, 0, frame.width - 1);
    const int sy = std::clamp(y, 0, frame.height - 1);
    return frame.pixels[static_cast<std::size_t>(sy) * frame.width + sx];
}

void Rgb565ToYuv(u16 pixel, int& y, int& u, int& v) {
    const int r = ((pixel >> 11) & 0x1F) * 255 / 31;
    const int g = ((pixel >> 5) & 0x3F) * 255 / 63;
    const int b = (pixel & 0x1F) * 255 / 31;
    y = std::clamp((77 * r + 150 * g + 29 * b) >> 8, 0, 255);
    u = std::clamp(((-43 * r - 85 * g + 128 * b) >> 8) + 128, 0, 255);
    v = std::clamp(((128 * r - 107 * g - 21 * b) >> 8) + 128, 0, 255);
}

} // Anonymous namespace

class Interface final : public CameraInterface {
public:
    Interface(int slot_, const Service::CAM::Flip& flip) : slot{slot_} {
        mirror = base_mirror =
            flip == Service::CAM::Flip::Horizontal || flip == Service::CAM::Flip::Reverse;
        invert = base_invert =
            flip == Service::CAM::Flip::Vertical || flip == Service::CAM::Flip::Reverse;
    }

    void StartCapture() override {}
    void StopCapture() override {}

    void SetResolution(const Service::CAM::Resolution& resolution_) override {
        resolution = resolution_;
    }

    void SetFlip(Service::CAM::Flip flip) override {
        mirror = base_mirror ^
                 (flip == Service::CAM::Flip::Horizontal || flip == Service::CAM::Flip::Reverse);
        invert =
            base_invert ^ (flip == Service::CAM::Flip::Vertical || flip == Service::CAM::Flip::Reverse);
    }

    void SetEffect(Service::CAM::Effect) override {}

    void SetFormat(Service::CAM::OutputFormat format_) override {
        format = format_;
    }

    void SetFrameRate(Service::CAM::FrameRate) override {}

    bool IsPreviewAvailable() override {
        return true;
    }

    std::vector<u16> ReceiveFrame() override {
        const int out_width = std::max(1, static_cast<int>(resolution.width));
        const int out_height = std::max(1, static_cast<int>(resolution.height));
        const bool yuv = format != Service::CAM::OutputFormat::RGB565;

        StoredFrame source;
        {
            std::scoped_lock lock{g_mutex};
            if (slot >= 0 && slot < NumSlots && !g_frames[slot].pixels.empty()) {
                source = g_frames[slot];
            }
        }

        std::vector<u16> output(static_cast<std::size_t>(out_width) * out_height,
                                yuv ? 0x8000 : 0);
        if (source.pixels.empty()) {
            return output;
        }

        std::vector<u16> rgb(static_cast<std::size_t>(out_width) * out_height);
        for (int y = 0; y < out_height; ++y) {
            const int sy = invert ? out_height - 1 - y : y;
            const int src_y = static_cast<int>(static_cast<long long>(sy) * source.height / out_height);
            for (int x = 0; x < out_width; ++x) {
                const int sx = mirror ? out_width - 1 - x : x;
                const int src_x =
                    static_cast<int>(static_cast<long long>(sx) * source.width / out_width);
                rgb[static_cast<std::size_t>(y) * out_width + x] = Rgb565At(source, src_x, src_y);
            }
        }

        if (!yuv) {
            return rgb;
        }

        // Pack as YUY2 words: (U << 8) | Y0, (V << 8) | Y1.
        for (int y = 0; y < out_height; ++y) {
            for (int x = 0; x < out_width; x += 2) {
                int y0, u0, v0, y1, u1, v1;
                Rgb565ToYuv(rgb[static_cast<std::size_t>(y) * out_width + x], y0, u0, v0);
                if (x + 1 < out_width) {
                    Rgb565ToYuv(rgb[static_cast<std::size_t>(y) * out_width + x + 1], y1, u1, v1);
                } else {
                    y1 = y0;
                    u1 = u0;
                    v1 = v0;
                }
                const int u = (u0 + u1) / 2;
                const int v = (v0 + v1) / 2;
                output[static_cast<std::size_t>(y) * out_width + x] =
                    static_cast<u16>((u << 8) | y0);
                if (x + 1 < out_width) {
                    output[static_cast<std::size_t>(y) * out_width + x + 1] =
                        static_cast<u16>((v << 8) | y1);
                }
            }
        }
        return output;
    }

private:
    int slot;
    Service::CAM::Resolution resolution{};
    Service::CAM::OutputFormat format = Service::CAM::OutputFormat::RGB565;
    bool base_mirror{};
    bool base_invert{};
    bool mirror{};
    bool invert{};
};

class Factory final : public CameraFactory {
public:
    std::unique_ptr<CameraInterface> Create(const std::string& config,
                                            const Service::CAM::Flip& flip) override {
        int slot = 0;
        try {
            slot = std::clamp(std::stoi(config), 0, NumSlots - 1);
        } catch (...) {
            slot = 0;
        }
        return std::make_unique<Interface>(slot, flip);
    }
};

} // namespace Camera::EmuCoreD

extern "C" {

void emucored_camera_configure(int slot, int enabled, int flip) {
    if (slot < 0 || slot >= Camera::EmuCoreD::NumSlots) {
        return;
    }
    std::scoped_lock lock{Camera::EmuCoreD::g_mutex};
    if (!Camera::EmuCoreD::g_registered) {
        Camera::RegisterFactory("emucored", std::make_unique<Camera::EmuCoreD::Factory>());
        Camera::EmuCoreD::g_registered = true;
    }
    Settings::values.camera_name[slot] = enabled ? "emucored" : "blank";
    Settings::values.camera_config[slot] = std::to_string(slot);
    Settings::values.camera_flip[slot] = std::clamp(flip, 0, 3);

    // Apply immediately when the camera service is already running.
    auto cam =
        Core::System::GetInstance().ServiceManager().GetService<Service::CAM::Module::Interface>(
            "cam:u");
    if (cam) {
        cam->GetModule()->ReloadCameraDevices();
    }
}

void emucored_camera_set_frame(int slot, int width, int height, const uint16_t* pixels,
                               int count) {
    if (slot < 0 || slot >= Camera::EmuCoreD::NumSlots || pixels == nullptr || width <= 0 ||
        height <= 0) {
        return;
    }
    if (static_cast<long long>(width) * height != count) {
        return;
    }
    std::scoped_lock lock{Camera::EmuCoreD::g_mutex};
    auto& frame = Camera::EmuCoreD::g_frames[slot];
    frame.width = width;
    frame.height = height;
    frame.pixels.assign(pixels, pixels + count);
}

} // extern "C"
