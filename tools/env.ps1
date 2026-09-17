# Dot-source this file:  . .\tools\env.ps1
# Sets JAVA_HOME (Android Studio JBR), ANDROID_HOME (Android Studio SDK) and PATH for this session.
$studio = "C:\Program Files\Android\Android Studio"
$jbr = Join-Path $studio "jbr"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path (Join-Path $jbr "bin\java.exe"))) { Write-Warning "JBR not found at $jbr - install Android Studio or set JAVA_HOME manually." }
if (-not (Test-Path $sdk)) { Write-Warning "Android SDK not found at $sdk" }
$env:JAVA_HOME = $jbr
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = (Join-Path $jbr "bin") + ";" + (Join-Path $sdk "platform-tools") + ";" + $env:PATH
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
