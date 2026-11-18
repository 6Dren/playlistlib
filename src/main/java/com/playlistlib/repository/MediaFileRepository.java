package com.playlistlib.repository;

import com.playlistlib.database.DatabaseManager;
import com.playlistlib.model.MediaFile;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Database operations for the physical files belonging to media items. */
public final class MediaFileRepository {

    public void insert(MediaFile mediaFile) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            insert(connection, mediaFile);
        }
    }

    /** Inserts a file using a caller-owned connection (and transaction). */
    public void insert(Connection connection, MediaFile mediaFile) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(mediaFile, "Media file cannot be null");

        String sql = """
                INSERT INTO media_files (
                    id, media_id, relative_path, file_role, format, mime_type,
                    file_size, checksum, added_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mediaFile.getId());
            statement.setString(2, mediaFile.getMediaId());
            statement.setString(3, mediaFile.getRelativePath());
            statement.setString(4, mediaFile.getFileRole());
            statement.setString(5, mediaFile.getFormat());
            statement.setString(6, mediaFile.getMimeType());
            if (mediaFile.getFileSize() == null) {
                statement.setNull(7, java.sql.Types.BIGINT);
            } else {
                statement.setLong(7, mediaFile.getFileSize());
            }
            statement.setString(8, mediaFile.getChecksum());
            statement.setString(9, mediaFile.getAddedAt().toString());
            statement.executeUpdate();
        }
    }

    public List<MediaFile> findByMediaId(String mediaId) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            return findByMediaId(connection, mediaId);
        }
    }

    public List<MediaFile> findByMediaId(Connection connection, String mediaId)
            throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(mediaId, "Media ID cannot be null");

        String sql = """
                SELECT * FROM media_files
                WHERE media_id = ?
                ORDER BY added_at, id
                """;

        List<MediaFile> files = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mediaId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    files.add(mapRow(result));
                }
            }
        }
        return files;
    }

    private MediaFile mapRow(ResultSet result) throws SQLException {
        long size = result.getLong("file_size");
        Long fileSize = result.wasNull() ? null : size;

        try {
            return new MediaFile(
                    result.getString("id"),
                    result.getString("media_id"),
                    result.getString("relative_path"),
                    result.getString("file_role"),
                    result.getString("format"),
                    result.getString("mime_type"),
                    fileSize,
                    result.getString("checksum"),
                    Instant.parse(result.getString("added_at"))
            );
        } catch (DateTimeParseException exception) {
            throw new SQLException("Invalid timestamp stored in media_files", exception);
        }
    }
}
