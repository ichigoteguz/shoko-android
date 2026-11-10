param([switch]$SkipChecks, [switch]$PrepareSigningOnly)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$signingPath = Join-Path $projectPath '.signing'
$propertiesPath = Join-Path $projectPath 'release-signing.properties'

if (-not (Test-Path -LiteralPath $propertiesPath)) {
    $jdkPath = $env:JAVA_HOME
    if (-not $jdkPath) {
        $portableJava = Join-Path (Split-Path -Parent $projectPath) '.android-tools\java'
        $jdkPath = (Get-ChildItem -LiteralPath $portableJava -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\keytool.exe') } |
            Select-Object -First 1).FullName
    }
    if (-not $jdkPath) { throw 'Run tools/setup-android.ps1 or set JAVA_HOME first.' }
    New-Item -ItemType Directory -Path $signingPath -Force | Out-Null
    $keyStorePath = Join-Path $signingPath 'shoko-remote.jks'
    if (Test-Path -LiteralPath $keyStorePath) { throw 'A signing key already exists. Restore its release-signing.properties before continuing.' }
    $password = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $passwordPath = Join-Path $signingPath 'password.txt'
    [System.IO.File]::WriteAllText($passwordPath, $password)
    & (Join-Path $jdkPath 'bin\keytool.exe') -genkeypair -keystore $keyStorePath -storetype JKS -alias shoko-remote `
        -storepass:file $passwordPath -keypass:file $passwordPath -keyalg RSA -keysize 3072 -validity 10000 `
        -dname 'CN=Shoko Remote Personal, OU=Personal Android App' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the local signing key.' }
    $content = "storeFile=$($keyStorePath.Replace('\', '/'))`nstorePassword=$password`nkeyAlias=shoko-remote`nkeyPassword=$password`n"
    [System.IO.File]::WriteAllText($propertiesPath, $content)
    Write-Output 'Created a personal app signing key. Keep .signing and release-signing.properties for future updates; neither is included in the source archive.'
}

if ($PrepareSigningOnly) { return }

$tasks = if ($SkipChecks) { @('assembleRelease') } else { @('assembleRelease', 'testDebugUnitTest', 'lintDebug') }
& (Join-Path $PSScriptRoot 'build.ps1') -Tasks $tasks
if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
