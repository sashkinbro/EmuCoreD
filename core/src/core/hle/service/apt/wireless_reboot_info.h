// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace Service::APT {
inline std::vector<std::uint8_t> MakeWirelessRebootResponse(
    const std::vector<std::uint8_t>& saved, std::size_t requested, std::size_t maximum) {
    std::vector<std::uint8_t> response(std::min(requested, maximum), 0);
    std::copy_n(saved.begin(), std::min(saved.size(), response.size()), response.begin());
    return response;
}
}
