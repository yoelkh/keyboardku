<#
.SYNOPSIS
  One-time toolchain check/setup for building the KeyboardKu Android app from the command line.
  Uses the JDK (JBR) and SDK that ship with Android Studio; downloads Gradle only if the wrapper is missing.
.PARAMETER GradleVersion  Gradle version to bootstrap the wrapper with (only needed when android/gradlew is absent).
#>
param([string]$GradleVersion = "9.7.1")
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = "SilentlyContinue"

$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "env.ps1")

# 1. JDK
& "$env:JAVA_HOME\bin\java.exe" -version
# 2. SDK
$platforms = Get-ChildItem (Join-Path $env:ANDROID_HOME "platforms") -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
Write-Host "Installed platforms: $($platforms -join ', ')"
if (-not (Test-Path (Join-Path $env:ANDROID_HOME "licenses\android-sdk-license"))) {
    Write-Warning "SDK license not accepted. Open Android Studio > SDK Manager once, or run sdkmanager --licenses."
}
# 3. local.properties
$lp = Join-Path $root "android\local.properties"
$sdkFwd = $env:ANDROID_HOME -replace "\\", "/"
"sdk.dir=$sdkFwd" | Out-File -Encoding ascii $lp
Write-Host "Wrote $lp"
# 4. Gradle wrapper
$wrapperJar = Join-Path $root "android\gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $wrapperJar)) {
    $gdir = Join-Path $env:LOCALAPPDATA "Programs\gradle-$GradleVersion"
    if (-not (Test-Path (Join-Path $gdir "bin\gradle.bat"))) {
        $zip = Join-Path $env:TEMP "gradle-$GradleVersion-bin.zip"
        Write-Host "Downloading Gradle $GradleVersion ..."
        Invoke-WebRequest -UseBasicParsing "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $zip
        Expand-Archive $zip (Join-Path $env:LOCALAPPDATA "Programs") -Force
        Remove-Item $zip
    }
    Push-Location (Join-Path $root "android")
    & (Join-Path $gdir "bin\gradle.bat") wrapper --gradle-version $GradleVersion --distribution-type bin --no-daemon -q
    Pop-Location
}
Write-Host "Toolchain OK. Next: .\tools\build-apk.ps1"
