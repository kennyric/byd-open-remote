param(
    [string]$CompanionUrl = '',
    [string]$Password = $env:WATCH_SIGN_PWD,
    [string]$Alias = 'bydwatch',
    [string]$Keystore = '',
    [string]$Cert = '',
    [string]$Profile = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw "Signing password missing. Pass -Password or set `$env:WATCH_SIGN_PWD."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$devEcoRoot = 'D:\Program Files\DevEco Studio'
$java = Join-Path $devEcoRoot 'jbr\bin\java.exe'
$signTool = Join-Path $devEcoRoot 'sdk\default\openharmony\toolchains\lib\hap-sign-tool.jar'
$packTool = Join-Path $devEcoRoot 'sdk\default\openharmony\toolchains\lib\app_packing_tool.jar'
$hapToBinTool = Join-Path $devEcoRoot 'sdk\default\openharmony\js\build-tools\binary-tools\haptobin_tool.jar'

if ([string]::IsNullOrWhiteSpace($Keystore)) { $Keystore = Join-Path $repoRoot 'signing\bydwatch_watch.p12' }
if ([string]::IsNullOrWhiteSpace($Cert)) { $Cert = Join-Path $repoRoot 'signing\BYD Companion Watch.cer' }
if ([string]::IsNullOrWhiteSpace($Profile)) { $Profile = Join-Path $repoRoot 'signing\BYD Watch ProfileDebug.p7b' }

if (-not [string]::IsNullOrWhiteSpace($CompanionUrl)) {
    Write-Host "Regenerating QR PNG for $CompanionUrl..."
    python (Join-Path $PSScriptRoot 'scripts\generate_qr.py') $CompanionUrl
    if ($LASTEXITCODE -ne 0) { throw "generate_qr.py failed ($LASTEXITCODE)" }
}

$env:NODE_HOME = Join-Path $devEcoRoot 'tools\node'
$env:DEVECO_SDK_HOME = Join-Path $devEcoRoot 'sdk'
Push-Location $PSScriptRoot
try {
    & (Join-Path $devEcoRoot 'tools\hvigor\bin\hvigorw.bat') assembleHap -p buildMode=release --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "hvigorw assembleHap failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$outDir = Join-Path $PSScriptRoot 'entry\build\default\outputs\default'
$binDir = Join-Path $outDir 'bin'
$unsignedBin = Join-Path $binDir 'entry-default-unsigned.bin'
$signedBin = Join-Path $binDir 'entry-default-signed.bin'
$tempHap = Join-Path $outDir 'entry-default-for-sign.hap'
$signedHap = Join-Path $outDir 'ev-watch-link-store-signed.hap'
$liteSourceDir = Join-Path $PSScriptRoot 'entry\build\default\intermediates\lite_source\default'

New-Item -ItemType Directory -Force -Path $binDir | Out-Null
Remove-Item $unsignedBin -Force -ErrorAction SilentlyContinue
& $java "-Dfile.encoding=GBK" -jar $hapToBinTool --project-path $liteSourceDir --bin-path $unsignedBin
if ($LASTEXITCODE -ne 0) { throw "haptobin_tool failed ($LASTEXITCODE)" }

& $java -jar $signTool sign-app `
    -mode localSign -keystoreFile $Keystore -keystorePwd $Password `
    -keyAlias $Alias -keyPwd $Password -signAlg SHA256withECDSA `
    -profileFile $Profile -appCertFile $Cert -profileSigned 1 `
    -inForm bin -inFile $unsignedBin -outFile $signedBin
if ($LASTEXITCODE -ne 0) { throw "Sign .bin failed ($LASTEXITCODE)" }

& $java -jar $packTool --mode hap --bin-path $signedBin --out-path $tempHap --force true
if ($LASTEXITCODE -ne 0) { throw "Pack .hap failed ($LASTEXITCODE)" }

& $java -jar $signTool sign-app `
    -mode localSign -keystoreFile $Keystore -keystorePwd $Password `
    -keyAlias $Alias -keyPwd $Password -signAlg SHA256withECDSA `
    -profileFile $Profile -appCertFile $Cert `
    -inFile $tempHap -outFile $signedHap
if ($LASTEXITCODE -ne 0) { throw "Sign .hap failed ($LASTEXITCODE)" }

Remove-Item $tempHap -Force -ErrorAction SilentlyContinue

$verifyCert = Join-Path $env:TEMP 'evlink_verify_chain.cer'
$verifyProfile = Join-Path $env:TEMP 'evlink_verify_profile.p7b'
try {
    & $java -jar $signTool verify-app -inFile $signedHap -outCertChain $verifyCert -outProfile $verifyProfile
    if ($LASTEXITCODE -ne 0) { throw "Verify .hap failed ($LASTEXITCODE)" }
} finally {
    Remove-Item $verifyCert, $verifyProfile -Force -ErrorAction SilentlyContinue
}

Write-Host "Signed Store HAP ready: $signedHap"
Write-Host "Note: AppGallery Connect Sport Watch (liteWearable) uploads the signed .hap directly."
