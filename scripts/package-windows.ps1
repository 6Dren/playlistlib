# ============================================================
# 1. PROJECT CONFIGURATION
# ============================================================

# Stop the script immediately if a PowerShell command fails.
$ErrorActionPreference = 'Stop'

# Get the project root directory (parent of the scripts folder).
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

# Application information.
$AppName = 'Playlistlib'
$AppVersion = if ($env:APP_VERSION) { $env:APP_VERSION } else { '0.1.0' }
$AppIcon = Join-Path $Root 'src\main\resources\com\playlistlib\icons\logoplib.ico'

# Project and external dependency versions.
$ProjectVersion = '1.0-SNAPSHOT' # Keep in sync with pom.xml
$YtDlpVersion = '2026.10.26'
$FfmpegAsset = 'ffmpeg-n9.0-latest-win64-lgpl-9.0.zip'

# Base URLs used to download yt-dlp and FFmpeg.
$YtBase = "https://github.com/yt-dlp/yt-dlp/releases/download/$YtDlpVersion"
$FfBase = 'https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/latest'

# Build, distribution, and temporary working directories.
$Build = Join-Path $Root 'target'
$Dist = Join-Path $Build 'dist'
$Work = Join-Path $Build 'package-windows'



# ============================================================
# 2. BUILD THE JAVA APPLICATION
# ============================================================

# Compile the project and package its dependencies using Maven.
& mvn clean package
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }

# Create the distribution directory if it does not exist.
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

# Define the module path containing the application JAR and dependencies.
$ModulePath = "$Build\playlistlib-$ProjectVersion.jar;$Build\dependency"

# Create a Windows application image using jpackage.
& jpackage --type app-image --name $AppName --app-version $AppVersion `
    --vendor $AppName --icon $AppIcon --dest $Dist --module-path $ModulePath `
    --module 'com.playlistlib/com.playlistlib.MainApp'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }



# ============================================================
# 3. PREPARE THE TEMPORARY WORKING DIRECTORY
# ============================================================

# Remove any previous packaging files.
Remove-Item $Work -Recurse -Force -ErrorAction SilentlyContinue

# Create a clean working directory for downloaded dependencies.
New-Item -ItemType Directory -Force -Path $Work | Out-Null



# ============================================================
# 4. HELPER FUNCTIONS
# ============================================================

# Download a file from a URL and save it to the specified path.
function Download-File($Url, $Path) {
    Invoke-WebRequest -Uri $Url -OutFile $Path
}

# Verify a downloaded file against its SHA-256 checksum.
# The expected checksum is read from the supplied checksum manifest.
function Assert-Sha256($Manifest, $AssetName, $File) {
    $Expected = $null
    foreach ($Line in Get-Content -LiteralPath $Manifest) {
        $Parts = $Line.Trim() -split '\s+', 2
        if ($Parts.Count -eq 2 -and $Parts[1] -eq $AssetName) {
            $Expected = $Parts[0].ToLowerInvariant()
            break
        }
    }
    
    if (-not $Expected) { throw "No checksum found for $AssetName" }

    # Calculate the downloaded file's checksum and compare both values.
    $Actual = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected) { throw "SHA-256 mismatch: $AssetName" }
}



# ============================================================
# 5. DOWNLOAD AND VERIFY YT-DLP
# ============================================================

# Define the executable and checksum manifest paths.
$YtFile = Join-Path $Work 'yt-dlp.exe'
$YtSums = Join-Path $Work 'yt-dlp-sums'

# Download yt-dlp and its official SHA-256 checksum manifest.
Download-File "$YtBase/yt-dlp.exe" $YtFile
Download-File "$YtBase/SHA2-256SUMS" $YtSums

# Verify the downloaded executable before using it.
Assert-Sha256 $YtSums 'yt-dlp.exe' $YtFile



# ============================================================
# 6. DOWNLOAD AND VERIFY FFMPEG
# ============================================================

# Define the FFmpeg archive and checksum manifest paths.
$FfFile = Join-Path $Work $FfmpegAsset
$FfSums = Join-Path $Work 'ffmpeg-sums'

