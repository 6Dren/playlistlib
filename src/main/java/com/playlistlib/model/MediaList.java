package com.playlistlib.model;

import java.time.Instant;
import java.util.Objects;

/** Represents one physical media-list directory. */
public final class MediaList {

    private final String id;
    private final String name;
    private final String directoryPath;
    private final int manifestVersion;
    private final Instant createdAt;
    private final Instant updatedAt;

    public MediaList(
            String id,
            String name,
            String directoryPath,
            int manifestVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.directoryPath = Objects.requireNonNull(
                directoryPath, "Directory path cannot be null");
        this.manifestVersion = manifestVersion;
        this.createdAt = Objects.requireNonNull(
                createdAt, "Creation timestamp cannot be null");
        this.updatedAt = Objects.requireNonNull(
                updatedAt, "Update timestamp cannot be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Name cannot be blank");
        }
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be blank");
        }
        if (manifestVersion < 1) {
            throw new IllegalArgumentException("Manifest version must be at least 1");
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDirectoryPath() {
        return directoryPath;
    }

    public int getManifestVersion() {
        return manifestVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return name;
    }
}
