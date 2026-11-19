param(
    [string]$Password = $env:WATCH_SIGN_PWD,
    [string]$Alias = 'bydwatch',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw "Signing password required: pass -Password or set WATCH_SIGN_PWD environment variable."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$devEcoRoot = 'D:\Program Files\DevEco Studio'
$java = Join-Path $devEcoRoot 'jbr\bin\java.exe'
$signTool = Join-Path $devEcoRoot 'sdk\default\openharmony\toolchains\lib\hap-sign-tool.jar'
$packTool = Join-Path $devEcoRoot 'sdk\default\openharmony\toolchains\lib\app_packing_tool.jar'
$hapToBinTool = Join-Path $devEcoRoot 'sdk\default\openharmony\js\build-tools\binary-tools\haptobin_tool.jar'

$keystore = Join-Path $repoRoot 'signing\bydwatch_watch.p12'
$cert = Join-Path $repoRoot 'signing\BYD Companion Watch.cer'
$profile = Join-Path $repoRoot 'signing\BYD Watch ProfileDebug.p7b'

if (-not $SkipBuild) {
    $env:NODE_HOME = Join-Path $devEcoRoot 'tools\node'
    $env:DEVECO_SDK_HOME = Join-Path $devEcoRoot 'sdk'
    Push-Location $PSScriptRoot
    try {
        & (Join-Path $devEcoRoot 'tools\hvigor\bin\hvigorw.bat') assembleHap -p buildMode=release --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "hvigorw assembleHap failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

$outDir = Join-Path $PSScriptRoot 'entry\build\default\outputs\default'
$binDir = Join-Path $outDir 'bin'
$unsignedBin = Join-Path $binDir 'entry-default-unsigned.bin'
$signedBin = Join-Path $binDir 'entry-default-signed.bin'
$tempHap = Join-Path $outDir 'entry-default-for-sign.hap'
$signedHap = Join-Path $outDir 'entry-default-signed.hap'
$liteSourceDir = Join-Path $PSScriptRoot 'entry\build\default\intermediates\lite_source\default'

# Hvigor skips haptobin in release mode (needPackBin requires debug); pack lite_source so release JS/bytecode still builds a signed Lite .bin HAP.
if ((-not $SkipBuild) -or (-not (Test-Path $unsignedBin))) {
    New-Item -ItemType Directory -Force -Path $binDir | Out-Null
    Remove-Item $unsignedBin -Force -ErrorAction SilentlyContinue
    & $java "-Dfile.encoding=GBK" -jar $hapToBinTool --project-path $liteSourceDir --bin-path $unsignedBin
    if ($LASTEXITCODE -ne 0) { throw "haptobin_tool failed ($LASTEXITCODE)" }
}

& $java -jar $signTool sign-app `
    -mode localSign `
    -keystoreFile $keystore `
    -keystorePwd $Password `
    -keyAlias $Alias `
    -keyPwd $Password `
    -signAlg SHA256withECDSA `
    -profileFile $profile `
    -appCertFile $cert `
    -profileSigned 1 `
    -inForm bin `
    -inFile $unsignedBin `
    -outFile $signedBin
if ($LASTEXITCODE -ne 0) { throw "Sign .bin failed ($LASTEXITCODE)" }

& $java -jar $packTool --mode hap --bin-path $signedBin --out-path $tempHap --force true
if ($LASTEXITCODE -ne 0) { throw "Pack .hap failed ($LASTEXITCODE)" }

& $java -jar $signTool sign-app `
    -mode localSign `
    -keystoreFile $keystore `
    -keystorePwd $Password `
    -keyAlias $Alias `
    -keyPwd $Password `
    -signAlg SHA256withECDSA `
    -profileFile $profile `
    -appCertFile $cert `
    -inFile $tempHap `
    -outFile $signedHap
if ($LASTEXITCODE -ne 0) { throw "Sign .hap failed ($LASTEXITCODE)" }

Remove-Item $tempHap -Force -ErrorAction SilentlyContinue

$verifyCert = Join-Path $env:TEMP 'bydwatch_verify_chain.cer'
$verifyProfile = Join-Path $env:TEMP 'bydwatch_verify_profile.p7b'
try {
    & $java -jar $signTool verify-app -inFile $signedHap -outCertChain $verifyCert -outProfile $verifyProfile
    if ($LASTEXITCODE -ne 0) { throw "Verify .hap failed ($LASTEXITCODE)" }
} finally {
    Remove-Item $verifyCert, $verifyProfile -Force -ErrorAction SilentlyContinue
}

Write-Host "Signed HAP ready: $signedHap"
