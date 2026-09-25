param([string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath("$PSScriptRoot/..")
$imageDirectory = Join-Path $workspace 'test-artifacts/android-image/x86_64'
if (!(Test-Path -LiteralPath (Join-Path $imageDirectory 'system.img'))) { throw 'Extract the official Android 35 x86_64 image into test-artifacts/android-image first.' }
$avdRoot = Join-Path $workspace 'test-artifacts/avd'
$avdPath = Join-Path $avdRoot 'LumbreTest.avd'
New-Item -ItemType Directory -Path $avdPath -Force | Out-Null
@"
avd.ini.encoding=UTF-8
path=$avdPath
target=android-35
"@ | Set-Content -LiteralPath (Join-Path $avdRoot 'LumbreTest.ini') -Encoding utf8
@"
AvdId=LumbreTest
avd.ini.encoding=UTF-8
avd.ini.displayname=Lumbre Test
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.audioInput=no
hw.mainKeys=no
hw.device.name=pixel_2
disk.dataPartition.size=2G
image.sysdir.1=$imageDirectory
tag.id=default
tag.display=Default
target=android-35
fastboot.forceColdBoot=yes
showDeviceFrame=no
PlayStore.enabled=false
"@ | Set-Content -LiteralPath (Join-Path $avdPath 'config.ini') -Encoding utf8
$env:ANDROID_AVD_HOME = $avdRoot
$env:ANDROID_HOME = $SdkPath
$emulatorPath = Join-Path $SdkPath 'emulator/emulator.exe'
$logPath = Join-Path $workspace 'test-artifacts/emulator.log'
$errorPath = Join-Path $workspace 'test-artifacts/emulator-error.log'
$process = Start-Process -FilePath $emulatorPath -ArgumentList @('-avd','LumbreTest','-no-window','-no-audio','-no-snapshot','-gpu','swiftshader','-accel','on','-port','5580') -WindowStyle Hidden -PassThru -RedirectStandardOutput $logPath -RedirectStandardError $errorPath
Write-Output "Started temporary emulator PID $($process.Id), serial emulator-5580. Logs: $logPath"
