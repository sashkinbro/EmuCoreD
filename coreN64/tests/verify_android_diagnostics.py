#!/usr/bin/env python3
"""Check compiled N64 bridge diagnostics in release and debug libraries.

Usage: python verify_android_diagnostics.py <llvm-nm> <release-bridge> <debug-bridge> <release-core> <debug-core>
"""
import subprocess
import sys


def imports(nm, library):
    result = subprocess.run([nm, '-D', '--undefined-only', library],
                            check=True, capture_output=True, text=True)
    return {line.split()[-1] for line in result.stdout.splitlines() if line.split()}


def main():
    if len(sys.argv) != 6:
        raise SystemExit(__doc__)
    nm, release, debug, release_core, debug_core = sys.argv[1:]
    release_imports = imports(nm, release)
    debug_imports = imports(nm, debug)
    if '__android_log_vprint' in release_imports:
        raise SystemExit('FAIL: release N64 bridge imports Android diagnostic logging')
    if '__android_log_vprint' not in debug_imports:
        raise SystemExit('FAIL: debug N64 bridge lost diagnostic logging')
    logging_functions = {'__android_log_print', '__android_log_vprint', '__android_log_write'}
    remaining = imports(nm, release_core) & logging_functions
    if remaining:
        raise SystemExit(f'FAIL: release N64 core imports Android diagnostic logging: {sorted(remaining)}')
    if not imports(nm, debug_core) & logging_functions:
        raise SystemExit('FAIL: debug N64 core lost diagnostic logging')
    print('PASS: release bridge and core diagnostics disabled; debug logging available')


if __name__ == '__main__':
    main()
