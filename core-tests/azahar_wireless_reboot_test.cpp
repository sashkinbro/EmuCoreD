#include <cstdint>
#include <cstdio>
#include <vector>
#include "core/hle/service/apt/wireless_reboot_info.h"

int main() {
    using Service::APT::MakeWirelessRebootResponse;
    const std::vector<std::uint8_t> saved{1, 2, 3, 4};
    if (MakeWirelessRebootResponse(saved, 2, 16) != std::vector<std::uint8_t>{1, 2}) return 1;
    if (MakeWirelessRebootResponse(saved, 6, 16) != std::vector<std::uint8_t>{1, 2, 3, 4, 0, 0}) return 2;
    if (MakeWirelessRebootResponse(saved, 999, 3) != std::vector<std::uint8_t>{1, 2, 3}) return 3;
    if (!MakeWirelessRebootResponse(saved, 0, 16).empty()) return 4;
    if (MakeWirelessRebootResponse({}, 2, 16) != std::vector<std::uint8_t>{0, 0}) return 5;
    std::puts("PASS wireless reboot response truncation, padding and limit");
}
