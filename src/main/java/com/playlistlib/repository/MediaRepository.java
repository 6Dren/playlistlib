package com.playlistlib.repository;

import com.playlistlib.database.DatabaseManager;
import com.playlistlib.model.Media;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MediaRepository {

    /*
     * INSERT
     *
     * Inserts a new media record into the database.
     */
    public void insert(Media media) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            insert(connection, media);
        }
    }

    public void insert(Connection connection, Media media) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(media, "Media cannot be null");

        String sql = """
                INSERT INTO media (
                    id, list_id, title, source_url, source_id, extractor,
                    duration_seconds, metadata_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, media.getId());
            statement.setString(2, media.getListId());
            statement.setString(3, media.getTitle());
            statement.setString(4, media.getSourceUrl());
            statement.setString(5, media.getSourceId());
            statement.setString(6, media.getExtractor());

            if (media.getDurationSeconds() == null) {
                statement.setNull(7, Types.REAL);
            } else {
                statement.setDouble(7, media.getDurationSeconds());
            }

            statement.setString(8, media.getMetadataJson());
            statement.setString(9, media.getCreatedAt().toString());
            statement.setString(10, media.getUpdatedAt().toString());
            statement.executeUpdate();
        }
    }

    /*
     * SELECT
     *
     * Finds one media record by its ID.
     */
    public List<Media> findByListId(String listId) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            return findByListId(connection, listId);
        }
    }

    public List<Media> findByListId(Connection connection, String listId) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(listId, "List ID cannot be null");

        String sql = """
                SELECT *
                FROM media
                WHERE list_id = ?
                ORDER BY created_at, id
                """;

        List<Media> mediaList = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, listId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    mediaList.add(mapRow(result));
                }
            }
        }
        return mediaList;
    }

    public Optional<Media> findById(String id)
            throws SQLException, IOException {

        String sql = """
                SELECT *
                FROM media
                WHERE id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, id);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(mapRow(result));
                }

                return Optional.empty();
            }
        }
    }

    /*
     * UPDATE
     *
     * Updates media metadata.
     *
     * Does not change list_id.
     */
    public boolean updateDetails(Media media)
            throws SQLException, IOException {

        Objects.requireNonNull(media, "Media cannot be null");

        String sql = """
                UPDATE media
                SET
                    title = ?,
                    source_url = ?,
                    source_id = ?,
                    extractor = ?,
                    duration_seconds = ?,
                    metadata_json = ?,
                    updated_at = ?
                WHERE id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, media.getTitle());
            statement.setString(2, media.getSourceUrl());
            statement.setString(3, media.getSourceId());
            statement.setString(4, media.getExtractor());

            if (media.getDurationSeconds() == null) {
                statement.setNull(5, Types.REAL);
            } else {
                statement.setDouble(
                        5,
                        media.getDurationSeconds());
            }

            statement.setString(6, media.getMetadataJson());
            statement.setString(7, media.getUpdatedAt().toString());
            statement.setString(8, media.getId());

            return statement.executeUpdate() == 1;
        }
    }

    /*
     * DELETE
     *
     * Deletes the database record.
     *
     * This does NOT delete physical files from disk.
     */
    public boolean deleteById(String id)
            throws SQLException, IOException {

        String sql = """
                DELETE FROM media
                WHERE id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, id);

            return statement.executeUpdate() == 1;
        }
    }

    /*
     * Converts a database row into a Media object.
     */
    private Media mapRow(ResultSet result)
            throws SQLException {

        double duration = result.getDouble("duration_seconds");

        Double durationSeconds = result.wasNull() ? null : duration;

        return new Media(
                result.getString("id"),
                result.getString("list_id"),
                result.getString("title"),
                result.getString("source_url"),
                result.getString("source_id"),
                result.getString("extractor"),
                durationSeconds,
                result.getString("metadata_json"),
                parseInstant(result.getString("created_at")),
                parseInstant(result.getString("updated_at")));
    }

    /*
     * Converts the SQLite timestamp string into an Instant.
     */
    private Instant parseInstant(String value)
            throws SQLException {

        try {
            return Instant.parse(value);

        } catch (DateTimeParseException e) {

            throw new SQLException(
                    "Invalid timestamp stored in database: " + value,
                    e);
        }
    }
}