# Download the FFmpeg archive and its checksum manifest.
Download-File "$FfBase/$FfmpegAsset" $FfFile
Download-File "$FfBase/checksums.sha256" $FfSums

# Verify the downloaded archive before extracting it.
Assert-Sha256 $FfSums $FfmpegAsset $FfFile



# ============================================================
# 7. EXTRACT AND LOCATE FFMPEG FILES
# ============================================================

# Extract the FFmpeg archive into the temporary working directory.
$Extract = Join-Path $Work 'ffmpeg'
Expand-Archive -LiteralPath $FfFile -DestinationPath $Extract -Force

# Locate the required executables and license file inside the archive.
$FfmpegExe = Get-ChildItem -Path $Extract -Recurse -File -Filter 'ffmpeg.exe' | Select-Object -First 1
$FfprobeExe = Get-ChildItem -Path $Extract -Recurse -File -Filter 'ffprobe.exe' | Select-Object -First 1
$FfmpegLicense = Get-ChildItem -Path $Extract -Recurse -File -Filter 'LICENSE.txt' | Select-Object -First 1

# Stop packaging if any required file is missing.
if (-not $FfmpegExe -or -not $FfprobeExe -or -not $FfmpegLicense) {
    throw 'FFmpeg archive is missing ffmpeg.exe, ffprobe.exe, or LICENSE.txt.'
}

# ============================================================
# 8. COPY DEPENDENCIES INTO THE APPLICATION IMAGE
# ============================================================

# Define the application image and its internal directories.
$AppImage = Join-Path $Dist $AppName
$AppDir = Join-Path $AppImage 'app'
$Tools = Join-Path $AppDir 'tools'
$Licenses = Join-Path $AppImage 'licenses'

# Create directories for the external tools and their licenses.
New-Item -ItemType Directory -Force -Path $Tools, $Licenses | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Tools 'ffmpeg'), `
    (Join-Path $Licenses 'yt-dlp'), (Join-Path $Licenses 'ffmpeg') | Out-Null

# Copy yt-dlp and FFmpeg executables into the packaged application.
Copy-Item $YtFile (Join-Path $Tools 'yt-dlp.exe')
Copy-Item $FfmpegExe.FullName (Join-Path $Tools 'ffmpeg\ffmpeg.exe')
Copy-Item $FfprobeExe.FullName (Join-Path $Tools 'ffmpeg\ffprobe.exe')



# ============================================================
# 9. INCLUDE THIRD-PARTY LICENSES AND NOTICES
# ============================================================

# Download yt-dlp's license and third-party license information.
Download-File "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$YtDlpVersion/LICENSE" `
    (Join-Path $Licenses 'yt-dlp\LICENSE')
Download-File "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$YtDlpVersion/THIRD_PARTY_LICENSES.txt" `
    (Join-Path $Licenses 'yt-dlp\THIRD_PARTY_LICENSES.txt')

# Copy FFmpeg's license into the application image.
Copy-Item $FfmpegLicense.FullName (Join-Path $Licenses 'ffmpeg\LICENSE.txt')

# Include information about the FFmpeg build used by the application.
"FFmpeg n9.0 LGPL build. Asset: $FfmpegAsset`nBuild project: https://github.com/FFmpeg-Builds/FFmpeg-Builds" |
    Set-Content -Encoding UTF8 (Join-Path $Licenses 'ffmpeg\BUILD_INFO.txt')

# Copy the project's own license and third-party notices.
Copy-Item (Join-Path $Root 'LICENSE') $AppImage
Copy-Item (Join-Path $Root 'THIRD_PARTY_NOTICES.md') $AppImage

# ============================================================
# 10. CREATE THE FINAL ZIP ARCHIVE
# ============================================================

# Compress the complete application image into a distributable ZIP file.
$Archive = Join-Path $Dist 'Playlistlib-windows-x64.zip'
Compress-Archive -Path $AppImage -DestinationPath $Archive -Force

# Display the location of the generated archive.
Write-Host "Created: $Archive"
