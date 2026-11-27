param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('all', 'selftest', 'difftest', 'bench', 'build-only')][string]$Task = 'all',
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$NdkVersion = '29.0.14206865',
    [string]$RomDir = 'D:\Nintendo\DS',
    [int]$Frames = 180,
    [int]$Warmup = 120,
    [int]$Jobs = 6,
    [string[]]$Roms = @(
        'Super Mario 64 DS (USA).nds',
        'Mario Kart DS (USA, Australia) (En,Fr,De,Es,It).nds',
        'New Super Mario Bros. (USA).nds',
        'Metroid Prime - Hunters (USA).nds',
        'Legend of Zelda, The - Phantom Hourglass (USA) (En,Fr,Es).nds',
        'Contra 4 (USA).nds',
        'Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds',
        'Grand Theft Auto - Chinatown Wars (USA) (En,Fr,De,Es,It).nds'
    ),
    [string[]]$Modes = @('soft_1x', 'vulkan_1x', 'vulkan_2x', 'vulkan_4x')
)

$ErrorActionPreference = 'Stop'
if ($Serial -match 'CPH2747|3B165F0008800000') {
    throw "REFUSED: Serial $Serial belongs to user's personal phone."
}

$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$coreDsDir = Join-Path $root 'coreDS'
$buildDir = Join-Path $coreDsDir 'build\android-tools'
$cmake = Join-Path $Sdk 'cmake\3.30.5\bin\cmake.exe'
$ninja = Join-Path $Sdk 'cmake\3.30.5\bin\ninja.exe'
$toolchain = Join-Path $Sdk "ndk\$NdkVersion\build\cmake\android.toolchain.cmake"
$adb = Join-Path $Sdk 'platform-tools\adb.exe'

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

Write-Host "=== Building emucored_ds_jit_difftest ==="
Invoke-Checked $cmake @(
    '-S', $coreDsDir, '-B', $buildDir, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-26',
    '-DANDROID_STL=c++_static',
    '-DANDROID_ARM_NEON=true',
    '-DCMAKE_BUILD_TYPE=Release',
    '-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG',
    '-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG',
    '-DEMUCORED_DIAGNOSTICS=OFF',
    '-DEMUCORED_BUILD_DS_TOOLS=ON'
)
Invoke-Checked $cmake @('--build', $buildDir, '--target', 'emucored_ds_jit_difftest', '--parallel', "$Jobs")

if ($Task -eq 'build-only') { exit 0 }

$remoteDir = '/data/local/tmp/emucored-ds'
$remoteBin = "$remoteDir/emucored_ds_jit_difftest"
$remoteRoms = "$remoteDir/roms"
Invoke-Checked $adb @('-s', $Serial, 'shell', "mkdir -p $remoteRoms $remoteDir/ppm")
Invoke-Checked $adb @('-s', $Serial, 'push', (Join-Path $buildDir 'emucored_ds_jit_difftest'), $remoteBin)
Invoke-Checked $adb @('-s', $Serial, 'shell', "chmod 755 $remoteBin")

if ($Task -eq 'all' -or $Task -eq 'selftest') {
    Write-Host "`n=== Running JIT Self-Tests ($Serial) ==="
    Invoke-Checked $adb @('-s', $Serial, 'shell', "$remoteBin --self-test")
}

if ($Task -eq 'all' -or $Task -eq 'difftest' -or $Task -eq 'bench') {
    $existingRemote = (& $adb -s $Serial shell "ls -1 $remoteRoms" 2>$null) -split "`r?`n"
    foreach ($rom in $Roms) {
        $localRom = Join-Path $RomDir $rom
        if (-not (Test-Path -LiteralPath $localRom)) {
            Write-Warning "Skipping missing ROM: $localRom"
            continue
        }
        $safeName = $rom -replace '[^A-Za-z0-9._-]', '_'
        if ($safeName -notin $existingRemote) {
            Write-Host "Pushing $rom -> $remoteRoms/$safeName"
            Invoke-Checked $adb @('-s', $Serial, 'push', $localRom, "$remoteRoms/$safeName")
        }
    }
}

if ($Task -eq 'all' -or $Task -eq 'difftest') {
    Write-Host "`n=== Running Interpreter vs JIT ROM DiffTests ($Serial) ==="
    foreach ($rom in $Roms) {
        $safeName = $rom -replace '[^A-Za-z0-9._-]', '_'
        $localRom = Join-Path $RomDir $rom
        if (-not (Test-Path -LiteralPath $localRom)) { continue }
        Write-Host "--- $rom ---"
        & $adb -s $Serial shell "$remoteBin --rom $remoteRoms/$safeName --frames $Frames --bisect"
    }
}

if ($Task -eq 'all' -or $Task -eq 'bench') {
    Write-Host "`n=== Running DS Benchmarks ($Serial) ==="
    foreach ($rom in $Roms) {
        $safeName = $rom -replace '[^A-Za-z0-9._-]', '_'
        $localRom = Join-Path $RomDir $rom
        if (-not (Test-Path -LiteralPath $localRom)) { continue }
        Write-Host "`n--- $rom ---"
        foreach ($mode in $Modes) {
            $ppmArg = ""
            if ($mode -in @('soft_1x', 'vulkan_1x', 'vulkan_2x')) {
                $ppmArg = "--dump-ppm $remoteDir/ppm/${safeName}_${mode}.ppm"
            }
            & $adb -s $Serial shell "$remoteBin --bench --rom $remoteRoms/$safeName --mode $mode --warmup $Warmup --frames $Frames $ppmArg"
        }
    }
}
