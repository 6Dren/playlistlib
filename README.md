# playlistlib — Project Plan

## 1. Overview

playlistlib is a Java desktop application for downloading and organizing audio and video using yt-dlp.

The project will start as a simple downloader and gradually introduce playlist management, media collections, and a local database.

## 2. Main Features

- Download audio  and video using yt-dlp
- Display download progress and errors.
- Organize media into folders and collections.
- Save download history and preferences.

- Manage data using SQLite.

## 3. Technologies

- Java 21 — Application logic.
- JavaFX — GUI.
- FXML — Interface layouts.
- CSS — Styling.
- Maven — Dependencies and builds.
- yt-dlp — Media downloads.
- FFmpeg — Audio and video processing.
- SQLite — Local database.

## 4. Development Phases

### Phase 1 — Basic Downloader

- Create the JavaFX interface.
- Accept URLs and select formats.
- Choose the download directory.
- Run yt-dlp through a thread.
- Display progress, status, and errors.
- Keep the interface responsive.

### Phase 2 — Playlists and Collections

- Download complete playlists.
- Display playlist information.
- Create and manage collections.
- Organize downloaded files.
- Save playlist and collection data.
- Avoid duplicate downloads.

### Phase 3 — Database and Users

- Integrate SQLite.
- Add user registration and login.
- Store download history and preferences.
- Save user-specific collections.
- Implement basic database operations (CRUD).
