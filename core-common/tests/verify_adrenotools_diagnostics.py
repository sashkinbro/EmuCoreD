#!/usr/bin/env python3
"""Verify the driver hook disables logcat in release and preserves it in debug.

Usage: python verify_adrenotools_diagnostics.py <llvm-nm> <release-so> <debug-so>
"""
import subprocess
import sys


def imports(nm, library):
    result = subprocess.run([nm, '-D', '--undefined-only', library],
                            check=True, capture_output=True, text=True)
    return {line.split()[-1] for line in result.stdout.splitlines() if line.split()}


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    nm, release, debug = sys.argv[1:]
    if '__android_log_print' in imports(nm, release):
        raise SystemExit('FAIL: release GPU driver hook imports Android diagnostic logging')
    if '__android_log_print' not in imports(nm, debug):
        raise SystemExit('FAIL: debug GPU driver hook lost diagnostic logging')
    print('PASS: release GPU driver hook diagnostics disabled; debug logging available')


if __name__ == '__main__':
    main()
