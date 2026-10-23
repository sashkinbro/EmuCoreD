# SPDX-License-Identifier: GPL-3.0-or-later
param(
    [string]$SdkDirectory = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'),
    [int]$Jobs = 4,
    [switch]$InitializeDependencies
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Invoke-Checked([string]$Executable, [string[]]$ArgumentList) {
    & $Executable @ArgumentList
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}
if ($Jobs -lt 1) { throw 'Jobs must be positive' }
$sdkRoot = [IO.Path]::GetFullPath($SdkDirectory)
$sourceDirectory = Join-Path $PSScriptRoot 'dolphin'
$buildDirectory = Join-Path $PSScriptRoot 'build/android-core'
$cmake = Join-Path $sdkRoot 'cmake/3.30.5/bin/cmake.exe'
$ninja = Join-Path $sdkRoot 'cmake/3.30.5/bin/ninja.exe'
$toolchain = Join-Path $sdkRoot 'ndk/29.0.14206865/build/cmake/android.toolchain.cmake'
foreach ($required in @($cmake, $ninja, $toolchain)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing required tool: $required" }
}
if ($InitializeDependencies) {
    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    Invoke-Checked 'git' @('-C', $repositoryRoot, 'submodule', 'update', '--init', '--depth', '1', 'coreGameCube/dolphin')
    # Only dependencies used by this Android configuration, always at parent pins.
    $dependencies = @(
        'Externals/cpp-optparse/cpp-optparse', 'Externals/fmt/fmt', 'Externals/imgui/imgui',
        'Externals/implot/implot', 'Externals/glslang/glslang', 'Externals/tinygltf/tinygltf',
        'Externals/libadrenotools', 'Externals/pugixml/pugixml', 'Externals/enet/enet',
        'Externals/xxhash/xxHash', 'Externals/bzip2/bzip2', 'Externals/zstd/zstd',
        'Externals/zlib-ng/zlib-ng', 'Externals/minizip-ng/minizip-ng', 'Externals/lz4/lz4',
        'Externals/libspng/libspng', 'Externals/cubeb/cubeb', 'Externals/SFML/SFML',
        'Externals/curl/curl', 'Externals/watcher/watcher',
        'Externals/VulkanMemoryAllocator', 'Externals/Vulkan-Headers'
    )
    Invoke-Checked 'git' (@('-C', $sourceDirectory, 'submodule', 'update', '--init', '--depth', '1', '--jobs', "$Jobs", '--') + $dependencies)
    Invoke-Checked 'git' @('-C', (Join-Path $sourceDirectory 'Externals/libadrenotools'),
        'submodule', 'update', '--init', '--depth', '1', 'lib/linkernsbypass')
}
Invoke-Checked $cmake @('-S', $sourceDirectory, '-B', $buildDirectory, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release', '-DENABLE_QT=OFF', '-DENABLE_TESTS=OFF', '-DENABLE_LLVM=OFF',
    '-DENABLE_ANALYTICS=OFF', '-DENABLE_AUTOUPDATE=OFF', '-DENCODE_FRAMEDUMPS=OFF',
    '-DUSE_UPNP=OFF', '-DUSE_DISCORD_PRESENCE=OFF', '-DUSE_RETRO_ACHIEVEMENTS=OFF',
    '-DUSE_MGBA=OFF', '-DUSE_SANITIZERS=OFF', '-DENABLE_VULKAN=ON', '-DENABLE_SDL=OFF')
# Static engine build only. This neither builds nor packages upstream Android UI/JNI.
Invoke-Checked $cmake @('--build', $buildDirectory, '--target', 'core', '--parallel', "$Jobs")
