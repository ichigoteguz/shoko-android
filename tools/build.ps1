param(
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$portableTools = Join-Path (Split-Path -Parent $projectPath) '.android-tools'

if (-not $env:JAVA_HOME) {
    $portableJdk = Get-ChildItem -LiteralPath (Join-Path $portableTools 'java') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
        Select-Object -First 1
    if (-not $portableJdk) { throw 'Install a JDK 17 and set JAVA_HOME, or run tools/setup-android.ps1.' }
    $env:JAVA_HOME = $portableJdk.FullName
}

if (-not $env:ANDROID_SDK_ROOT) {
    if ($env:ANDROID_HOME) {
        $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    } elseif (Test-Path -LiteralPath (Join-Path $portableTools 'android-sdk')) {
        $env:ANDROID_SDK_ROOT = Join-Path $portableTools 'android-sdk'
    } else {
        throw 'Set ANDROID_SDK_ROOT to your Android SDK, or run tools/setup-android.ps1.'
    }
}
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
if (-not $env:GRADLE_USER_HOME -and (Test-Path -LiteralPath $portableTools)) {
    $env:GRADLE_USER_HOME = Join-Path $portableTools 'gradle-cache'
}

Push-Location $projectPath
try {
    & (Join-Path $projectPath 'gradlew.bat') @Tasks --no-daemon --stacktrace
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}
