package com.playlistlib.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playlistlib.model.DownloadRequest;
import com.playlistlib.util.YtdlpLocator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs yt-dlp and collects the final file and metadata for each completed item.
 */
public class YtdlpDownloadService {
    /*
     * Types of media supported
     */

    public static final List<String> AUDIO_FORMATS = List.of(
            "MP3", "WAV", "FLAC", "M4A",
            "OPUS", "AAC", "ALAC", "VORBIS");

    public static final List<String> VIDEO_FORMATS = List.of("MP4", "WEBM");

    public static final List<String> ALL_FORMATS = Stream.concat(
            AUDIO_FORMATS.stream(),
            VIDEO_FORMATS.stream()).toList();

    /**
     * Keeps the original API available for any existing callers that only need
     * yt-dlp's exit code.
     */
    public int download(DownloadRequest request, Consumer<String> output)
            throws IOException, InterruptedException {
        return downloadAndCollect(request, output).exitCode();
    }

    /**
     * Downloads the requested URL and returns metadata for each item that reaches
     * yt-dlp's after_move stage. For a playlist, this normally means one record
     * per successfully downloaded playlist item.
     */
    public DownloadResult downloadAndCollect(
            DownloadRequest request,
            Consumer<String> output) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "Download request cannot be null");
        Objects.requireNonNull(output, "Output consumer cannot be null");

        Path metadataFile = Files.createTempFile("playlistlib-ytdlp-", ".jsonl");

        try {
            List<String> command = buildYdlpCommand(request, metadataFile);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.accept(line);
                }
            }

            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw exception;
            }

            long metadataBytes = Files.size(metadataFile);
            output.accept("yt-dlp metadata file size: " + metadataBytes + " byte(s).");

            List<LibraryService.DownloadedMedia> downloadedItems = readDownloadedItems(
                    metadataFile,
                    request.getUrl(),
                    request.getOutputDirectory(),
                    output);

            output.accept("Collected " + downloadedItems.size()
                    + " completed item(s) from yt-dlp metadata.");
            if (downloadedItems.isEmpty()) {
                output.accept("No items were registered from this download. "
                        + "Check the yt-dlp messages above; the list and its empty manifest "
                        + "have still been created.");
            }

            return new DownloadResult(exitCode, downloadedItems);
        } finally {
            Files.deleteIfExists(metadataFile);
        }
    }

    private List<String> buildYdlpCommand(DownloadRequest request, Path metadataFile) {
        List<String> command = new ArrayList<>();

        command.add(YtdlpLocator.getYtdlpPath().toString());

        // yt-dlp appends one JSON object for each item after its final file is moved.
        // The temporary file is outside the selected list directory, so it will
        // never become part of the user's media collection.
        command.add("--print-to-file");
        command.add("after_move:%(.{id,title,webpage_url,original_url,extractor,duration,filepath,ext,mime_type})j");
        command.add(metadataFile.toString());
        command.add("--no-simulate");

        if (YtdlpLocator.isWindows()) {
            command.add("--ffmpeg-location");
            command.add(YtdlpLocator.getFfmpegDirectory().toString());
        }

        String format = request.getFormat();

        if (isAudioFormat(format)) {
            command.add("-x");
            command.add("--audio-format");
            command.add(format.toLowerCase());
        } else {
            command.add("-f");
            command.add(String.format(
                    "bv*[ext=%s]+ba[ext=m4a]/b[ext=%s]",
                    format.toLowerCase(),
                    format.toLowerCase()));
        }

        if (request.isEmbedThumbnail()) {
            command.add("--embed-thumbnail");
        }

        if (request.getOutputDirectory() != null) {
            command.add("-P");
            command.add(request.getOutputDirectory().toString());
        }

        command.add(request.getUrl());
        return command;
    }

    private List<LibraryService.DownloadedMedia> readDownloadedItems(
            Path metadataFile,
            String requestedUrl,
            Path outputDirectory,
            Consumer<String> output) throws IOException {
        List<LibraryService.DownloadedMedia> items = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(metadataFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }

                try {
                    JsonElement parsed = JsonParser.parseString(line);
                    if (!parsed.isJsonObject()) {
                        output.accept("Ignoring invalid yt-dlp metadata line " + lineNumber + ".");
                        continue;
                    }

                    JsonObject metadata = parsed.getAsJsonObject();
                    String filepath = getString(metadata, "filepath");
                    if (filepath == null || filepath.isBlank()) {
                        output.accept("Ignoring metadata without a final file path (line "
                                + lineNumber + ").");
                        continue;
                    }

                    Path mediaPath = Path.of(filepath);
                    if (!mediaPath.isAbsolute() && outputDirectory != null) {
                        mediaPath = outputDirectory.resolve(mediaPath);
                    }
                    mediaPath = mediaPath.toAbsolutePath().normalize();

                    if (!Files.isRegularFile(mediaPath)) {
                        output.accept("Ignoring metadata because the downloaded file was not found: "
                                + mediaPath);
                        continue;
                    }

                    String title = getString(metadata, "title");
                    if (title == null || title.isBlank()) {
                        title = removeExtension(mediaPath.getFileName().toString());
                    }

                    String sourceUrl = getString(metadata, "webpage_url");
                    if (sourceUrl == null || sourceUrl.isBlank()) {
                        sourceUrl = getString(metadata, "original_url");
                    }
                    if (sourceUrl == null || sourceUrl.isBlank()) {
                        sourceUrl = requestedUrl;
                    }

                    items.add(new LibraryService.DownloadedMedia(
                            title,
                            sourceUrl,
                            getString(metadata, "id"),
                            getString(metadata, "extractor"),
                            getDouble(metadata, "duration"),
                            metadata.toString(),
                            mediaPath,
                            getString(metadata, "ext"),
                            getString(metadata, "mime_type")));

                } catch (RuntimeException exception) {
                    output.accept("Could not read yt-dlp metadata line " + lineNumber
                            + ": " + exception.getMessage());
                }
            }
        }

        return List.copyOf(items);
    }

    private String getString(JsonObject object, String property) {
        JsonElement value = object.get(property);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.getAsString();
    }

    private Double getDouble(JsonObject object, String property) {
        JsonElement value = object.get(property);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }

        try {
            double number = value.getAsDouble();
            return Double.isFinite(number) && number >= 0 ? number : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private boolean isAudioFormat(String format) {
        return AUDIO_FORMATS
                .contains(format.toUpperCase());
    }

    public record DownloadResult(
            int exitCode,
            List<LibraryService.DownloadedMedia> downloadedItems) {
        public DownloadResult {
            downloadedItems = List.copyOf(downloadedItems);
        }
    }
}
