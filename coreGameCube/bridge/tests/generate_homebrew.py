# SPDX-License-Identifier: CC0-1.0
"""Generate original, redistributable GameCube and Wii DOL video fixtures.

No Nintendo SDK, copyrighted game data, firmware or external toolchain required.
The PPC program programs the documented VI registers and displays YUYV bars.
Wii's fixture contains an unreachable HID4 instruction, as required by Dolphin's
executable reader to distinguish Wii mode from GameCube mode.
"""
from pathlib import Path
import struct
import sys

def make_dol(wii: bool) -> bytes:
    code = [0x3C60CC00, 0x60632000]  # lis r3,0xcc00; ori r3,r3,0x2000 (VI)
    def store16(offset, value):
        code.extend([0x38800000 | value, 0xB0830000 | offset])
    store16(0x00, (240 << 4) | 6)  # active lines, equalization
    store16(0x02, 5)  # enable, noninterlaced
    store16(0x0C, 5)  # odd post blanking
    store16(0x0E, 22)  # odd pre blanking
    store16(0x10, 4)  # even post blanking
    store16(0x12, 23)  # even pre blanking
    store16(0x48, 0x1414)  # 320 pixels, 640 byte stride
    code.extend([0x3C800010, 0x9083001C, 0x90830024])  # XFB at physical 0x100000
    code.append(0x48000000)  # b .
    if wii:
        code.append(0x7C13FBA6)  # unreachable mtspr HID4, distinguishes Wii DOL
    while len(code) % 8:
        code.append(0x60000000)
    text = b''.join(struct.pack('>I', word) for word in code)
    # Two pixels per YUYV unit; three visible vertical bars.
    colors = [bytes([90, 90, 90, 240]), bytes([145, 54, 145, 34]), bytes([41, 240, 41, 110])]
    row = b''.join(colors[min(2, x * 3 // 160)] for x in range(160))
    pixels = row * 240
    header = bytearray(0x100)
    for offset, value in [(0x00, 0x100), (0x48, 0x80004000), (0x90, len(text)),
                          (0x1C, 0x100 + len(text)), (0x64, 0x80100000),
                          (0xAC, len(pixels)), (0xE0, 0x80004000)]:
        struct.pack_into('>I', header, offset, value)
    return bytes(header) + text + pixels

if __name__ == '__main__':
    output = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/dolphin-homebrew')
    output.mkdir(parents=True, exist_ok=True)
    for wii in (False, True):
        path = output / ('wii-video.dol' if wii else 'gamecube-video.dol')
        path.write_bytes(make_dol(wii))
        print(f'{path}: {path.stat().st_size} bytes')
