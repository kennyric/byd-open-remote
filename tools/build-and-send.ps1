param(
    [string]$Apk = "",
    [switch]$NoBuild,
    [string]$LocalSendCli = "$PSScriptRoot\localsend-cli.exe"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not $NoBuild) {
    Push-Location "$root\android-companion"
    & .\gradlew.bat :app:assembleStandardDebug
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { exit $code }
}

if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = "$root\android-companion\app\build\outputs\apk\standard\debug\BYD-Open-Remote.apk"
}
if (-not (Test-Path $Apk)) {
    throw "APK not found: $Apk"
}

if (-not (Test-Path $LocalSendCli)) {
    Write-Host "LocalSend CLI not found; downloading v1.18.2..."
    Invoke-WebRequest `
        -Uri "https://github.com/localsend/localsend/releases/download/v1.18.2/LocalSend-CLI-1.18.2-windows-x86-64.exe" `
        -OutFile $LocalSendCli
}

Write-Host ""
Write-Host "APK: $Apk"
Write-Host "Opening LocalSend device list."
Write-Host "Select your phone with 1-9. The phone must have LocalSend open."
Write-Host ""

& $LocalSendCli -f $Apk
exit $LASTEXITCODE
