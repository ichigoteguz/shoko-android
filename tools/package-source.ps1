param([string]$OutputPath)

$ErrorActionPreference = 'Stop'
$projectPath = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if (-not $OutputPath) { $OutputPath = Join-Path $projectPath 'dist\shoko-remote-source.zip' }
$archivePath = [System.IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Path (Split-Path -Parent $archivePath) -Force | Out-Null
if (Test-Path -LiteralPath $archivePath) { Remove-Item -LiteralPath $archivePath }
$skipDirectories = @('.git', '.gradle', '.kotlin', '.idea', '.signing', 'build', 'node_modules', 'dist', 'captures', 'artifacts')
$skipFiles = @('local.properties', 'release-signing.properties')

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [System.IO.File]::Open($archivePath, [System.IO.FileMode]::CreateNew)
$archive = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create)
function Add-SourceDirectory([string]$Directory) {
    foreach ($entry in Get-ChildItem -LiteralPath $Directory -Force) {
        if ($entry.PSIsContainer) {
            if ($entry.Name -notin $skipDirectories) { Add-SourceDirectory $entry.FullName }
        } elseif ($entry.Name -notin $skipFiles -and $entry.Extension -notin @('.jks', '.keystore')) {
            $relative = [System.IO.Path]::GetRelativePath($projectPath, $entry.FullName).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $entry.FullName, $relative, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }
}
try { Add-SourceDirectory $projectPath } finally { $archive.Dispose(); $stream.Dispose() }
Write-Output $archivePath
