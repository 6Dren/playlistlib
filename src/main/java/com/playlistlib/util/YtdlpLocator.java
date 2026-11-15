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
        // Locate the application directory in a jpackage image
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path packagedAppDirectory = javaHome.getParent().resolve("app");

        if (Files.isDirectory(packagedAppDirectory)) {
            return packagedAppDirectory;
        }

        // Development: locate the project directory through maven
        try {
            var codeSource = YtdlpLocator.class
                    .getProtectionDomain()
                    .getCodeSource();

            if (codeSource == null) {
                throw new IllegalStateException("Unable to determine application directory");
            }

            Path locationPath = Path.of(codeSource.getLocation().toURI());

            if (Files.isDirectory(locationPath)) {
                return locationPath;
            }

            if (Files.isRegularFile(locationPath) && locationPath.getParent() != null) {
                return locationPath.getParent();
            }

            throw new IllegalStateException("Unable to determine application directory");

        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to determine application directory", e);
        }

        // try {
        // URL location =
        // YtdlpLocator.class.getProtectionDomain().getCodeSource().getLocation();
        // Path locationPath = Path.of(location.toURI());

        // System.out.println(
        // YtdlpLocator.class.getProtectionDomain().getCodeSource());

        // if (Files.isDirectory(locationPath))
        // return locationPath;
        // if (Files.isRegularFile(locationPath) && locationPath.getParent() != null)
        // return locationPath.getParent();

        // throw new IllegalStateException("Unable to determine application directory");

        // } catch (URISyntaxException e) {
        // throw new IllegalStateException("Unable to determine application directory.",
        // e);
        // }
    }

    public static Path getToolsDirectory() {
        Path applicationDirectory = getApplicationDirectory();
        Path packagedTools = applicationDirectory.resolve("tools");

        if (isPackaged())
            return packagedTools;

        // If not found, check the development project structure. So probably it's
        // running from maven
        Path root = applicationDirectory.getParent().getParent();

        Path tools = root.resolve("packaging")
                .resolve(getPackagingDirectoryName())
                .resolve("tools");

        if (Files.isDirectory(tools))
            return tools;

        throw new IllegalStateException("Unable to determine tools directory");
    }

    private static boolean isPackaged() {
        Path applicationDirectory = getApplicationDirectory();

        return applicationDirectory.getFileName().toString().equals("app");

    }

    // Executable discovery

    public static Optional<Path> findExecutableOnPath(String executable) {
        String pathEnv = System.getenv("PATH"); // get a system variable

        if (pathEnv == null || pathEnv.isBlank())
            return Optional.empty();

        for (String directory : pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path candidate = Path.of(directory).resolve(executable);

            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) { // check first if the path is a file
                                                                                   // and if it's alos a executable
                return Optional.of(candidate);
            }

        }

        return Optional.empty();
    }

    public static Path getYtdlpPath() {
        String executable = getExecutableName("yt-dlp");

        Optional<Path> pathExecutable = findExecutableOnPath(executable);

        // Development: system PATH has priority
        if (!isPackaged() && pathExecutable.isPresent()) {
            return pathExecutable.get();
        }

        // Check bundled executable
        Path bundledExecutable = getToolsDirectory().resolve(executable);

        if (Files.isRegularFile(bundledExecutable)
                && Files.isExecutable(bundledExecutable)) {
            return bundledExecutable;
        }

        // Packaged: system PATH is the fallback
        if (pathExecutable.isPresent()) {
            return pathExecutable.get();
        }

        throw new IllegalStateException(
                "The program wasn't able to find yt-dlp");
    }

    public static Path getFfmpegDirectory() {
        String executable = getExecutableName("ffmpeg");

        Optional<Path> pathExecutable = findExecutableOnPath(executable);

        // Development: system PATH has priority
        if (!isPackaged() && pathExecutable.isPresent()) {
            return pathExecutable.get().getParent();
        }

        // Check bundled executable
        Path bundledDirectory = getToolsDirectory().resolve("ffmpeg");
        Path bundledExecutable = bundledDirectory.resolve(executable);

        if (Files.isRegularFile(bundledExecutable)
                && Files.isExecutable(bundledExecutable)) {
            return bundledDirectory;
        }

        // Packaged: system PATH is the fallback
        if (pathExecutable.isPresent()) {
            return pathExecutable.get().getParent();
        }

        throw new IllegalStateException(
                "The program wasn't able to find ffmpeg");
    }

}
