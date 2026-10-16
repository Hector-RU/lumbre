param(
    [string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Serial = 'emulator-5582',
    [Parameter(Mandatory = $true)][string]$KeystorePath,
    [string]$KeyAlias = 'lumbre'
)
$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath("$PSScriptRoot/..")
$adbPath = Join-Path $SdkPath 'platform-tools/adb.exe'
$avdName = (& $adbPath -s $Serial emu avd name | Select-Object -First 1).Trim()
if ($Serial -notmatch '^emulator-\d+$' -or $avdName -ne 'LumbreApi26') { throw 'This probe is restricted to the isolated LumbreApi26 emulator.' }
if (!$env:LUMBRE_STORE_PASSWORD -or !$env:LUMBRE_KEY_PASSWORD) { throw 'Set signing password environment variables locally first.' }
$output = Join-Path $workspace 'app/build/outputs/release-v1.3'
$oldApk = Join-Path $workspace 'app/build/outputs/release-v1.2/lumbre-1.2.apk'
$newApk = Join-Path $output 'lumbre-1.3.apk'
$probeApk = Join-Path $output 'upgrade-probe.apk'
$toolsPath = Join-Path $SdkPath 'build-tools/36.0.0'
$apksigner = Join-Path $toolsPath 'apksigner.bat'
$work = Join-Path $workspace 'test-artifacts/upgrade-probe'
New-Item -ItemType Directory -Path (Join-Path $work 'classes') -Force | Out-Null
$androidJar = Get-ChildItem (Join-Path $SdkPath 'platforms') -Filter android.jar -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
# Build a Java-only probe independently: AGP generates the regular test runner,
# and the published 1.2 APK must not load test code compiled against 1.3 models.
@'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.hector.epubreader.upgradeprobe">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="26" />
    <application android:label="Lumbre upgrade probe" />
    <instrumentation android:name="com.hector.epubreader.ReleaseUpgradeProbe" android:targetPackage="com.hector.epubreader" />
</manifest>
'@ | Set-Content -LiteralPath (Join-Path $work 'AndroidManifest.xml') -Encoding utf8
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') -source 11 -target 11 -cp $androidJar -d (Join-Path $work 'classes') (Join-Path $workspace 'app/src/androidTest/java/com/hector/epubreader/ReleaseUpgradeProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Upgrade probe compilation failed.' }
$classFile = Join-Path $work 'classes/com/hector/epubreader/ReleaseUpgradeProbe.class'
& (Join-Path $toolsPath 'd8.bat') --lib $androidJar --min-api 26 --output $work $classFile
if ($LASTEXITCODE -ne 0) { throw 'Upgrade probe DEX compilation failed.' }
& (Join-Path $toolsPath 'aapt.exe') package -f -M (Join-Path $work 'AndroidManifest.xml') -A (Join-Path $workspace 'app/src/androidTest/assets') -I $androidJar -F $probeApk
if ($LASTEXITCODE -ne 0) { throw 'Upgrade probe packaging failed.' }
Push-Location $work
try { & (Join-Path $toolsPath 'aapt.exe') add $probeApk classes.dex } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Upgrade probe DEX packaging failed.' }
& $apksigner sign --ks $KeystorePath --ks-key-alias $KeyAlias --ks-pass env:LUMBRE_STORE_PASSWORD --key-pass env:LUMBRE_KEY_PASSWORD $probeApk
if ($LASTEXITCODE -ne 0) { throw 'Could not sign the instrumentation probe.' }
& $adbPath -s $Serial install -r $oldApk
if ($LASTEXITCODE -ne 0) { throw 'Could not install signed 1.2 on the isolated emulator.' }
& $adbPath -s $Serial install -r $probeApk
if ($LASTEXITCODE -ne 0) { throw 'Could not install the signed instrumentation probe.' }
function Invoke-UpgradeProbe([string]$Phase) {
    & $adbPath -s $Serial shell am start -W -n com.hector.epubreader/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    $result = & $adbPath -s $Serial shell am instrument -w -r -e phase $Phase com.hector.epubreader.upgradeprobe/com.hector.epubreader.ReleaseUpgradeProbe
    $result | Set-Content -LiteralPath (Join-Path $workspace "test-artifacts/signed-upgrade-$Phase.log") -Encoding utf8
    $result | Write-Output
    if (($result -join "`n") -notmatch 'passed=true') { throw "Signed upgrade $Phase failed." }
}
Invoke-UpgradeProbe 'prepare'
& $adbPath -s $Serial install -r $newApk
if ($LASTEXITCODE -ne 0) { throw 'Signed update installation failed.' }
Invoke-UpgradeProbe 'verify'
Write-Output 'Signed 1.2 to 1.3 upgrade preserved the book, progress, text locator and bookmark.'
