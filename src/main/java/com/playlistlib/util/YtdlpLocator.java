package com.playlistlib.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.net.URISyntaxException;
import java.net.URL;
import java.io.File;
import java.util.Optional;

public class YtdlpLocator {

    // OS detection
    public static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("linux");
    }

    // OS-specific names

    private static String getExecutableName(String name) {
        if (isWindows()) {
            return name + ".exe";
        }

        return name;
    }

    private static String getPackagingDirectoryName() {
        if (isWindows()) {
            return "windows";
        }

        if (isLinux())
            return "linux";

        throw new UnsupportedOperationException("Unsupported operating system");
    }

    // Application and packaging information

    public static Path getApplicationDirectory() {
        try {
            URL location = YtdlpLocator.class.getProtectionDomain().getCodeSource().getLocation();
            Path locationPath = Path.of(location.toURI());

            if (Files.isDirectory(locationPath))
                return locationPath;
            if (Files.isRegularFile(locationPath) && locationPath.getParent() != null)
                return locationPath.getParent();

            throw new IllegalStateException("Unable to determine application directory");

        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to determine application directory.", e);
        }
    }

    public static Path getToolsDirectory() {
        Path applicationDirectory = getApplicationDirectory();
        Path packagedTools = applicationDirectory.resolve("tools");

        if (Files.isDirectory(packagedTools)) {
            return packagedTools;
        }

        // If not found, check the development project structure. So probably it's
        // running from maven
        Path root = applicationDirectory.getParent().getParent();

        Path tools = root.resolve("packaging")
                .resolve(getPackagingDirectoryName())
                .resolve("tools");

        if (Files.isDirectory(tools))
            return tools;

        // if (isWindows()) {
        // Path tools = root.resolve("packaging").resolve("windows").resolve("tools");
        // if (Files.isDirectory(tools))
        // return tools;
        // } else if (isLinux()) {
        // Path tools = root.resolve("packaging").resolve("linux").resolve("tools");
        // if (Files.isDirectory(tools))
        // return tools;
        // }
        throw new IllegalStateException("Unable to determine tools directory");
    }

    private static boolean isPackaged() {
        Path applicationDirectory = getApplicationDirectory();
        Path tools = applicationDirectory.resolve("tools");

        return Files.isDirectory(tools);
    }

    // Executable discovery

    public static Optional<Path> findExecutableOnPath(String executable) {
        String pathEnv = System.getenv("PATH"); // get a system variable

        if (pathEnv == null || pathEnv.isBlank())
            return Optional.empty();

        for (String directory : pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path candidate = Path.of(directory).resolve(executable);
            System.out.println("Checking: " + candidate);

            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) { // check first if the path is a file
                                                                                   // and if it's alos a executable
                return Optional.of(candidate);
            }

        }

        return Optional.empty();
    }

    public static Path getYtdlpPath() {
        if (isPackaged()) {
            Path ytdlpPath = getToolsDirectory()
                    .resolve(getExecutableName("yt-dlp"));
            // Path ytdlpPath;
            // if (isWindows())
            // ytdlpPath = getToolsDirectory().resolve("yt-dlp.exe");
            // else if (isLinux())
            // ytdlpPath = getToolsDirectory().resolve("yt-dlp");
            // else
            // throw new UnsupportedOperationException("Unsupported operating system");

            if (Files.isExecutable(ytdlpPath))
                return ytdlpPath;
        }

        Optional<Path> optYdlpPath = findExecutableOnPath(getExecutableName("yt-dlp"));

        // Optional<Path> optYdlpPath = Optional.empty();

        // if (isWindows())
        // optYdlpPath = findExecutableOnPath("yt-dlp.exe");
        // else if (isLinux())
        // optYdlpPath = findExecutableOnPath("yt-dlp");

        if (optYdlpPath.isPresent())
            return optYdlpPath.get();

        else
            throw new IllegalStateException("The program wasn't able to find yt-dlp");
    }

    public static Path getFfmpegDirectory() {
        if (isPackaged()) {
            Path ffmpegDir = getToolsDirectory().resolve("ffmpeg");
            Path ffmpegPath = ffmpegDir.resolve(getExecutableName("ffmpeg"));
            if (Files.isExecutable(ffmpegPath))
                return ffmpegDir;

        }

        Optional<Path> optFfmpegPath = findExecutableOnPath(getExecutableName("ffmpeg"));

        if (optFfmpegPath.isPresent()) {
            return optFfmpegPath.get().getParent();
        }

        throw new IllegalStateException("The program wasn't able to find ffmpeg");

    }

}
