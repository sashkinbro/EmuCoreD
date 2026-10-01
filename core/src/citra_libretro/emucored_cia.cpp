// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-2.0-or-later
//
// EmuCoreD helper: prepares a CIA boot content for the libretro frontend.
//
// The libretro core loads NCCH images but cannot install CIA containers, so the
// frontend extracts the requested content with this helper. The logic mirrors
// Azahar's own CIAFile installer: the ticket title key (decrypted with the
// common key) removes the content-level AES-CBC layer, and the NCCH secure
// crypto (keyY from the NCCH signature plus the KeyX slots) removes the
// section-level AES-CTR layer. The written NCCH gets the no_crypto flag so the
// regular loader accepts it. Built-in keys are used when the user did not
// provide aes_keys.txt, exactly like the standalone emulator.

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cryptopp/aes.h>
#include <cryptopp/modes.h>
#include <cryptopp/sha.h>

#include "common/alignment.h"
#include "common/common_types.h"
#include "common/file_util.h"
#include "common/logging/log.h"
#include "core/file_sys/ncch_container.h"
#include "core/file_sys/ticket.h"
#include "core/hw/aes/key.h"

namespace {

constexpr u32 kBlockSize = 0x200;
constexpr u32 kCiaHeaderSize = 0x2020;
constexpr u32 kTmdContentEntrySize = 0x30;
constexpr u32 kExHeaderSize = 0x400;

u16 ReadBe16(const u8* data) {
    return static_cast<u16>((data[0] << 8) | data[1]);
}

u32 ReadBe32(const u8* data) {
    return (static_cast<u32>(data[0]) << 24) | (static_cast<u32>(data[1]) << 16) |
           (static_cast<u32>(data[2]) << 8) | static_cast<u32>(data[3]);
}

u64 ReadBe64(const u8* data) {
    return (static_cast<u64>(ReadBe32(data)) << 32) | ReadBe32(data + 4);
}

u32 ReadLe32(const u8* data) {
    return static_cast<u32>(data[0]) | (static_cast<u32>(data[1]) << 8) |
           (static_cast<u32>(data[2]) << 16) | (static_cast<u32>(data[3]) << 24);
}

u64 ReadLe64(const u8* data) {
    return static_cast<u64>(ReadLe32(data)) | (static_cast<u64>(ReadLe32(data + 4)) << 32);
}

bool ReadFd(int fd, u64 offset, void* buffer, size_t size) {
    auto* out = static_cast<u8*>(buffer);
    size_t done = 0;
    while (done < size) {
        const ssize_t read = pread(fd, out + done, size - done,
                                   static_cast<off_t>(offset + done));
        if (read <= 0) {
            return false;
        }
        done += static_cast<size_t>(read);
    }
    return true;
}

bool WriteFd(int fd, u64 offset, const void* buffer, size_t size) {
    const auto* in = static_cast<const u8*>(buffer);
    size_t done = 0;
    while (done < size) {
        const ssize_t written = pwrite(fd, in + done, size - done,
                                       static_cast<off_t>(offset + done));
        if (written <= 0) {
            return false;
        }
        done += static_cast<size_t>(written);
    }
    return true;
}

struct ContentEntry {
    u32 id;
    u16 index;
    u16 type;
    u64 size;
};

struct TmdInfo {
    u16 count = 0;
    u16 boot_index = 0;
    u64 title_id = 0;
    std::vector<ContentEntry> entries;
};

// The TMD content table is stored either right after the fixed header fields
// (standard) or at the end of the TMD block (tools that pad the signature).
bool ParseTmdEntries(const std::vector<u8>& tmd, u32 tmd_offset_in_tmd, TmdInfo* out) {
    if (tmd.size() < 0xA4 + kTmdContentEntrySize) {
        return false;
    }
    // Locate the issuer ("Root-CA") to derive the base of the TMD body.
    size_t base = 0;
    bool found = false;
    for (size_t i = 0; i + 7 <= std::min<size_t>(tmd.size(), 0x300); ++i) {
        if (std::memcmp(tmd.data() + i, "Root-CA", 7) == 0) {
            base = i;
            found = true;
            break;
        }
    }
    if (!found) {
        return false;
    }
    out->count = ReadBe16(tmd.data() + base + 0x9E);
    out->boot_index = ReadBe16(tmd.data() + base + 0xA0);
    out->title_id = ReadBe64(tmd.data() + base + 0x4C);
    if (out->count == 0 || out->count > 64) {
        return false;
    }

    const auto read_table = [&](size_t table_offset) -> bool {
        if (table_offset + out->count * kTmdContentEntrySize > tmd.size()) {
            return false;
        }
        out->entries.clear();
        u64 total = 0;
        for (u16 i = 0; i < out->count; ++i) {
            const u8* entry = tmd.data() + table_offset + i * kTmdContentEntrySize;
            ContentEntry parsed{};
            parsed.id = ReadBe32(entry);
            parsed.index = ReadBe16(entry + 4);
            parsed.type = ReadBe16(entry + 6);
            parsed.size = ReadBe64(entry + 8);
            if (parsed.size == 0 || parsed.index >= out->count) {
                return false;
            }
            total += Common::AlignUp(parsed.size, 0x40ULL);
            out->entries.push_back(parsed);
        }
        std::sort(out->entries.begin(), out->entries.end(),
                  [](const ContentEntry& a, const ContentEntry& b) { return a.index < b.index; });
        return true;
    };

    if (read_table(base + 0xA4)) {
        return true;
    }
    const size_t end_table = tmd.size() - static_cast<size_t>(out->count) * kTmdContentEntrySize;
    return read_table(end_table);
}

std::optional<std::array<u8, 16>> ReadTitleKey(int fd, u64 ticket_offset, u32 ticket_size) {
    if (ticket_size < 0x250) {
        return std::nullopt;
    }
    std::vector<u8> ticket(ticket_size);
    if (!ReadFd(fd, ticket_offset, ticket.data(), ticket.size())) {
        return std::nullopt;
    }
    FileSys::Ticket parsed;
    if (parsed.Load(std::span<const u8>(ticket.data(), ticket.size())) !=
        Loader::ResultStatus::Success) {
        return std::nullopt;
    }
    return parsed.GetTitleKey();
}

bool DecryptRegion(int fd, u64 file_offset, u64 seek_from, const HW::AES::AESKey& key,
                   const std::array<u8, 16>& ctr, u64 size) {
    if (size == 0) {
        return true;
    }
    std::vector<u8> buffer(static_cast<size_t>(size));
    if (!ReadFd(fd, file_offset, buffer.data(), buffer.size())) {
        return false;
    }
    CryptoPP::CTR_Mode<CryptoPP::AES>::Decryption decryption(key.data(), key.size(), ctr.data());
    const u64 offset = file_offset - seek_from;
    if (offset != 0) {
        decryption.Seek(offset);
    }
    decryption.ProcessData(buffer.data(), buffer.data(), buffer.size());
    return WriteFd(fd, file_offset, buffer.data(), buffer.size());
}

// Removes the NCCH secure crypto layer in place and flags the image as
// decrypted so the libretro loader accepts it. Mirrors NCCHCryptoFile.
int DecryptNcch(int fd) {
    NCCH_Header header{};
    if (!ReadFd(fd, 0, &header, sizeof(header))) {
        return -1;
    }
    if (header.magic != FileUtil::MakeMagic('N', 'C', 'C', 'H')) {
        return -1;
    }
    if (header.no_crypto) {
        return 0;
    }

    HW::AES::InitKeys();
    HW::AES::AESKey primary_key{};
    HW::AES::AESKey secondary_key{};

    if (header.fixed_key) {
        primary_key.fill(0);
        secondary_key.fill(0);
    } else {
        HW::AES::AESKey key_y_primary{};
        std::copy(header.signature, header.signature + key_y_primary.size(), key_y_primary.begin());
        HW::AES::AESKey key_y_secondary = key_y_primary;
        if (header.seed_crypto) {
            // Seed crypto needs the console's seeddb; the frontend cannot
            // provide it, so these titles stay unsupported.
            LOG_ERROR(Frontend, "Seed crypto is not supported by the EmuCoreD helper");
            return -4;
        }

        HW::AES::SetKeyY(HW::AES::KeySlotID::NCCHSecure1, key_y_primary);
        if (!HW::AES::IsNormalKeyAvailable(HW::AES::KeySlotID::NCCHSecure1)) {
            LOG_ERROR(Frontend, "Secure1 KeyX is missing");
            return -2;
        }
        primary_key = HW::AES::GetNormalKey(HW::AES::KeySlotID::NCCHSecure1);

        const auto load_secondary = [&](HW::AES::KeySlotID slot) -> bool {
            HW::AES::SetKeyY(slot, key_y_secondary);
            if (!HW::AES::IsNormalKeyAvailable(slot)) {
                LOG_ERROR(Frontend, "Secure KeyX is missing");
                return false;
            }
            secondary_key = HW::AES::GetNormalKey(slot);
            return true;
        };
        switch (header.secondary_key_slot) {
        case 0:
            if (!load_secondary(HW::AES::KeySlotID::NCCHSecure1)) return -2;
            break;
        case 1:
            if (!load_secondary(HW::AES::KeySlotID::NCCHSecure2)) return -2;
            break;
        case 10:
            if (!load_secondary(HW::AES::KeySlotID::NCCHSecure3)) return -2;
            break;
        case 11:
            if (!load_secondary(HW::AES::KeySlotID::NCCHSecure4)) return -2;
            break;
        default:
            LOG_ERROR(Frontend, "Unknown secondary key slot {}", header.secondary_key_slot);
            return -4;
        }
    }

    std::array<u8, 16> exheader_ctr{};
    std::array<u8, 16> exefs_ctr{};
    std::array<u8, 16> romfs_ctr{};
    if (header.version == 0 || header.version == 2) {
        std::reverse_copy(header.partition_id, header.partition_id + 8, exheader_ctr.begin());
        exefs_ctr = romfs_ctr = exheader_ctr;
        exheader_ctr[8] = 1;
        exefs_ctr[8] = 2;
        romfs_ctr[8] = 3;
    } else if (header.version == 1) {
        std::copy(header.partition_id, header.partition_id + 8, exheader_ctr.begin());
        exefs_ctr = romfs_ctr = exheader_ctr;
        const auto write_be32 = [](std::array<u8, 16>& ctr, u32 value) {
            ctr[12] = static_cast<u8>(value >> 24);
            ctr[13] = static_cast<u8>((value >> 16) & 0xFF);
            ctr[14] = static_cast<u8>((value >> 8) & 0xFF);
            ctr[15] = static_cast<u8>(value & 0xFF);
        };
        write_be32(exheader_ctr, 0x200);
        write_be32(exefs_ctr, header.exefs_offset * kBlockSize);
        write_be32(romfs_ctr, header.romfs_offset * kBlockSize);
    } else {
        LOG_ERROR(Frontend, "Unsupported NCCH version {}", header.version);
        return -4;
    }

    if (header.extended_header_size != 0) {
        if (!DecryptRegion(fd, sizeof(NCCH_Header), sizeof(NCCH_Header), primary_key, exheader_ctr,
                           kExHeaderSize)) {
            return -1;
        }
    }

    if (header.exefs_size != 0) {
        const u64 exefs_offset = static_cast<u64>(header.exefs_offset) * kBlockSize;
        if (!DecryptRegion(fd, exefs_offset, exefs_offset, primary_key, exefs_ctr,
                           sizeof(ExeFs_Header))) {
            return -1;
        }
        ExeFs_Header exefs{};
        if (!ReadFd(fd, exefs_offset, &exefs, sizeof(exefs))) {
            return -1;
        }
        for (const auto& section : exefs.section) {
            if (section.size == 0) {
                continue;
            }
            const bool is_primary = std::memcmp(section.name, "icon", 4) == 0 ||
                                    std::memcmp(section.name, "banner", 6) == 0;
            const u64 offset = exefs_offset + sizeof(ExeFs_Header) + section.offset;
            const u64 size = Common::AlignUp(section.size, kBlockSize);
            if (!DecryptRegion(fd, offset, exefs_offset, is_primary ? primary_key : secondary_key,
                               exefs_ctr, size)) {
                return -1;
            }
        }
    }

    if (header.romfs_size != 0) {
        const u64 romfs_offset = static_cast<u64>(header.romfs_offset) * kBlockSize;
        if (!DecryptRegion(fd, romfs_offset, romfs_offset, secondary_key, romfs_ctr,
                           static_cast<u64>(header.romfs_size) * kBlockSize)) {
            return -1;
        }
    }

    header.no_crypto.Assign(1);
    if (!WriteFd(fd, 0, &header, sizeof(header))) {
        return -1;
    }
    return 0;
}

} // namespace

