param(
    [string]$ToolsDirectory = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.android-tools'),
    [switch]$AcceptAndroidLicenses,
    [switch]$WithEmulator
)

$ErrorActionPreference = 'Stop'
if (-not $AcceptAndroidLicenses) {
    throw 'Read https://developer.android.com/studio/terms, then run this script with -AcceptAndroidLicenses if you agree.'
}
New-Item -ItemType Directory -Path $ToolsDirectory -Force | Out-Null
$ToolsDirectory = (Resolve-Path -LiteralPath $ToolsDirectory).Path
$downloadDirectory = Join-Path $ToolsDirectory 'downloads'
New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null

function Get-VerifiedArchive([string]$Uri, [string]$Name, [string]$Sha256) {
    $archive = Join-Path $downloadDirectory $Name
    if (-not (Test-Path -LiteralPath $archive)) {
        & curl.exe --fail --location --retry 3 --output $archive $Uri
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Uri" }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum verification failed: $archive. Remove that archive and retry."
    }
    return $archive
}

$jdkArchive = Get-VerifiedArchive 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_windows_hotspot_17.0.21_9.zip' 'jdk17.zip' '98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b'
$jdkDirectory = Join-Path $ToolsDirectory 'java\jdk-17.0.21+9'
if (-not (Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin\java.exe'))) {
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath (Join-Path $ToolsDirectory 'java') -Force
}
$env:JAVA_HOME = $jdkDirectory

$cliArchive = Get-VerifiedArchive 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' 'commandline.zip' '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$sdkDirectory = Join-Path $ToolsDirectory 'android-sdk'
$cliDirectory = Join-Path $sdkDirectory 'cmdline-tools'
$sdkManager = Join-Path $cliDirectory 'latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    Expand-Archive -LiteralPath $cliArchive -DestinationPath $cliDirectory -Force
    Rename-Item -LiteralPath (Join-Path $cliDirectory 'cmdline-tools') -NewName 'latest'
}
$env:ANDROID_SDK_ROOT = $sdkDirectory
$env:ANDROID_HOME = $sdkDirectory
$packages = @('platforms;android-36', 'build-tools;36.0.0', 'platform-tools')
if ($WithEmulator) { $packages += @('emulator', 'system-images;android-36;google_apis;x86_64') }
1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkDirectory" @packages
if ($LASTEXITCODE -ne 0) { throw "SDK installation failed with exit code $LASTEXITCODE." }
Write-Output "Android tools ready in $ToolsDirectory. Run tools/build.ps1 to build and test."
