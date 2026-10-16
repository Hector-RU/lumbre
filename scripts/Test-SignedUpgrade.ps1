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
$testApk = Join-Path $workspace 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$probeApk = Join-Path $output 'upgrade-probe.apk'
$apksigner = Join-Path $SdkPath 'build-tools/36.0.0/apksigner.bat'
& $apksigner sign --ks $KeystorePath --ks-key-alias $KeyAlias --ks-pass env:LUMBRE_STORE_PASSWORD --key-pass env:LUMBRE_KEY_PASSWORD --out $probeApk $testApk
if ($LASTEXITCODE -ne 0) { throw 'Could not sign the instrumentation probe.' }
& $adbPath -s $Serial install -r $oldApk
if ($LASTEXITCODE -ne 0) { throw 'Could not install signed 1.2 on the isolated emulator.' }
& $adbPath -s $Serial install -r $probeApk
if ($LASTEXITCODE -ne 0) { throw 'Could not install the signed instrumentation probe.' }
function Invoke-UpgradeProbe([string]$Phase) {
    & $adbPath -s $Serial shell am start -W -n com.hector.epubreader/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    $result = & $adbPath -s $Serial shell am instrument -w -r -e phase $Phase com.hector.epubreader.test/com.hector.epubreader.ReleaseUpgradeProbe
    $result | Set-Content -LiteralPath (Join-Path $workspace "test-artifacts/signed-upgrade-$Phase.log") -Encoding utf8
    $result | Write-Output
    if (($result -join "`n") -notmatch 'passed=true') { throw "Signed upgrade $Phase failed." }
}
Invoke-UpgradeProbe 'prepare'
& $adbPath -s $Serial install -r $newApk
if ($LASTEXITCODE -ne 0) { throw 'Signed update installation failed.' }
Invoke-UpgradeProbe 'verify'
Write-Output 'Signed 1.2 to 1.3 upgrade preserved the book, progress, text locator and bookmark.'
