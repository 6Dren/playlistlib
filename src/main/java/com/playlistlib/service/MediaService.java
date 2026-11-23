package com.playlistlib.service;

import com.playlistlib.model.Media;
import com.playlistlib.repository.MediaRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.playlistlib.database.DatabaseManager;
import java.sql.Connection;

public class MediaService {

    private final MediaRepository mediaRepository;

    /*
     * Creates a MediaService with its own repository.
     */
    public MediaService() {
        this(new MediaRepository());
    }

    /*
     * Allows a repository to be supplied externally.
     */
    public MediaService(MediaRepository mediaRepository) {

        this.mediaRepository = Objects.requireNonNull(
                mediaRepository,
                "MediaRepository cannot be null");
    }

    /*
     * Creates and persists a new Media object.
     */
    public Media createMedia(
            String listId,
            String title,
            String sourceUrl,
            String sourceId,
            String extractor,
            Double durationSeconds,
            String metadataJson) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            return createMedia(
                    connection,
                    listId,
                    title,
                    sourceUrl,
                    sourceId,
                    extractor,
                    durationSeconds,
                    metadataJson);
        }
    }

    public Media createMedia(
            Connection connection,
            String listId,
            String title,
            String sourceUrl,
            String sourceId,
            String extractor,
            Double durationSeconds,
            String metadataJson) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");

        listId = requireNonBlank(listId, "List ID");
        title = requireNonBlank(title, "Title");
        validateDuration(durationSeconds);

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Media media = new Media(
                id,
                listId,
                title,
                sourceUrl,
                sourceId,
                extractor,
                durationSeconds,
                metadataJson,
                now,
                now);

        mediaRepository.insert(connection, media);
        return media;
    }

    /*
     * Retrieves a Media object by its ID.
     */
    public Optional<Media> findMediaById(String id)
            throws SQLException, IOException {

        id = requireNonBlank(id, "Media ID");

        return mediaRepository.findById(id);
    }

    /*
     * Retrieves all Media objects belonging to a list.
     */
    public List<Media> findMediaByListId(String listId)
            throws SQLException, IOException {

        listId = requireNonBlank(listId, "List ID");

        return mediaRepository.findByListId(listId);
    }

    /*
     * Updates an existing Media object's metadata.
     */
    public void updateMediaDetails(Media media)
            throws SQLException, IOException {

        Objects.requireNonNull(media, "Media cannot be null");

        requireNonBlank(media.getTitle(), "Title");

        validateDuration(media.getDurationSeconds());

        Instant previousUpdatedAt = media.getUpdatedAt();

        media.setUpdatedAt(Instant.now());

        try {

            boolean updated = mediaRepository.updateDetails(media);

            if (!updated) {

                throw new IllegalArgumentException(
                        "Media not found: " + media.getId());
            }

        } catch (SQLException | IOException | RuntimeException e) {

            // Restore the previous timestamp if the update failed.
            media.setUpdatedAt(previousUpdatedAt);

            throw e;
        }
    }

    /*
     * Validates non-null and non-empty strings.
     */
    private String requireNonBlank(String value, String fieldName) {

        Objects.requireNonNull(
                value,
                fieldName + " cannot be null");

        value = value.trim();

        if (value.isEmpty()) {

            throw new IllegalArgumentException(
                    fieldName + " cannot be empty");
        }

        return value;
    }

    /*
     * Validates the media duration.
     */
    private void validateDuration(Double durationSeconds) {

        if (durationSeconds != null
                && (!Double.isFinite(durationSeconds)
                        || durationSeconds < 0)) {

            throw new IllegalArgumentException(
                    "Duration must be a finite, non-negative number.");
        }
    }
}
