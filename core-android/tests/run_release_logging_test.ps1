# SPDX-FileCopyrightText: 2026 SBRO
# SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
[CmdletBinding()]
param(
    [string]$NdkPath = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/ndk/29.0.14206865'),
    [string]$DeviceSerial = '',
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'),
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'logs/release-logging-regression' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path
$toolchain = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$compiler = Join-Path $toolchain 'clang++.exe'
$fmtInclude = Join-Path $repoRoot 'core/externals/fmt/include'
if (-not (Test-Path (Join-Path $fmtInclude 'fmt/format.h'))) { throw 'Initialize the repository fmt submodule before running this test.' }
$executable = Join-Path $OutputDirectory 'release_logging_test'
$compileArgs = @(
    '--target=aarch64-linux-android26', '-std=c++20', '-O2', '-static-libstdc++',
    '-DANDROID', '-DEMUCORED_DIAGNOSTICS=0',
    "-I$(Join-Path $repoRoot 'core/src')", "-I$fmtInclude",
    (Join-Path $PSScriptRoot 'release_logging_test.cpp'),
    (Join-Path $repoRoot 'core/src/common/logging/backend.cpp'),
    (Join-Path $repoRoot 'core/src/common/logging/text_formatter.cpp'),
    '-o', $executable
)
$compileOutput = & $compiler @compileArgs 2>&1
$compileStatus = $LASTEXITCODE
$compileOutput | Set-Content (Join-Path $OutputDirectory 'compile.log')
if ($compileStatus -ne 0) { throw "Native logging regression did not compile. See $OutputDirectory/compile.log" }
$symbols = & (Join-Path $toolchain 'llvm-nm.exe') -C $executable
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect native regression symbols.' }
if ($symbols -match 'Common::Log::.*(FileBackend|LogcatBackend|Impl::)') { throw 'Release executable retains a diagnostic backend implementation.' }
$strings = & (Join-Path $toolchain 'llvm-strings.exe') $executable
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect native regression strings.' }
if ($strings -match 'discarded-release-log-format') { throw 'Disabled LOG macros retain a format string.' }
Write-Output 'PASS native compile, discarded arguments/format strings, and backend implementation exclusion'
if (-not $DeviceSerial) {
    Write-Output 'Device execution skipped. Supply -DeviceSerial to verify the real APIs on an arm64 Android device.'
    exit 0
}

# No package installation, input, app session, or user storage is touched.
$probeId = [Guid]::NewGuid().ToString('N')
$remoteExecutable = "/data/local/tmp/emucored-release-logging-$probeId"
$remoteLog = "$remoteExecutable.log"
& $AdbPath -s $DeviceSerial push $executable $remoteExecutable | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot push the native regression executable.' }
try {
    $deviceOutput = & $AdbPath -s $DeviceSerial shell "chmod 700 $remoteExecutable && $remoteExecutable $remoteLog" 2>&1
    $deviceStatus = $LASTEXITCODE
    $deviceOutput | Set-Content (Join-Path $OutputDirectory 'device.log')
    if ($deviceStatus -ne 0) { throw "Native device regression failed (exit $deviceStatus). See $OutputDirectory/device.log" }
    if ($deviceOutput.Count -ne 1 -or $deviceOutput -notmatch '^PASS release logging:') {
        throw 'Unexpected output: disabled logger may have written to stdout/stderr. See device.log.'
    }
    Write-Output $deviceOutput
} finally {
    # Only this invocation's unique executable and sentinel path are eligible for cleanup.
    & $AdbPath -s $DeviceSerial shell "rm -f $remoteExecutable $remoteLog" | Out-Null
}
