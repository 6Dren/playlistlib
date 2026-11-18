package com.playlistlib.service;

import com.playlistlib.database.DatabaseManager;
import com.playlistlib.model.Media;
import com.playlistlib.model.MediaFile;
import com.playlistlib.model.MediaList;
import com.playlistlib.repository.ListEntryRepository;
import com.playlistlib.repository.MediaFileRepository;
import com.playlistlib.repository.MediaListRepository;
import com.playlistlib.repository.MediaRepository;
import com.playlistlib.util.ListManifestWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates list, media, file, and ordered-entry persistence.
 * A playlist download should be submitted as one batch so its database records
 * are committed together.
 */
public final class LibraryService {

    private final MediaListRepository mediaListRepository;
    private final MediaRepository mediaRepository;
    private final MediaService mediaService;
    private final MediaFileRepository mediaFileRepository;
    private final ListEntryRepository listEntryRepository;
    private final ListManifestWriter manifestWriter;

    public LibraryService() {
        this(
                new MediaListRepository(),
                new MediaRepository(),
                new MediaFileRepository(),
                new ListEntryRepository(),
                new ListManifestWriter()
        );
    }

    public LibraryService(
            MediaListRepository mediaListRepository,
            MediaRepository mediaRepository,
            MediaFileRepository mediaFileRepository,
            ListEntryRepository listEntryRepository,
            ListManifestWriter manifestWriter
    ) {
        this.mediaListRepository = Objects.requireNonNull(mediaListRepository);
        this.mediaRepository = Objects.requireNonNull(mediaRepository);
        this.mediaService = new MediaService(mediaRepository);
        this.mediaFileRepository = Objects.requireNonNull(mediaFileRepository);
        this.listEntryRepository = Objects.requireNonNull(listEntryRepository);
        this.manifestWriter = Objects.requireNonNull(manifestWriter);
    }

    /**
     * Finds the list registered for this directory, or creates it.
     * A pre-existing manifest that is not present in this database is left
     * untouched; importing that portable manifest should be a separate operation.
     */
    public MediaList getOrCreateList(Path listDirectory) throws SQLException, IOException {
        Path directory = normalizeDirectory(listDirectory);
        Files.createDirectories(directory);
        String directoryPath = directory.toString();

        MediaList result;
        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<MediaList> existing = mediaListRepository.findByDirectoryPath(
                        connection, directoryPath);

                if (existing.isPresent()) {
                    result = existing.get();
                } else {
                    refuseUnregisteredManifest(directory);
                    result = newList(directory);
                    mediaListRepository.insert(connection, result);
                }
                connection.commit();
            } catch (SQLException | IOException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }

        refreshManifest(result.getId());
        return result;
    }

    /**
     * Registers all downloaded items from one download operation.
     * Each item receives its own UUID and each file path is stored relative to
     * the list directory, so the manifest remains portable when the folder moves.
     */
    public List<Media> registerDownloads(
            Path listDirectory,
            List<DownloadedMedia> downloadedMedia
    ) throws SQLException, IOException {
        Path directory = normalizeDirectory(listDirectory);
        Files.createDirectories(directory);
        Objects.requireNonNull(downloadedMedia, "Downloaded media list cannot be null");
        List<DownloadedMedia> batch = List.copyOf(downloadedMedia);

        List<Media> createdMedia = new ArrayList<>();
        String directoryPath = directory.toString();
        MediaList mediaList;

        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<MediaList> existing = mediaListRepository.findByDirectoryPath(
                        connection, directoryPath);

                if (existing.isPresent()) {
                    mediaList = existing.get();
                } else {
                    refuseUnregisteredManifest(directory);
                    mediaList = newList(directory);
                    mediaListRepository.insert(connection, mediaList);
                }

                int position = listEntryRepository.getNextPosition(connection, mediaList.getId());

                for (DownloadedMedia item : batch) {
                    Media media = mediaService.createMedia(
                            connection,
                            mediaList.getId(),
                            item.title(),
                            item.sourceUrl(),
                            item.sourceId(),
                            item.extractor(),
                            item.durationSeconds(),
                            item.metadataJson()
                    );

                    for (DownloadedFile downloadedFile : item.files()) {
                        Path absoluteFile = validateDownloadedFile(directory, downloadedFile.path());
                        Path relativeFile = directory.relativize(absoluteFile);
                        String portableRelativePath = relativeFile.toString().replace('\\', '/');

                        Long fileSize = Files.size(absoluteFile);
                        String mimeType = downloadedFile.mimeType();
                        if (mimeType == null || mimeType.isBlank()) {
                            mimeType = Files.probeContentType(absoluteFile);
                        }

                        String format = downloadedFile.format();
                        if (format == null || format.isBlank()) {
                            format = fileExtension(absoluteFile);
                        }

                        MediaFile mediaFile = new MediaFile(
                                UUID.randomUUID().toString(),
                                media.getId(),
                                portableRelativePath,
                                downloadedFile.fileRole(),
                                format,
                                mimeType,
                                fileSize,
                                null,
                                Instant.now()
                        );
                        mediaFileRepository.insert(connection, mediaFile);
                    }

                    listEntryRepository.insertMediaEntry(
                            connection, mediaList.getId(), media.getId(), position++);
                    createdMedia.add(media);
                }

                mediaListRepository.touch(connection, mediaList.getId(), Instant.now());
                connection.commit();
            } catch (SQLException | IOException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }

        // The database is authoritative. If manifest writing fails, the next
        // refreshManifest() call can regenerate it from the committed records.
        refreshManifest(mediaList.getId());
        return List.copyOf(createdMedia);
    }

    /** Rebuilds the portable manifest from the current SQLite records. */
    public void refreshManifest(String listId) throws SQLException, IOException {
        Objects.requireNonNull(listId, "List ID cannot be null");

        try (Connection connection = DatabaseManager.getConnection()) {
            MediaList mediaList = mediaListRepository.findById(connection, listId)
                    .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));

            List<Media> mediaItems = mediaRepository.findByListId(connection, listId);
            List<ListEntryRepository.Entry> entries =
                    listEntryRepository.findByListId(connection, listId);

            Map<String, List<MediaFile>> filesByMediaId = new LinkedHashMap<>();
            for (Media media : mediaItems) {
                filesByMediaId.put(
                        media.getId(),
                        mediaFileRepository.findByMediaId(connection, media.getId())
                );
            }

            manifestWriter.write(mediaList, mediaItems, filesByMediaId, entries);
        }
    }

    private MediaList newList(Path directory) {
        Path fileName = directory.getFileName();
        String name = fileName == null ? directory.toString() : fileName.toString();
        Instant now = Instant.now();
        return new MediaList(
                UUID.randomUUID().toString(),
                name,
                directory.toString(),
                1,
                now,
                now
        );
    }

    private void refuseUnregisteredManifest(Path directory) throws IOException {
        Path manifest = directory.resolve(ListManifestWriter.MANIFEST_FILE_NAME);
        if (Files.exists(manifest)) {
            throw new IOException(
                    "This directory already contains a PlaylistLib manifest, but the list "
                    + "is not registered in the current database: " + manifest
                    + ". Import the existing list instead of replacing its manifest."
            );
        }
    }

    private Path normalizeDirectory(Path directory) {
        Objects.requireNonNull(directory, "List directory cannot be null");
        return directory.toAbsolutePath().normalize();
    }

    private Path validateDownloadedFile(Path listDirectory, Path file) throws IOException {
        Objects.requireNonNull(file, "Downloaded file path cannot be null");
        Path absolute = (file.isAbsolute() ? file : listDirectory.resolve(file))
                .toAbsolutePath().normalize();

        if (!absolute.startsWith(listDirectory)) {
            throw new IOException("Downloaded file is outside the selected list directory: " + absolute);
        }
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("Downloaded file does not exist or is not a regular file: " + absolute);
        }

        // Also checks symlinks: a file inside the directory must not point outside it.
        Path realDirectory = listDirectory.toRealPath();
        Path realFile = absolute.toRealPath();
        if (!realFile.startsWith(realDirectory)) {
            throw new IOException("Downloaded file resolves outside the list directory: " + absolute);
        }
        return absolute;
    }

    private String fileExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)
                : null;
    }

    private void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    /** Metadata for one successfully downloaded yt-dlp item. */
    public record DownloadedMedia(
            String title,
            String sourceUrl,
            String sourceId,
            String extractor,
            Double durationSeconds,
            String metadataJson,
            List<DownloadedFile> files
    ) {
        public DownloadedMedia {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Downloaded media title cannot be blank");
            }
            if (durationSeconds != null
                    && (!Double.isFinite(durationSeconds) || durationSeconds < 0)) {
                throw new IllegalArgumentException("Duration must be finite and non-negative");
            }
            files = List.copyOf(Objects.requireNonNull(files, "Files cannot be null"));
            if (files.isEmpty()) {
                throw new IllegalArgumentException("A downloaded media item must have at least one file");
            }
        }

        public DownloadedMedia(
                String title,
                String sourceUrl,
                String sourceId,
                String extractor,
                Double durationSeconds,
                String metadataJson,
                Path mediaFile,
                String format,
                String mimeType
        ) {
            this(
                    title,
                    sourceUrl,
                    sourceId,
                    extractor,
                    durationSeconds,
                    metadataJson,
                    List.of(new DownloadedFile(mediaFile, "MEDIA", format, mimeType))
            );
        }
    }

    /** One physical file produced for a downloaded media item. */
    public record DownloadedFile(
            Path path,
            String fileRole,
            String format,
            String mimeType
    ) {
        public DownloadedFile {
            Objects.requireNonNull(path, "Downloaded file path cannot be null");
            fileRole = fileRole == null || fileRole.isBlank() ? "MEDIA" : fileRole;
        }

        public DownloadedFile(Path path) {
            this(path, "MEDIA", null, null);
        }
    }
}