extern "C" {

// Extracts the requested CIA content into out_path and returns it as a plain,
// decrypted NCCH image the libretro loader can open.
//
// Returns 0 on success, -1 for a malformed container, -2 when the required
// keys are missing, -3 for encrypted content without a usable title key and
// -4 for unsupported (seed) crypto.
int emucored_extract_cia_content_fd(int fd, const char* out_path, unsigned content_index) {
    if (fd < 0 || out_path == nullptr) {
        return -1;
    }
    std::array<u8, kCiaHeaderSize> header{};
    if (!ReadFd(fd, 0, header.data(), header.size()) || ReadLe32(header.data()) != kCiaHeaderSize) {
        return -1;
    }
    const u32 cert_size = ReadLe32(header.data() + 0x08);
    const u32 ticket_size = ReadLe32(header.data() + 0x0C);
    const u32 tmd_size = ReadLe32(header.data() + 0x10);
    if (tmd_size == 0 || tmd_size > 16 * 1024 * 1024) {
        return -1;
    }

    const u64 ticket_offset = Common::AlignUp(static_cast<u64>(kCiaHeaderSize) + cert_size, 0x40ULL);
    const u64 tmd_offset = Common::AlignUp(ticket_offset + ticket_size, 0x40ULL);
    const u64 content_offset = Common::AlignUp(tmd_offset + tmd_size, 0x40ULL);

    std::vector<u8> tmd(tmd_size);
    if (!ReadFd(fd, tmd_offset, tmd.data(), tmd.size())) {
        return -1;
    }
    TmdInfo info;
    if (!ParseTmdEntries(tmd, 0, &info)) {
        return -1;
    }
    if (content_index >= info.count) {
        content_index = info.boot_index;
    }
    const auto entry = std::find_if(info.entries.begin(), info.entries.end(),
                                    [content_index](const ContentEntry& candidate) {
                                        return candidate.index == content_index;
                                    });
    if (entry == info.entries.end()) {
        return -1;
    }

    // Walk the contents in index order to find the requested one.
    u64 offset = content_offset;
    for (const auto& current : info.entries) {
        if (current.index == entry->index) {
            break;
        }
        offset = Common::AlignUp(offset + current.size, 0x40ULL);
    }

    std::vector<u8> content(static_cast<size_t>(entry->size));
    if (!ReadFd(fd, offset, content.data(), content.size())) {
        return -1;
    }

    const bool content_encrypted = (entry->type & 0x01) != 0;
    if (content_encrypted) {
        const auto title_key = ReadTitleKey(fd, ticket_offset, ticket_size);
        if (!title_key.has_value()) {
            LOG_ERROR(Frontend, "CIA title key is unavailable");
            return -3;
        }
        std::array<u8, 16> iv{};
        CryptoPP::CBC_Mode<CryptoPP::AES>::Decryption decryption(title_key->data(),
                                                                 title_key->size(), iv.data());
        decryption.ProcessData(content.data(), content.data(), content.size());
    }

    const int output = open(out_path, O_RDWR | O_CREAT | O_TRUNC, 0666);
    if (output < 0) {
        return -1;
    }
    const bool written = WriteFd(output, 0, content.data(), content.size());
    if (!written) {
        close(output);
        return -1;
    }

    const int result = DecryptNcch(output);
    close(output);
    if (result != 0) {
        remove(out_path);
    }
    return result;
}

} // extern "C"
