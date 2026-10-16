# The AOSP API 26 r1 image bundles com.android.webview 58, but its framework
# provider list names Google packages. Register the bundled engine on this
# disposable emulator only. No WebView binary or production app is modified.
param([string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk", [string]$Serial = 'emulator-5582')
$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath("$PSScriptRoot/..")
$adbPath = Join-Path $SdkPath 'platform-tools/adb.exe'
$avdName = (& $adbPath -s $Serial emu avd name | Select-Object -First 1).Trim()
if ($avdName -ne 'LumbreApi26' -or $Serial -notmatch '^emulator-\d+$') { throw 'Only the isolated LumbreApi26 emulator is supported.' }
$work = Join-Path $workspace 'test-artifacts/api26-webview-overlay'
New-Item -ItemType Directory -Path (Join-Path $work 'res/xml') -Force | Out-Null
@'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.hector.epubreader.compat.webview">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="26" />
    <application android:hasCode="false" />
    <overlay android:targetPackage="android" android:isStatic="true" android:priority="999" />
</manifest>
'@ | Set-Content -LiteralPath (Join-Path $work 'AndroidManifest.xml') -Encoding utf8
@'
<webviewproviders>
    <webviewprovider availableByDefault="true" description="Bundled AOSP WebView" packageName="com.android.webview" />
</webviewproviders>
'@ | Set-Content -LiteralPath (Join-Path $work 'res/xml/config_webview_packages.xml') -Encoding utf8
$toolsPath = Join-Path $SdkPath 'build-tools/36.0.0'
$androidJar = Get-ChildItem (Join-Path $SdkPath 'platforms') -Filter android.jar -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
$overlay = Join-Path $work 'webview-provider.apk'
& (Join-Path $toolsPath 'aapt.exe') package -f -M (Join-Path $work 'AndroidManifest.xml') -S (Join-Path $work 'res') -I $androidJar -F $overlay
if ($LASTEXITCODE -ne 0) { throw 'Overlay packaging failed.' }
& (Join-Path $toolsPath 'apksigner.bat') sign --ks (Join-Path $env:USERPROFILE '.android/debug.keystore') --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android $overlay
if ($LASTEXITCODE -ne 0) { throw 'Overlay signing failed.' }
& $adbPath -s $Serial root
& $adbPath -s $Serial wait-for-device
$remount = & $adbPath -s $Serial remount
if ($LASTEXITCODE -ne 0 -or ($remount -join "`n") -match 'failed|Read-only') { throw 'Restart the isolated emulator with -writable-system before registering its bundled WebView.' }
& $adbPath -s $Serial shell mkdir -p /system/vendor/overlay
& $adbPath -s $Serial push $overlay /system/vendor/overlay/lumbre-webview-provider.apk
if ($LASTEXITCODE -ne 0) { throw 'Could not register the bundled WebView provider.' }
& $adbPath -s $Serial shell restorecon -RF /vendor/overlay
$context = & $adbPath -s $Serial shell ls -Zd /vendor/overlay
if (($context -join "`n") -notmatch 'vendor_overlay_file') { throw 'Overlay directory security context was not restored.' }
& $adbPath -s $Serial reboot
Write-Output 'Rebooting the isolated emulator with its bundled AOSP WebView provider registered.'
