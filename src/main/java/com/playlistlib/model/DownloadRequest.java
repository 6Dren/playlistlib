package com.playlistlib.model;

import java.nio.file.Path;

public class DownloadRequest {

    private final String url;
    private final String format;
    private final boolean embedThumbnail;
    private final Path outputDirectory;

    public DownloadRequest(
            String url,
            String format,
            boolean embedThumbnail,
            Path outputDirectory) {

        this.url = url;
        this.format = format;
        this.embedThumbnail = embedThumbnail;
        this.outputDirectory = outputDirectory;
    }

    public String getUrl() {
        return url;
    }

    public String getFormat() {
        return format;
    }

    public boolean isEmbedThumbnail() {
        return embedThumbnail;
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }
}
