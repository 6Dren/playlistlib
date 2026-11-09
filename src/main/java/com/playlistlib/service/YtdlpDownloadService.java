package com.playlistlib.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.playlistlib.model.DownloadRequest;
import com.playlistlib.util.YtdlpLocator;

public class YtdlpDownloadService {
    public int download(DownloadRequest request, Consumer<String> output)
            throws IOException, InterruptedException {

        // Configure the command. Adjust the path if yt-dlp isn't in PATH resulting in a
        // exception
        List<String> command = buildYdlpCommand(request);

        ProcessBuilder pb = new ProcessBuilder(command);
        System.out.println(command);

        // Redirect error stream
        pb.redirectErrorStream(true);

        // Creating process for the new thread
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {

            String line = reader.readLine();

            while (line != null) {

                // outputline
                // final String outputLine = line;

                // Update the GUI
                line = reader.readLine();
                output.accept(line);
            }
        }

        // Wait for yt-dlp to finish
        return process.waitFor();
    }

    private List<String> buildYdlpCommand(DownloadRequest request) {
        List<String> command = new ArrayList<>();

        command.add(YtdlpLocator.getYtDlpPath().toString());
        command.add(request.getUrl());

        if (YtdlpLocator.isWindows()) {
            command.add("--ffmpeg-location");
            command.add(YtdlpLocator.getFfmpegPath().getParent().toString());
        }

        String format = request.getFormat();

        if (isAudioFormat(format)) {
            // command.add("-f");
            // command.add("bestaudio");
            command.add("-x");
            command.add("--audio-format");
            command.add(format.toLowerCase());
        }

        else {
            command.add("-f");
            command.add(String.format("bv*[ext=%s]+ba[ext=m4a]/b[ext=%s]", format.toLowerCase(), format.toLowerCase()));// "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]"
        }

        // Download thumbnail if selected
        if (request.isEmbedThumbnail()) {
            command.add("--embed-thumbnail");
        }

        if (request.getOutputDirectory() != null) {
            command.add("-P");
            command.add(request.getOutputDirectory().toString());
        }

        return command;
    }

    // Check whether the selected format is audio.
    private boolean isAudioFormat(String format) {

        ArrayList<String> words = new ArrayList<>(List.of("MP3", "WAV", "FLAC", "M4A",
                "OPUS", "AAC", "ALAC", "VORBIS"));

        return words.contains(format);
    }

}
