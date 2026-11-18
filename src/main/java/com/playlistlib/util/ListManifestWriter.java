package com.playlistlib.util;

import com.playlistlib.model.Media;
import com.playlistlib.model.MediaFile;
import com.playlistlib.model.MediaList;
import com.playlistlib.repository.ListEntryRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** Writes the portable JSON manifest stored inside each list directory. */
public final class ListManifestWriter {

    public static final String MANIFEST_FILE_NAME = ".playlistlib.json";

    /**
     * Replaces the manifest atomically where the filesystem supports it.
     * The caller should pass the complete current database state for this list.
     */
    public void write(
            MediaList mediaList,
            List<Media> mediaItems,
            Map<String, List<MediaFile>> filesByMediaId,
            List<ListEntryRepository.Entry> entries) throws IOException {
        Path directory = Path.of(mediaList.getDirectoryPath()).toAbsolutePath().normalize();
        Files.createDirectories(directory);

        Path target = directory.resolve(MANIFEST_FILE_NAME);
        if (Files.exists(target) && Files.isDirectory(target)) {
            throw new IOException("Manifest path is a directory: " + target);
        }

        String json = buildJson(mediaList, mediaItems, filesByMediaId, entries);
        Path temporary = Files.createTempFile(directory, ".playlistlib-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String buildJson(
            MediaList list,
            List<Media> mediaItems,
            Map<String, List<MediaFile>> filesByMediaId,
            List<ListEntryRepository.Entry> entries) {
        StringBuilder json = new StringBuilder(2048);
        json.append("{\n  \"manifestVersion\": ").append(list.getManifestVersion()).append(",\n");
        json.append("  \"list\": {\n");
        field(json, "id", list.getId(), 4, true);
        field(json, "name", list.getName(), 4, true);
        field(json, "createdAt", list.getCreatedAt().toString(), 4, true);
        field(json, "updatedAt", list.getUpdatedAt().toString(), 4, false);
        json.append("  },\n");

        json.append("  \"entries\": [");
        for (int i = 0; i < entries.size(); i++) {
            ListEntryRepository.Entry entry = entries.get(i);
            json.append(i == 0 ? "\n" : ",\n").append("    {");
            json.append("\"id\": ").append(quote(entry.id())).append(", ");
            json.append("\"type\": ").append(quote(entry.entryType())).append(", ");
            json.append("\"position\": ").append(entry.position());
            if (entry.mediaId() != null) {
                json.append(", \"mediaId\": ").append(quote(entry.mediaId()));
            }
            if (entry.childListId() != null) {
                json.append(", \"childListId\": ").append(quote(entry.childListId()));
            }
            json.append("}");
        }
        if (!entries.isEmpty())
            json.append('\n').append("  ");
        json.append("],\n");

        json.append("  \"media\": [");
        for (int i = 0; i < mediaItems.size(); i++) {
            Media media = mediaItems.get(i);
            json.append(i == 0 ? "\n" : ",\n").append("    {\n");
            field(json, "id", media.getId(), 6, true);
            field(json, "title", media.getTitle(), 6, true);
            field(json, "sourceUrl", media.getSourceUrl(), 6, true);
            field(json, "sourceId", media.getSourceId(), 6, true);
            field(json, "extractor", media.getExtractor(), 6, true);
            numberField(json, "durationSeconds", media.getDurationSeconds(), 6, true);
            // Keep the original yt-dlp JSON as a string; this avoids corrupting the
            // manifest if metadata is absent or was supplied in an unexpected form.
            field(json, "metadataJson", media.getMetadataJson(), 6, true);
            field(json, "createdAt", media.getCreatedAt().toString(), 6, true);
            field(json, "updatedAt", media.getUpdatedAt().toString(), 6, true);

            json.append("      \"files\": [");
            List<MediaFile> files = filesByMediaId.getOrDefault(media.getId(), List.of());
            for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
                MediaFile file = files.get(fileIndex);
                json.append(fileIndex == 0 ? "\n" : ",\n").append("        {\n");
                field(json, "id", file.getId(), 10, true);
                field(json, "relativePath", file.getRelativePath(), 10, true);
                field(json, "fileRole", file.getFileRole(), 10, true);
                field(json, "format", file.getFormat(), 10, true);
                field(json, "mimeType", file.getMimeType(), 10, true);
                numberField(json, "fileSize", file.getFileSize(), 10, true);
                field(json, "checksum", file.getChecksum(), 10, true);
                field(json, "addedAt", file.getAddedAt().toString(), 10, false);
                json.append("        }");
            }
            if (!files.isEmpty())
                json.append('\n').append("      ");
            json.append("]\n    }");
        }
        if (!mediaItems.isEmpty())
            json.append('\n').append("  ");
        json.append("]\n}\n");
        return json.toString();
    }

    private void field(StringBuilder json, String name, String value, int indent, boolean comma) {
        json.append(" ".repeat(indent))
                .append(quote(name)).append(": ")
                .append(value == null ? "null" : quote(value));
        json.append(comma ? ",\n" : "\n");
    }

    private void numberField(StringBuilder json, String name, Number value, int indent, boolean comma) {
        json.append(" ".repeat(indent))
                .append(quote(name)).append(": ")
                .append(value == null ? "null" : value.toString());
        json.append(comma ? ",\n" : "\n");
    }

    private String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
