package com.playlistlib.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class YtdlpLocator {

    // Check if the OS is windows
    public static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    public static Path getYtDlpPath() {

        if (isWindows()) {
            Path path = getToolsDirectory().resolve("yt-dlp.exe");

            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("yt-dlp.exe not found: " + path);
            }

            return path;
        }

        // Linux: use the executable available in PATH
        return Path.of("yt-dlp");
    }

    public static Path getFfmpegPath() {

        if (isWindows()) {
            Path path = getToolsDirectory()
                    .resolve("ffmpeg")
                    .resolve("ffmpeg.exe");

            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("ffmpeg.exe not found: " + path);
            }

            return path;
        }

        // Linux: use the executable available in PATH
        return Path.of("ffmpeg");
    }

    private static Path getToolsDirectory() {
        return Path.of("packaging", "windows", "tools")
                .toAbsolutePath()
                .normalize();
    }

}
