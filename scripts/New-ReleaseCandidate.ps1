param(
    [string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$KeystorePath = '',
    [string]$KeyAlias = '',
    [string]$StorePasswordVariable = 'LUMBRE_STORE_PASSWORD',
    [string]$KeyPasswordVariable = 'LUMBRE_KEY_PASSWORD',
    [string]$PreviousApk = ''
)
$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath("$PSScriptRoot/..")
$sourceApk = Join-Path $workspace 'app/build/outputs/apk/release/app-release-unsigned.apk'
if (!(Test-Path -LiteralPath $sourceApk)) { throw 'Build assembleRelease before packaging.' }
$toolsPath = Join-Path $SdkPath 'build-tools/36.0.0'
$outputDirectory = Join-Path $workspace 'app/build/outputs/release-v1.4'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$signed = [bool]$KeystorePath
$name = if ($signed) { 'lumbre-1.4.apk' } else { 'lumbre-1.4-unsigned.apk' }
$outputApk = Join-Path $outputDirectory $name
$workingApk = Join-Path $outputDirectory ("candidate-" + [guid]::NewGuid().ToString() + '.apk')
$badging = & (Join-Path $toolsPath 'aapt.exe') dump badging $sourceApk
if ($LASTEXITCODE -ne 0 -or !($badging | Select-String -SimpleMatch "package: name='com.hector.epubreader' versionCode='5' versionName='1.4'")) {
    throw 'The release build does not contain the expected 1.4 package metadata.'
}
try {
& (Join-Path $toolsPath 'zipalign.exe') -f -P 16 4 $sourceApk $workingApk
if ($LASTEXITCODE -ne 0) { throw 'ZIP alignment failed.' }
if ($signed) {
    if (!(Test-Path -LiteralPath $KeystorePath) -or !$KeyAlias) { throw 'Pass an existing keystore and alias.' }
    if (![Environment]::GetEnvironmentVariable($StorePasswordVariable) -or ![Environment]::GetEnvironmentVariable($KeyPasswordVariable)) {
        throw 'Set the named password environment variables locally before signing. Do not put passwords in command arguments.'
    }
    if (!$PreviousApk) { $PreviousApk = Join-Path $workspace 'app/build/outputs/release-v1.3/lumbre-1.3.apk' }
    if (!(Test-Path -LiteralPath $PreviousApk)) { throw 'Pass the previous signed APK to verify certificate continuity.' }
    $apksigner = Join-Path $toolsPath 'apksigner.bat'
    & $apksigner sign --ks $KeystorePath --ks-key-alias $KeyAlias --ks-pass "env:$StorePasswordVariable" --key-pass "env:$KeyPasswordVariable" $workingApk
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed.' }
    $signature = & $apksigner verify --verbose --print-certs $workingApk
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
    $previousSignature = & $apksigner verify --print-certs $PreviousApk
    if ($LASTEXITCODE -ne 0) { throw 'Previous APK signature verification failed.' }
    $currentCertificate = @($signature | Select-String '^Signer #1 certificate SHA-256 digest:')
    $previousCertificate = @($previousSignature | Select-String '^Signer #1 certificate SHA-256 digest:')
    if ($currentCertificate.Count -ne 1 -or $previousCertificate.Count -ne 1 -or $currentCertificate[0].Line -ne $previousCertificate[0].Line) {
        throw 'The candidate certificate does not match the previous release. Do not distribute this APK.'
    }
    $signature | Set-Content -LiteralPath (Join-Path $outputDirectory 'signature-verification.txt') -Encoding utf8
}
$alignment = & (Join-Path $toolsPath 'zipalign.exe') -c -P 16 -v 4 $workingApk
if ($LASTEXITCODE -ne 0) { throw 'Final alignment verification failed.' }
$alignment | Set-Content -LiteralPath (Join-Path $outputDirectory "$name.alignment.txt") -Encoding utf8
Move-Item -LiteralPath $workingApk -Destination $outputApk -Force
$checksum = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToLowerInvariant()
"$checksum  $name" | Set-Content -LiteralPath "$outputApk.sha256" -Encoding ascii
$sourceCommit = (& git -C $workspace rev-parse HEAD | Out-String).Trim()
$sourceDirty = ((& git -C $workspace status --porcelain | Out-String).Trim().Length -gt 0)
@{ versionName = '1.4'; versionCode = 5; signed = $signed; apk = $name; sha256 = $checksum; bytes = (Get-Item -LiteralPath $outputApk).Length; published = $false; sourceCommit = $sourceCommit; sourceDirty = $sourceDirty } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputDirectory "$name.json") -Encoding utf8
Write-Output "Candidate prepared: $outputApk (signed: $signed, published: false)"
} finally {
    if (Test-Path -LiteralPath $workingApk) { Remove-Item -LiteralPath $workingApk -Force }
    if (Test-Path -LiteralPath "$workingApk.idsig") { Remove-Item -LiteralPath "$workingApk.idsig" -Force }
}
