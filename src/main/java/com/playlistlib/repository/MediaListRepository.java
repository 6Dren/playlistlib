package com.playlistlib.repository;

import com.playlistlib.database.DatabaseManager;
import com.playlistlib.model.MediaList;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

/** Database operations for physical media-list directories. */
public final class MediaListRepository {

    public void insert(MediaList mediaList) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            insert(connection, mediaList);
        }
    }

    /** Inserts a list using a caller-owned connection (and transaction). */
    public void insert(Connection connection, MediaList mediaList) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(mediaList, "Media list cannot be null");

        String sql = """
                INSERT INTO media_lists (
                    id, name, directory_path, manifest_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mediaList.getId());
            statement.setString(2, mediaList.getName());
            statement.setString(3, mediaList.getDirectoryPath());
            statement.setInt(4, mediaList.getManifestVersion());
            statement.setString(5, mediaList.getCreatedAt().toString());
            statement.setString(6, mediaList.getUpdatedAt().toString());
            statement.executeUpdate();
        }
    }

    public List<MediaList> findAll(Connection connection) throws SQLException {

        Objects.requireNonNull(connection, "Connection cannot be null");

        String sql = """
                SELECT *
                FROM media_lists
                ORDER BY name COLLATE NOCASE, id
                """;

        List<MediaList> lists = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                lists.add(mapRow(result));
            }
        }

        return lists;
    }

    public void removeList(MediaList mediaList, boolean removePhysically) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            removeListFromDatabase(connection, mediaList);
            // if (removePhysically)
            // removeListPhysically(connection, mediaList);
        }
    }

    public void removeListFromDatabase(
            Connection connection,
            MediaList mediaList) throws SQLException {

        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(mediaList, "Media list cannot be null");

        String sql = """
                DELETE FROM media_lists
                WHERE id = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, mediaList.getId());

            int rowsDeleted = statement.executeUpdate();

            if (rowsDeleted == 0) {
                throw new SQLException(
                        "Media list not found: " + mediaList.getId());
            }
        }
    }

    public void updateDirectoryPath(
            Connection connection,
            String listId,
            String directoryPath) throws SQLException {

        String sql = """
                UPDATE media_lists
                SET directory_path = ?
                WHERE id = ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, directoryPath);
            statement.setString(2, listId);

            if (statement.executeUpdate() != 1) {
                throw new SQLException("Media list not found: " + listId);
            }
        }
    }

    /**
     * Updates the list's modification timestamp inside the caller's transaction.
     */
    public void touch(Connection connection, String listId, Instant updatedAt) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(listId, "List ID cannot be null");
        Objects.requireNonNull(updatedAt, "Updated timestamp cannot be null");

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE media_lists SET updated_at = ? WHERE id = ?")) {
            statement.setString(1, updatedAt.toString());
            statement.setString(2, listId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Media list not found: " + listId);
            }
        }
    }

    public Optional<MediaList> findById(String id) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            return findById(connection, id);
        }
    }

    public Optional<MediaList> findById(Connection connection, String id) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(id, "List ID cannot be null");

        String sql = "SELECT * FROM media_lists WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapRow(result)) : Optional.empty();
            }
        }
    }

    public Optional<MediaList> findByDirectoryPath(String directoryPath)
            throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            return findByDirectoryPath(connection, directoryPath);
        }
    }

    public Optional<MediaList> findByDirectoryPath(Connection connection, String directoryPath)
            throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(directoryPath, "Directory path cannot be null");

        String sql = "SELECT * FROM media_lists WHERE directory_path = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, directoryPath);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapRow(result)) : Optional.empty();
            }
        }
    }

    private MediaList mapRow(ResultSet result) throws SQLException {
        try {
            return new MediaList(
                    result.getString("id"),
                    result.getString("name"),
                    result.getString("directory_path"),
                    result.getInt("manifest_version"),
                    Instant.parse(result.getString("created_at")),
                    Instant.parse(result.getString("updated_at")));
        } catch (DateTimeParseException exception) {
            throw new SQLException("Invalid timestamp stored in media_lists", exception);
        }
    }
}
