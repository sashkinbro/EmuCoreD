param(
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [ValidateSet('all', 'gba', 'gb')][string]$Core = 'all',
    [string]$SdkDirectory = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'),
    [int]$Jobs = 4
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Checked([string]$Executable, [string[]]$ArgumentList) {
    & $Executable @ArgumentList
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = [System.IO.Path]::GetFullPath($SdkDirectory)
$cmake = Join-Path $sdkRoot 'cmake/3.30.5/bin/cmake.exe'
$ninja = Join-Path $sdkRoot 'cmake/3.30.5/bin/ninja.exe'
$toolchain = Join-Path $sdkRoot 'ndk/29.0.14206865/build/cmake/android.toolchain.cmake'
$adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
foreach ($required in @($cmake, $ninja, $toolchain, $adb)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing required tool: $required" }
}
if ($Jobs -lt 1) { throw 'Jobs must be positive' }
$deviceAbi = (& $adb -s $DeviceSerial shell getprop ro.product.cpu.abilist) -join ''
if ($LASTEXITCODE -ne 0 -or 'arm64-v8a' -notin $deviceAbi.Trim().Split(',')) {
    throw 'The explicitly selected device must support arm64-v8a'
}
Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'mkdir', '-p', '/data/local/tmp/emucored-core-contracts')
$engines = @(
    @{ Id = 'gba'; Directory = 'coreGBA'; Binary = 'emucored_mgba_contract_test' },
    @{ Id = 'gb'; Directory = 'coreGB'; Binary = 'emucored_sameboy_contract_test' }
)
foreach ($engine in $engines) {
    if ($Core -ne 'all' -and $Core -ne $engine.Id) { continue }
    $sourceDirectory = Join-Path $repositoryRoot $engine.Directory
    $buildDirectory = Join-Path $sourceDirectory 'build/android-contract'
    Invoke-Checked $cmake @('-S', $sourceDirectory, '-B', $buildDirectory, '-G', 'Ninja',
        "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
        '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
        '-DCMAKE_BUILD_TYPE=Release', '-DEMUCORED_BUILD_CORE_TESTS=ON')
    Invoke-Checked $cmake @('--build', $buildDirectory, '--parallel', "$Jobs")
    $localBinary = Join-Path $buildDirectory $engine.Binary
    $remoteBinary = '/data/local/tmp/emucored-core-contracts/' + $engine.Binary
    Invoke-Checked $adb @('-s', $DeviceSerial, 'push', $localBinary, $remoteBinary)
    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'chmod', '700', $remoteBinary)
    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', $remoteBinary)
}
