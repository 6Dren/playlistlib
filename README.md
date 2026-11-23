# Playlistlib

Playlistlib is a Java desktop application that provides a graphical interface for [yt-dlp](https://github.com/yt-dlp/yt-dlp). It works as a download manager and a local media library, allowing users to download, organize, and manage audio and video files without using terminal commands for everyday operations.

The application uses SQLite to store library information and JSON manifest files to make lists easier to import and transfer between directories.

## Features

- Download audio and video using yt-dlp.
- Choose the output format and download options.
- Organize media into lists and directories.
- Import existing lists and add local media files.
- Search and manage items in the local library.
- Store library information using SQLite.

## Technologies

- **Java 21** — Application logic.
- **JavaFX** — Graphical interface.
- **FXML / XML** — Interface layouts.
- **CSS** — Interface styling.
- **Maven** — Dependency management and builds.
- **SQLite** — Local database.
- **yt-dlp** — Media downloads.
- **FFmpeg** — Audio and video processing.

## Requirements

To run the project from source, you need:

- Git
- JDK 21
- Maven
- yt-dlp and FFmpeg

Make sure `yt-dlp` and FFmpeg are installed and available through your system's `PATH`.

## Getting Started

Clone the repository:

```bash
git clone https://github.com/6Dren/playlistlib.git
cd playlistlib
```

Run the application directly using Maven:

```bash
mvn clean javafx:run
```

This runs the application from the source code without packaging a standalone executable.

## Packaging

The project includes scripts to package the application for different operating systems.

**Linux**

```bash
chmod +x scripts/package-linux.sh
./scripts/package-linux.sh
```

**Windows (PowerShell)**

```powershell
.\scripts\package-windows.ps1
```

These scripts handle the packaging process for their respective platforms.

## Prebuilt Versions

You can also download the latest packaged version from the [GitHub Releases](https://github.com/6Dren/playlistlib/releases) page.

1. Download the archive for your operating system.
2. Extract its contents.
3. Launch the application:
   - **Windows:** Run `Playlistlib.exe`.
   - **Linux:** Run `bin/Playlistlib`.

