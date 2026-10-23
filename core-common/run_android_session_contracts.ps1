param(
 [Parameter(Mandatory=$true)][int]$TransportId,
 [string]$SdkDirectory = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference='Stop'
$adb=Join-Path $SdkDirectory 'platform-tools/adb.exe'
$model=(& $adb -t $TransportId shell getprop ro.product.model).Trim()
if($LASTEXITCODE -ne 0 -or $model -ne 'CPH2747'){throw 'Only the explicitly authorized OnePlus CPH2747 may run these contracts'}
$root=Split-Path -Parent $PSScriptRoot
$cmake=Join-Path $SdkDirectory 'cmake/3.30.5/bin/cmake.exe'
$remote='/data/local/tmp/emucored-handheld-session'
function Checked([string]$tool,[string[]]$arguments){& $tool @arguments;if($LASTEXITCODE -ne 0){throw "Command failed ($LASTEXITCODE): $tool"}}
Checked $adb @('-t',"$TransportId",'shell','mkdir','-p',$remote)
foreach($engine in @(@{Dir='coreGBA';Build='android-contract';Id='gba'},@{Dir='coreGB';Build='android-contract';Id='gb'},@{Dir='coreNES';Build='android-runtime';Id='nes'})){
 $source=Join-Path $root $engine.Dir
 $build=Join-Path $source "build/$($engine.Build)"
 Checked $cmake @('-S',$source,'-B',$build,'-G','Ninja',"-DCMAKE_MAKE_PROGRAM=$SdkDirectory/cmake/3.30.5/bin/ninja.exe","-DCMAKE_TOOLCHAIN_FILE=$SdkDirectory/ndk/29.0.14206865/build/cmake/android.toolchain.cmake",'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release','-DEMUCORED_BUILD_ANDROID_RUNTIME=ON','-DEMUCORED_BUILD_CORE_TESTS=ON')
 $binary="emucored_$($engine.Id)_session_contract"
 Checked $cmake @('--build',$build,'--target',"emucored_$($engine.Id)",$binary,'--parallel','4')
 Checked $adb @('-t',"$TransportId",'push',(Join-Path $build $binary),"$remote/$binary")
 Checked $adb @('-t',"$TransportId",'shell','chmod','700',"$remote/$binary")
 Checked $adb @('-t',"$TransportId",'shell',"$remote/$binary",$remote)
}
