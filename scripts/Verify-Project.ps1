param(
    [string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Serial = '',
    [switch]$SkipDevice
)
$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath("$PSScriptRoot/..")
$logDirectory = Join-Path $workspace 'test-artifacts'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
Push-Location $workspace
try {
    & .\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest --console=plain 2>&1 |
        Tee-Object -FilePath (Join-Path $logDirectory 'verification-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Build, JVM or Lint verification failed.' }
    if ($SkipDevice) { return }
    # Release build memory before instrumentation on smaller developer machines.
    & .\gradlew.bat --stop
    $adbPath = Join-Path $SdkPath 'platform-tools/adb.exe'
    if (!$Serial) {
        $devices = @(& $adbPath devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
        if ($devices.Count -ne 1) { throw 'Pass -Serial when there is not exactly one connected device.' }
        $Serial = $devices[0]
    }
    $accessibilityBefore = (& $adbPath -s $Serial shell settings get secure accessibility_enabled | Out-String).Trim()
    $servicesBefore = (& $adbPath -s $Serial shell settings get secure enabled_accessibility_services | Out-String).Trim()
    & $adbPath -s $Serial shell getprop ro.build.version.sdk
    & $adbPath -s $Serial shell dumpsys webviewupdate
    & $adbPath -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Debug installation failed.' }
    & $adbPath -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
    $result = & $adbPath -s $Serial shell am instrument -w -r com.hector.epubreader.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath (Join-Path $logDirectory 'verification-device.log')
    $result | Set-Content -LiteralPath (Join-Path $logDirectory 'verification-device.log') -Encoding utf8
    $result | Write-Output
    $joined = $result -join "`n"
    if ($joined -notmatch 'OK \((\d+) tests?\)' -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw 'Instrumentation failed; inspect test-artifacts/verification-device.log.'
    }
    $accessibilityAfter = (& $adbPath -s $Serial shell settings get secure accessibility_enabled | Out-String).Trim()
    $servicesAfter = (& $adbPath -s $Serial shell settings get secure enabled_accessibility_services | Out-String).Trim()
    if ($accessibilityBefore -ne $accessibilityAfter -or $servicesBefore -ne $servicesAfter) { throw 'Accessibility state changed during verification.' }
} finally { Pop-Location }
