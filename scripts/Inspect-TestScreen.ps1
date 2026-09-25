param(
    [int[]]$Tap,
    [int[]]$Swipe,
    [switch]$Back,
    [string]$Text,
    [string]$Screenshot
)
$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath("$PSScriptRoot/..")
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
$serial = 'emulator-5580'
if ($Tap.Count -eq 2) { & $adb -s $serial shell input tap $Tap[0] $Tap[1] }
if ($Swipe.Count -eq 5) { & $adb -s $serial shell input swipe $Swipe[0] $Swipe[1] $Swipe[2] $Swipe[3] $Swipe[4] }
if ($Back) { & $adb -s $serial shell input keyevent KEYCODE_BACK }
if ($Text) { & $adb -s $serial shell input text $Text.Replace(' ', '%s') }
& $adb -s $serial shell uiautomator dump /sdcard/lumbre-window.xml | Out-Null
$xmlPath = Join-Path $workspace 'test-artifacts/window.xml'
& $adb -s $serial pull /sdcard/lumbre-window.xml $xmlPath | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot read emulator screen' }
[xml]$tree = Get-Content -LiteralPath $xmlPath
$tree.SelectNodes('//node') | Where-Object { ($_.text -or $_.'content-desc') -and $_.bounds -ne '[0,0][0,0]' } | ForEach-Object {
    $label = $_.text
    if ($label.Length -gt 180) { $label = $label.Substring(0,180) + '…' }
    '{0} | {1} | {2}' -f $label,$_.'content-desc',$_.bounds
}
if ($Screenshot) {
    if ($Screenshot -notmatch '^[a-z0-9-]+\.png$') { throw 'Use a simple PNG filename' }
    $outputPath = Join-Path $workspace "docs/screenshots/$Screenshot"
    & $adb -s $serial shell screencap -p /sdcard/lumbre-screen.png
    & $adb -s $serial pull /sdcard/lumbre-screen.png $outputPath
}
