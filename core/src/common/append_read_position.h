#pragma once

#include <string_view>

namespace Common {

// bionic initially reads a+ streams at EOF; glibc reads at the beginning.
// Seek through IOFile's backend so SAF, file descriptors and VFS keep their semantics.
template <typename Rewind>
void NormalizeAppendReadPosition(bool opened, std::string_view mode, Rewind&& rewind) {
    if (opened && mode.find('a') != std::string_view::npos &&
        mode.find('+') != std::string_view::npos)
        rewind();
}

} // namespace Common
