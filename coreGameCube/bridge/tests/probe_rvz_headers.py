# SPDX-License-Identifier: GPL-3.0-or-later
"""Read actual RVZ embedded disc headers and verify both container SHA1s.
Layout follows the pinned Dolphin DiscIO/WIABlob.h; no filename classification.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys

def probe(path):
    with path.open('rb') as stream:
        first = stream.read(0x48)
        if first[:4] != b'RVZ\x01':
            raise ValueError('rvz_magic_invalid')
        second_size = struct.unpack_from('>I', first, 0x0c)[0]
        if not 0x90 <= second_size <= 0x10000:
            raise ValueError('rvz_header_size_invalid')
        second = stream.read(second_size)
        if hashlib.sha1(first[:0x34]).digest() != first[0x34:0x48]:
            raise ValueError('rvz_header1_hash_invalid')
        if hashlib.sha1(second).digest() != first[0x10:0x24]:
            raise ValueError('rvz_header2_hash_invalid')
        disc = second[0x10:0x90]
        platform = ('wii' if struct.unpack_from('>I', disc, 0x18)[0] == 0x5d1c9ea3
                    else 'gamecube' if struct.unpack_from('>I', disc, 0x1c)[0] == 0xc2339f3d else None)
        return dict(file=path.name, platform=platform, game_id=disc[:6].decode('ascii'),
                    revision=disc[7], title=disc[0x20:].split(b'\0')[0].decode('utf8', 'replace'),
                    header_sha1_valid=True)

if __name__ == '__main__':
    for source in sys.argv[1:]:
        paths = sorted(Path(source).glob('*.rvz')) if Path(source).is_dir() else [Path(source)]
        for path in paths:
            print(json.dumps(probe(path), ensure_ascii=False))
