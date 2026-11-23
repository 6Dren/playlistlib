#!/usr/bin/env bash

#### Initialize the shell and locate the project
set -euo pipefail # Treat possible errors

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)" # get the root directory
cd "$ROOT"

#### Define configuration and paths
APP_NAME="Playlistlib"
APP_VERSION="${APP_VERSION:-0.1.0}"
PROJECT_VERSION="1.0-SNAPSHOT" # Keep in sync with pom.xml
ICON="$ROOT/src/main/resources/com/playlistlib/icons/logoplib.png"

# The versions that the project is using
YT_DLP_VERSION="2026.10.26" 
FFMPEG_ASSET="ffmpeg-n9.0-latest-linux64-lgpl-9.0.tar.xz"
YT_BASE="https://github.com/yt-dlp/yt-dlp/releases/download/${YT_DLP_VERSION}"
FF_BASE="https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/latest"

BUILD="$ROOT/target"
DIST="$BUILD/dist"
WORK="$BUILD/package-linux"

#### Build the JavaFX application
mvn clean package
mkdir -p "$DIST"
jpackage --type app-image --name "$APP_NAME" --app-version "$APP_VERSION" \
  --vendor "$APP_NAME" --icon "$ICON" --dest "$DIST" \
  --module-path "$BUILD/playlistlib-$PROJECT_VERSION.jar:$BUILD/dependency" \
  --module com.playlistlib/com.playlistlib.MainApp

#### Download and verify yt-dlp
rm -rf "$WORK"
mkdir -p "$WORK/ffmpeg"

curl -fL --retry 3 "$YT_BASE/yt-dlp_linux" -o "$WORK/yt-dlp_linux"
curl -fL "$YT_BASE/SHA2-256SUMS" -o "$WORK/yt-dlp-sums"
YT_HASH="$(awk '$2 == "yt-dlp_linux" {print $1}' "$WORK/yt-dlp-sums")"
printf '%s  %s\n' "$YT_HASH" "$WORK/yt-dlp_linux" | sha256sum --check -

#### Download, verify, and extract FFmpeg
# Download the FFmpeg archive and its checksum manifest.
# Note: the "latest" URL can point to a different build over time.
curl -fL --retry 3 "$FF_BASE/$FFMPEG_ASSET" -o "$WORK/$FFMPEG_ASSET"
curl -fL "$FF_BASE/checksums.sha256" -o "$WORK/ffmpeg-sums"

# Find the expected checksum for the selected archive.
FF_HASH="$(awk -v file="$FFMPEG_ASSET" '$2 == file {print $1}' "$WORK/ffmpeg-sums")"
printf '%s  %s\n' "$FF_HASH" "$WORK/$FFMPEG_ASSET" | sha256sum --check -
tar -xJf "$WORK/$FFMPEG_ASSET" -C "$WORK/ffmpeg"

#### Stage executables and license files
APP_IMAGE="$DIST/$APP_NAME"
APP_DIR="$APP_IMAGE/lib/app"
LICENSES="$APP_IMAGE/licenses"

# Locate the required executables and FFmpeg license inside the extracted archive.
FFMPEG_BIN="$(find "$WORK/ffmpeg" -type f -name ffmpeg -print -quit)"
FFPROBE_BIN="$(find "$WORK/ffmpeg" -type f -name ffprobe -print -quit)"
FFMPEG_LICENSE="$(find "$WORK/ffmpeg" -type f -name LICENSE.txt -print -quit)"

# Abort if a required checksum or file could not be found.
[[ -n "$YT_HASH" && -n "$FF_HASH" && -n "$FFMPEG_BIN" && -n "$FFPROBE_BIN" && -n "$FFMPEG_LICENSE" ]]

# Copy executables into the locations expected by YtdlpLocator.
# -D creates missing parent directories; -m755 sets executable permissions
install -Dm755 "$WORK/yt-dlp_linux" "$APP_DIR/tools/yt-dlp"
install -Dm755 "$FFMPEG_BIN" "$APP_DIR/tools/ffmpeg/ffmpeg"
install -Dm755 "$FFPROBE_BIN" "$APP_DIR/tools/ffmpeg/ffprobe"

# Include the licenses and notices required for third-party distribution.
mkdir -p "$LICENSES/yt-dlp" "$LICENSES/ffmpeg"
curl -fsSL "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$YT_DLP_VERSION/LICENSE" \
  -o "$LICENSES/yt-dlp/LICENSE"
curl -fsSL "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$YT_DLP_VERSION/THIRD_PARTY_LICENSES.txt" \
  -o "$LICENSES/yt-dlp/THIRD_PARTY_LICENSES.txt"
install -m644 "$FFMPEG_LICENSE" "$LICENSES/ffmpeg/LICENSE.txt"

# Record which FFmpeg build was included in this application.
printf 'FFmpeg n9.0 LGPL build. Asset: %s\nBuild project: https://github.com/FFmpeg-Builds/FFmpeg-Builds\n' \
  "$FFMPEG_ASSET" > "$LICENSES/ffmpeg/BUILD_INFO.txt"

# Copy Playlistlib's own license and third-party notices.
cp "$ROOT/LICENSE" "$APP_IMAGE/LICENSE"
cp "$ROOT/THIRD_PARTY_NOTICES.md" "$APP_IMAGE/THIRD_PARTY_NOTICES.md"

#### Create the final archive
# Archive the application directory, preserving its top-level folder structure.
tar -czf "$DIST/Playlistlib-linux-x86_64.tar.gz" -C "$DIST" "$APP_NAME"
echo "Created: $DIST/Playlistlib-linux-x86_64.tar.gz"


#### References
# Bash: https://www.gnu.org/software/bash/manual/
# GNU Coreutils (install, sha256sum): https://www.gnu.org/software/coreutils/manual/
# GNU Findutils: https://www.gnu.org/software/findutils/manual/
# GNU Tar: https://www.gnu.org/software/tar/manual/
# curl: https://curl.se/docs/manpage.html
# Java jpackage: https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html
# yt-dlp releases and checksums: https://github.com/yt-dlp/yt-dlp
# FFmpeg builds and checksums: https://github.com/FFmpeg-Builds/FFmpeg-Builds
# Example CI workflows: https://github.com/yt-dlp/yt-dlp/tree/master/.github/workflows
# Example CI workflows: https://github.com/FFmpeg-Builds/FFmpeg-Builds/tree/master/.github/workflows
