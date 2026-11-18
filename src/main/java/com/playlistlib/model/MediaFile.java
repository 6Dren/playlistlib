package com.playlistlib.model;

import java.time.Instant;
import java.util.Objects;

/** Represents one physical file belonging to a media item. */
public final class MediaFile {

    private final String id;
    private final String mediaId;
    private final String relativePath;
    private final String fileRole;
    private final String format;
    private final String mimeType;
    private final Long fileSize;
    private final String checksum;
    private final Instant addedAt;

    public MediaFile(
            String id,
            String mediaId,
            String relativePath,
            String fileRole,
            String format,
            String mimeType,
            Long fileSize,
            String checksum,
            Instant addedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.mediaId = Objects.requireNonNull(mediaId, "Media ID cannot be null");
        this.relativePath = Objects.requireNonNull(
                relativePath, "Relative path cannot be null");
        this.fileRole = fileRole == null ? "MEDIA" : fileRole;
        this.format = format;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.addedAt = Objects.requireNonNull(addedAt, "Added timestamp cannot be null");

        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("Relative path cannot be blank");
        }
        if (fileSize != null && fileSize < 0) {
            throw new IllegalArgumentException("File size cannot be negative");
        }
    }

    public String getId() {
        return id;
    }

    public String getMediaId() {
        return mediaId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getFileRole() {
        return fileRole;
    }

    public String getFormat() {
        return format;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getChecksum() {
        return checksum;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
