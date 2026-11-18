package com.playlistlib.repository;

import com.playlistlib.database.DatabaseManager;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Database operations for the ordered contents of a list. */
public final class ListEntryRepository {

    public record Entry(
            String id,
            String listId,
            String entryType,
            String mediaId,
            String childListId,
            int position) {
        public Entry {
            Objects.requireNonNull(id, "Entry ID cannot be null");
            Objects.requireNonNull(listId, "List ID cannot be null");
            Objects.requireNonNull(entryType, "Entry type cannot be null");

            if (!entryType.equals("MEDIA") && !entryType.equals("LIST")) {
                throw new IllegalArgumentException("Entry type must be MEDIA or LIST");
            }
            if (position < 0) {
                throw new IllegalArgumentException("Position cannot be negative");
            }
            if (entryType.equals("MEDIA") && (mediaId == null || childListId != null)) {
                throw new IllegalArgumentException("A MEDIA entry requires only mediaId");
            }
            if (entryType.equals("LIST") && (childListId == null || mediaId != null)) {
                throw new IllegalArgumentException("A LIST entry requires only childListId");
            }
        }
    }

    public Entry insertMediaEntry(Connection connection, String listId, String mediaId, int position)
            throws SQLException {
        Entry entry = new Entry(UUID.randomUUID().toString(), listId, "MEDIA", mediaId, null, position);
        insert(connection, entry);

        return entry;
    }

    public Entry insertChildListEntry(
            Connection connection, String listId, String childListId, int position) throws SQLException {
        Entry entry = new Entry(UUID.randomUUID().toString(), listId, "LIST", null, childListId, position);
        insert(connection, entry);

        return entry;
    }

    public void insert(Connection connection, Entry entry) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(entry, "Entry cannot be null");

        String sql = """
                INSERT INTO list_entries (
                    id, list_id, entry_type, media_id, child_list_id, position
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.id());
            statement.setString(2, entry.listId());
            statement.setString(3, entry.entryType());
            statement.setString(4, entry.mediaId());
            statement.setString(5, entry.childListId());
            statement.setInt(6, entry.position());
            
            statement.executeUpdate();
        }
    }

    public int getNextPosition(Connection connection, String listId) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(listId, "List ID cannot be null");

        String sql = "SELECT COALESCE(MAX(position) + 1, 0) FROM list_entries WHERE list_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, listId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    public List<Entry> findByListId(String listId) throws SQLException, IOException {
        try (Connection connection = DatabaseManager.getConnection()) {
            return findByListId(connection, listId);
        }
    }

    public List<Entry> findByListId(Connection connection, String listId) throws SQLException {
        Objects.requireNonNull(connection, "Connection cannot be null");
        Objects.requireNonNull(listId, "List ID cannot be null");

        String sql = """
                SELECT id, list_id, entry_type, media_id, child_list_id, position
                FROM list_entries
                WHERE list_id = ?
                ORDER BY position, id
                """;

        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, listId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(new Entry(
                            result.getString("id"),
                            result.getString("list_id"),
                            result.getString("entry_type"),
                            result.getString("media_id"),
                            result.getString("child_list_id"),
                            result.getInt("position")));
                }
            }
        }
        return entries;
    }
}
