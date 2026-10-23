param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$NdkVersion = '29.0.14206865'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$taskAdb = Join-Path $Sdk 'platform-tools\adb.exe'
$taskCompiler = Join-Path $Sdk "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android28-clang++.cmd"
$taskOutput = Join-Path $taskRoot 'logs\android-gpu-tests'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

$taskBinary = Join-Path $taskOutput 'ds-presenter-color-test'
Invoke-Checked $taskCompiler @('-std=c++17', '-Wall', '-Wextra', '-fPIE', '-pie',
    (Join-Path $PSScriptRoot 'ds_presenter_color_test.cpp'), '-lEGL', '-lGLESv3', '-o', $taskBinary)
$taskRemote = '/data/local/tmp/emucored-ds-color-test'
Invoke-Checked $taskAdb @('-s', $Serial, 'push', $taskBinary, $taskRemote)
Invoke-Checked $taskAdb @('-s', $Serial, 'shell', 'chmod', '755', $taskRemote)
Invoke-Checked $taskAdb @('-s', $Serial, 'shell', $taskRemote)
