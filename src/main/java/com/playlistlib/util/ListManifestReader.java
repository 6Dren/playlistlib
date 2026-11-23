package com.playlistlib.util;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.playlistlib.model.Media;
import com.playlistlib.model.MediaFile;
import com.playlistlib.repository.ListEntryRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.playlistlib.model.MediaList;

public class ListManifestReader {

    private static final String MANIFEST_FILE_NAME = ".playlistlib.json";

    private final Path directory;

    public ListManifestReader(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("Directory cannot be null.");
        }

        this.directory = directory.toAbsolutePath().normalize();
    }

    public MediaList read() throws IOException {
        return readManifest().list();
    }

    public ManifestData readManifest() throws IOException {

        if (!Files.isDirectory(directory)) {
            throw new IOException("Directory does not exist: " + directory);
        }

        Path manifestPath = directory.resolve(MANIFEST_FILE_NAME);

        if (!Files.isRegularFile(manifestPath)) {
            throw new IOException("Manifest file not found: " + manifestPath);
        }

        try (Reader reader = Files.newBufferedReader(
                manifestPath, StandardCharsets.UTF_8)) {

            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            int version = root.get("manifestVersion").getAsInt();

            JsonObject listJson = root.getAsJsonObject("list");

            MediaList mediaList = new MediaList(
                    getRequiredString(listJson, "id"),
                    getRequiredString(listJson, "name"),
                    directory.toString(),
                    version,
                    getRequiredInstant(listJson, "createdAt"),
                    getRequiredInstant(listJson, "updatedAt"));

            List<Media> mediaItems = new ArrayList<>();
            List<MediaFile> mediaFiles = new ArrayList<>();

            // Read media records and their associated physical files.
            for (JsonElement element : getOptionalArray(root, "media")) {

                JsonObject mediaJson = element.getAsJsonObject();

                String mediaId = getRequiredString(mediaJson, "id");

                Media media = new Media(
                        mediaId,
                        mediaList.getId(),
                        getRequiredString(mediaJson, "title"),
                        getNullableString(mediaJson, "sourceUrl"),
                        getNullableString(mediaJson, "sourceId"),
                        getNullableString(mediaJson, "extractor"),
                        getNullableDouble(mediaJson, "durationSeconds"),
                        getNullableString(mediaJson, "metadataJson"),
                        getRequiredInstant(mediaJson, "createdAt"),
                        getRequiredInstant(mediaJson, "updatedAt"));

                mediaItems.add(media);

                for (JsonElement fileElement : getOptionalArray(mediaJson, "files")) {

                    JsonObject fileJson = fileElement.getAsJsonObject();

                    mediaFiles.add(new MediaFile(
                            getRequiredString(fileJson, "id"),
                            mediaId,
                            getRequiredString(fileJson, "relativePath"),
                            getNullableString(fileJson, "fileRole"),
                            getNullableString(fileJson, "format"),
                            getNullableString(fileJson, "mimeType"),
                            getNullableLong(fileJson, "fileSize"),
                            getNullableString(fileJson, "checksum"),
                            getRequiredInstant(fileJson, "addedAt")));
                }
            }

            // Read the ordered contents of the list.
            List<ListEntryRepository.Entry> entries = new ArrayList<>();

            for (JsonElement element : getOptionalArray(root, "entries")) {

                JsonObject entryJson = element.getAsJsonObject();

                entries.add(new ListEntryRepository.Entry(
                        getRequiredString(entryJson, "id"),
                        mediaList.getId(),
                        getRequiredString(entryJson, "type"),
                        getNullableString(entryJson, "mediaId"),
                        getNullableString(entryJson, "childListId"),
                        entryJson.get("position").getAsInt()));
            }

            return new ManifestData(
                    mediaList,
                    mediaItems,
                    mediaFiles,
                    entries);

        } catch (RuntimeException exception) {
            throw new IOException(
                    "Could not read manifest: " + manifestPath,
                    exception);
        }
    }

    private JsonArray getOptionalArray(JsonObject object, String property) {

        JsonElement value = object.get(property);

        if (value == null || value.isJsonNull()) {
            return new JsonArray();
        }

        return value.getAsJsonArray();
    }

    private String getNullableString(JsonObject object, String property) {

        JsonElement value = object.get(property);

        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }

    private Double getNullableDouble(JsonObject object, String property) {

        JsonElement value = object.get(property);

        return value == null || value.isJsonNull()
                ? null
                : value.getAsDouble();
    }

    private Long getNullableLong(JsonObject object, String property) {

        JsonElement value = object.get(property);

        return value == null || value.isJsonNull()
                ? null
                : value.getAsLong();
    }

    private String getRequiredString(JsonObject object, String property)
            throws IOException {

        if (!object.has(property) || object.get(property).isJsonNull()) {
            throw new IOException(
                    "Missing required manifest property: " + property);
        }

        String value = object.get(property).getAsString();

        if (value.isBlank()) {
            throw new IOException(
                    "Manifest property cannot be empty: " + property);
        }

        return value;
    }

    private Instant getRequiredInstant(JsonObject object, String property)
            throws IOException {

        String value = getRequiredString(object, property);

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IOException(
                    "Invalid timestamp in manifest: " + property,
                    e);
        }
    }

    public record ManifestData(
            MediaList list,
            List<Media> media,
            List<MediaFile> files,
            List<ListEntryRepository.Entry> entries) {

        public ManifestData {
            media = List.copyOf(media);
            files = List.copyOf(files);
            entries = List.copyOf(entries);
        }
    }
}
