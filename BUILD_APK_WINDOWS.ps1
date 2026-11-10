# Local build helper: Run in PowerShell after installing Android Studio + Android SDK 35.
# It downloads Gradle from the official Gradle distribution service if missing.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$gradleVersion = '8.9'
$cache = Join-Path $env:USERPROFILE '.gradle-local-downloads'
$gradleDir = Join-Path $cache "gradle-$gradleVersion"
$gradleExe = Join-Path $gradleDir 'bin\gradle.bat'

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar'))) {
    Write-Host "Android SDK 35 not found in $env:ANDROID_HOME" -ForegroundColor Yellow
    Write-Host 'Install Android Studio, then SDK Manager > Android SDK Platform 35, and retry.'
    exit 1
}

if (-not (Test-Path $gradleExe)) {
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $zip = Join-Path $cache "gradle-$gradleVersion-bin.zip"
    Write-Host "Downloading Gradle $gradleVersion from official Gradle servers..."
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $cache -Force
    Remove-Item $zip -Force
}

Write-Host 'Building Android debug APK...'
& $gradleExe --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "Build succeeded but APK missing: $apk" }
Write-Host "APK READY: $apk" -ForegroundColor Green
