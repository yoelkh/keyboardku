param([switch]$Release, [switch]$Test)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "env.ps1")
Push-Location (Join-Path $root "android")
try {
    $task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
    if ($Test) { & .\gradlew.bat test --console=plain; if ($LASTEXITCODE -ne 0) { throw "unit tests failed" } }
    & .\gradlew.bat $task --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradle failed" }
    $apk = if ($Release) { "app\build\outputs\apk\release\app-release.apk" } else { "app\build\outputs\apk\debug\app-debug.apk" }
    Write-Host "APK: $(Join-Path (Get-Location) $apk)"
    if ($Release) {
        $dist = Join-Path $root "dist"
        New-Item -ItemType Directory -Force $dist | Out-Null
        $out = Join-Path $dist "KeyboardKu-0.1.0.apk"
        Copy-Item $apk $out -Force
        Write-Host "Installer siap dikirim ke HP: $out"
    }
} finally { Pop-Location }
