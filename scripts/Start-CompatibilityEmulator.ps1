param([string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk", [int]$Port = 5582)
$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath("$PSScriptRoot/..")
$imageDirectory = Join-Path $SdkPath 'system-images/android-26/default/x86'
if (!(Test-Path -LiteralPath (Join-Path $imageDirectory 'system.img'))) {
    $imageDirectory = Join-Path $workspace 'test-artifacts/api26-aosp/x86'
}
if (!(Test-Path -LiteralPath (Join-Path $imageDirectory 'system.img'))) { throw 'Install the official API 26 default x86 system image first.' }
$avdRoot = Join-Path $workspace 'test-artifacts/api26-avd'
$avdPath = Join-Path $avdRoot 'LumbreApi26.avd'
New-Item -ItemType Directory -Path $avdPath -Force | Out-Null
@"
avd.ini.encoding=UTF-8
path=$avdPath
target=android-26
"@ | Set-Content -LiteralPath (Join-Path $avdRoot 'LumbreApi26.ini') -Encoding utf8
@"
AvdId=LumbreApi26
avd.ini.encoding=UTF-8
avd.ini.displayname=Lumbre API 26 Compatibility
abi.type=x86
hw.cpu.arch=x86
hw.cpu.ncore=2
hw.ramSize=1024
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=320
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.audioInput=no
hw.mainKeys=no
disk.dataPartition.size=1536M
image.sysdir.1=$imageDirectory
tag.id=default
tag.display=Default
target=android-26
showDeviceFrame=no
PlayStore.enabled=false
"@ | Set-Content -LiteralPath (Join-Path $avdPath 'config.ini') -Encoding utf8
$env:ANDROID_AVD_HOME = $avdRoot
$env:ANDROID_HOME = $SdkPath
$process = Start-Process -FilePath (Join-Path $SdkPath 'emulator/emulator.exe') -ArgumentList @('-avd','LumbreApi26','-no-window','-no-audio','-no-snapshot','-gpu','swiftshader','-accel','on','-port',"$Port") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $workspace 'test-artifacts/api26-emulator.log') -RedirectStandardError (Join-Path $workspace 'test-artifacts/api26-emulator-error.log')
Write-Output "Started isolated API 26 emulator PID $($process.Id), serial emulator-$Port."
