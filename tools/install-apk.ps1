param([string]$Apk = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "env.ps1")
if ($Apk -eq "") { $Apk = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk" }
adb devices
adb install -r -t $Apk
if ($LASTEXITCODE -ne 0) {
    Write-Warning "adb install failed (MIUI: enable 'Install via USB' in Developer options). Pushing APK to /sdcard/Download instead."
    adb push $Apk /sdcard/Download/keyboardku.apk
    Write-Host "Open the Files app on the phone and install Download/keyboardku.apk"
} else {
    adb shell am start -n id.keyboardku/.ui.MainActivity | Out-Null
}
