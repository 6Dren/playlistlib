package com.playlistlib.service;

import com.playlistlib.util.YtdlpLocator;

import com.playlistlib.util.ListManifestReader;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.UUID;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

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
                new ListManifestWriter());
    }

    public LibraryService(
            MediaListRepository mediaListRepository,
            MediaRepository mediaRepository,
            MediaFileRepository mediaFileRepository,
            ListEntryRepository listEntryRepository,
            ListManifestWriter manifestWriter) {
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
            List<DownloadedMedia> downloadedMedia) throws SQLException, IOException {
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
                            item.metadataJson());

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
                                Instant.now());
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
            List<ListEntryRepository.Entry> entries = listEntryRepository.findByListId(connection, listId);

            Map<String, List<MediaFile>> filesByMediaId = new LinkedHashMap<>();
            for (Media media : mediaItems) {
                filesByMediaId.put(
                        media.getId(),
                        mediaFileRepository.findByMediaId(connection, media.getId()));
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
                now);
    }

    public MediaList importList(Path directory) throws IOException, SQLException {

        Path rootDirectory = normalizeDirectory(directory).toRealPath();

        // Find all manifests inside the selected directory.
        List<Path> manifestPaths;

        try (Stream<Path> paths = Files.walk(rootDirectory)) {

            manifestPaths = paths
                    .filter(path -> path.getFileName() != null
                            && ListManifestWriter.MANIFEST_FILE_NAME.equals(
                                    path.getFileName().toString()))
                    .filter(path -> !Files.isSymbolicLink(path)
                            && Files.isRegularFile(path))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        Map<Path, ImportedList> listsByDirectory = new LinkedHashMap<>();
        Map<String, ImportedList> listsById = new LinkedHashMap<>();

        // Read all manifests, including nested lists.
        for (Path manifestPath : manifestPaths) {

            Path listDirectory = manifestPath.getParent().toRealPath();

            ListManifestReader.ManifestData manifest = new ListManifestReader(listDirectory).readManifest();

            ImportedList imported = new ImportedList(listDirectory, manifest);

            if (listsByDirectory.putIfAbsent(listDirectory, imported) != null) {
                throw new IOException("Duplicate list directory: " + listDirectory);
            }

            if (listsById.putIfAbsent(manifest.list().getId(), imported) != null) {
                throw new IOException(
                        "Duplicate list ID: " + manifest.list().getId());
            }
        }

        ImportedList rootList = listsByDirectory.get(rootDirectory);

        if (rootList == null) {
            throw new IOException("No PlaylistLib manifest found in selected directory.");
        }

        // Prepare entries, recovering missing media entries if necessary.
        Map<String, List<ListEntryRepository.Entry>> entriesByListId = new LinkedHashMap<>();

        for (ImportedList imported : listsByDirectory.values()) {

            String listId = imported.manifest().list().getId();

            List<ListEntryRepository.Entry> entries = new ArrayList<>(imported.manifest().entries());

            Set<String> enteredMediaIds = new HashSet<>();

            for (ListEntryRepository.Entry entry : entries) {
                if (entry.entryType().equals("MEDIA")) {
                    enteredMediaIds.add(entry.mediaId());
                }
            }

            for (Media media : imported.manifest().media()) {

                if (!enteredMediaIds.contains(media.getId())) {

                    entries.add(new ListEntryRepository.Entry(
                            UUID.randomUUID().toString(),
                            listId,
                            "MEDIA",
                            media.getId(),
                            null,
                            nextPosition(entries)));
                }
            }

            entriesByListId.put(listId, entries);
        }

        // Recover parent-child relationships from the directory structure.
        for (ImportedList child : listsByDirectory.values()) {

            if (child.directory().equals(rootDirectory)) {
                continue;
            }

            Path ancestor = child.directory().getParent();
            ImportedList parent = null;

            while (ancestor != null && ancestor.startsWith(rootDirectory)) {

                parent = listsByDirectory.get(ancestor);

                if (parent != null) {
                    break;
                }

                ancestor = ancestor.getParent();
            }

            if (parent == null) {
                throw new IOException(
                        "Could not determine parent of nested list: "
                                + child.directory());
            }

            String parentId = parent.manifest().list().getId();
            String childId = child.manifest().list().getId();

            List<ListEntryRepository.Entry> parentEntries = entriesByListId.get(parentId);

            boolean alreadyLinked = parentEntries.stream().anyMatch(entry -> entry.entryType().equals("LIST")
                    && childId.equals(entry.childListId()));

            if (!alreadyLinked) {

                parentEntries.add(new ListEntryRepository.Entry(
                        UUID.randomUUID().toString(),
                        parentId,
                        "LIST",
                        null,
                        childId,
                        nextPosition(parentEntries)));
            }
        }

        // Validate file paths before inserting anything.
        for (ImportedList imported : listsByDirectory.values()) {
            for (MediaFile file : imported.manifest().files()) {
                validateImportedFilePath(
                        imported.directory(),
                        file.getRelativePath());
            }
        }

        // Import everything within one transaction.
        try (Connection connection = DatabaseManager.getConnection()) {

            connection.setAutoCommit(false);

            try {

                // Insert all lists first.
                for (ImportedList imported : listsByDirectory.values()) {

                    MediaList list = imported.manifest().list();

                    Optional<MediaList> existingById = mediaListRepository.findById(connection, list.getId());

                    Optional<MediaList> existingByPath = mediaListRepository.findByDirectoryPath(
                            connection,
                            imported.directory().toString());

                    if (existingByPath.isPresent()
                            && !existingByPath.get().getId().equals(list.getId())) {

                        throw new SQLException(
                                "Directory already belongs to another list: "
                                        + imported.directory());
                    }

                    if (existingById.isPresent()
                            && !existingById.get().getDirectoryPath().equals(
                                    imported.directory().toString())) {

                        Path oldDirectory = Path.of(
                                existingById.get().getDirectoryPath());

                        if (Files.exists(oldDirectory)) {
                            throw new SQLException(
                                    "This list ID is already registered elsewhere: "
                                            + oldDirectory);
                        }

                        mediaListRepository.updateDirectoryPath(
                                connection,
                                list.getId(),
                                imported.directory().toString());
                    }

                    if (existingById.isEmpty() && existingByPath.isEmpty()) {
                        mediaListRepository.insert(connection, list);
                    }
                }

                // Insert media and their physical-file records.
                for (ImportedList imported : listsByDirectory.values()) {

                    for (Media media : imported.manifest().media()) {

                        Optional<Media> existingMedia = mediaRepository.findById(connection, media.getId());

                        if (existingMedia.isPresent()
                                && !existingMedia.get().getListId().equals(media.getListId())) {

                            throw new SQLException(
                                    "Media ID already belongs to another list: "
                                            + media.getId());
                        }

                        if (existingMedia.isEmpty()) {
                            mediaRepository.insert(connection, media);
                        }

                        for (MediaFile file : imported.manifest().files()) {

                            if (!file.getMediaId().equals(media.getId())) {
                                continue;
                            }

                            Optional<MediaFile> existingFile = mediaFileRepository.findById(connection, file.getId());

                            if (existingFile.isPresent()) {
                                continue;
                            }

                            boolean samePathExists = mediaFileRepository.findByMediaId(
                                    connection, media.getId())
                                    .stream()
                                    .anyMatch(existing -> existing.getRelativePath().equals(
                                            file.getRelativePath()));

                            if (!samePathExists) {
                                mediaFileRepository.insert(connection, file);
                            }
                        }
                    }
                }

                // Restore ordered media and nested-list relationships.
                for (Map.Entry<String, List<ListEntryRepository.Entry>> item : entriesByListId.entrySet()) {

                    insertMissingEntries(
                            connection,
                            item.getKey(),
                            item.getValue());
                }

                connection.commit();

            } catch (SQLException | IOException | RuntimeException exception) {

                rollback(connection, exception);
                throw exception;
            }
        }

        System.out.println(
                "Imported " + listsByDirectory.size() + " list(s) and "
                        + listsByDirectory.values().stream()
                                .mapToInt(item -> item.manifest().media().size())
                                .sum()
                        + " media item(s).");

        return rootList.manifest().list();
    }

    public void removeList(MediaList mediaList)
            throws SQLException, IOException {

        Objects.requireNonNull(mediaList, "Media list cannot be null");

        try (Connection connection = DatabaseManager.getConnection()) {

            connection.setAutoCommit(false);

            try {

                mediaListRepository.removeListFromDatabase(
                        connection,
                        mediaList);

                connection.commit();

            } catch (SQLException | RuntimeException exception) {

                rollback(connection, exception);
                throw exception;
            }
        }
    }

    public void removeListPhysically(MediaList mediaList)
            throws SQLException, IOException {

        Objects.requireNonNull(mediaList, "Media list cannot be null");

        // Get the current database record instead of relying on a possibly
        // outdated MediaList object from the UI.
        MediaList registeredList;

        try (Connection connection = DatabaseManager.getConnection()) {

            registeredList = mediaListRepository.findById(
                    connection,
                    mediaList.getId()).orElseThrow(
                            () -> new SQLException(
                                    "Media list not found: " + mediaList.getId()));
        }

        Path directory = Path.of(
                registeredList.getDirectoryPath()).toAbsolutePath().normalize();

        validateDeletionDirectory(directory);

        // Delete the physical directory, if it still exists.
        // If it was already deleted, we can still clean up its DB records.
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {

            if (Files.isSymbolicLink(directory)) {
                throw new IOException(
                        "Refusing to delete a list directory that is a symbolic link.");
            }

            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "The registered list path is not a directory: " + directory);
            }

            deleteDirectoryRecursively(directory);
        }

        reconcileLists();

    }

    private void deleteDirectoryRecursively(Path directory)
            throws IOException {

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes) throws IOException {

                Files.delete(file);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path dir,
                    IOException exception) throws IOException {

                if (exception != null) {
                    throw exception;
                }

                Files.delete(dir);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    public int reconcileLists() throws SQLException, IOException {

        int removedLists = 0;

        try (Connection connection = DatabaseManager.getConnection()) {

            connection.setAutoCommit(false);

            try {

                List<MediaList> lists = mediaListRepository.findAll(connection);

                for (MediaList list : lists) {

                    Path directory = Path.of(
                            list.getDirectoryPath()).toAbsolutePath().normalize();

                    // Only remove a registration when the path
                    // is confirmed not to exist.
                    if (Files.notExists(directory)) {

                        mediaListRepository.removeListFromDatabase(
                                connection,
                                list);

                        removedLists++;
                    }
                }

                connection.commit();

            } catch (SQLException | RuntimeException exception) {

                rollback(connection, exception);
                throw exception;
            }
        }

        if (removedLists > 0) {
            System.out.println(
                    "Removed " + removedLists
                            + " missing list registration(s).");
        }

        return removedLists;
    }

    private void validateDeletionDirectory(Path directory)
            throws IOException {

        Path filesystemRoot = directory.getRoot();

        Path projectRoot = YtdlpLocator.getProjectRootDirectory()
                .toAbsolutePath()
                .normalize();

        Path homeDirectory = Path.of(
                System.getProperty("user.home")).toAbsolutePath().normalize();

        if (directory.equals(filesystemRoot)
                || directory.equals(projectRoot)
                || projectRoot.startsWith(directory)
                || directory.equals(homeDirectory)
                || homeDirectory.startsWith(directory)) {

            throw new IOException(
                    "Refusing to delete a protected directory: " + directory);
        }
    }

    private int nextPosition(List<ListEntryRepository.Entry> entries) {

        return entries.stream()
                .mapToInt(ListEntryRepository.Entry::position)
                .max()
                .orElse(-1) + 1;
    }

    private void validateImportedFilePath(
            Path listDirectory,
            String relativePath) throws IOException {

        Path relative = Path.of(
                relativePath.replace('\\', '/')).normalize();

        if (relative.isAbsolute()
                || relative.startsWith("..")
                || relative.toString().isBlank()) {

            throw new IOException(
                    "Invalid media file path in manifest: " + relativePath);
        }

        Path resolved = listDirectory.resolve(relative).normalize();

        if (!resolved.startsWith(listDirectory)) {
            throw new IOException(
                    "Media file path escapes its list directory: " + relativePath);
        }

        // Do not allow an existing symlink to reference a file outside the list.
        if (Files.exists(resolved)) {

            if (!Files.isRegularFile(resolved)) {
                throw new IOException("Not a regular media file: " + resolved);
            }

            if (!resolved.toRealPath().startsWith(listDirectory.toRealPath())) {
                throw new IOException(
                        "Media file resolves outside its list directory: " + resolved);
            }
        }
    }

    /**
     * Removes one media item from its list manifest and then from SQLite.
     * Physical files are kept unless deletePhysicalFiles is true.
     */
    public void removeMedia(Media media, boolean deletePhysicalFiles)
            throws SQLException, IOException {

        Objects.requireNonNull(media, "Media cannot be null");

        Media registeredMedia;
        MediaList registeredList;

        // Verify that the selected media still exists in the database.
        try (Connection connection = DatabaseManager.getConnection()) {

            registeredMedia = mediaRepository.findById(connection, media.getId())
                    .orElseThrow(() -> new SQLException(
                            "Media not found: " + media.getId()));

            registeredList = mediaListRepository.findById(
                    connection, registeredMedia.getListId())
                    .orElseThrow(() -> new SQLException(
                            "List not found: " + registeredMedia.getListId()));
        }

        if (!registeredMedia.getListId().equals(media.getListId())) {
            throw new SQLException("The selected media no longer belongs to this list.");
        }

        Path directory = Path.of(registeredList.getDirectoryPath())
                .toAbsolutePath().normalize();

        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "The list directory is missing or is not a regular directory: "
                            + directory);
        }

        Path realDirectory = directory.toRealPath();

        // Read the current manifest.
        ListManifestReader.ManifestData originalManifest = new ListManifestReader(realDirectory).readManifest();

        if (!originalManifest.list().getId().equals(registeredList.getId())) {
            throw new IOException("The manifest does not belong to the selected list.");
        }

        // Ensure that the selected media exists in the manifest.
        boolean mediaExistsInManifest = originalManifest.media().stream()
                .anyMatch(item -> item.getId().equals(registeredMedia.getId()));

        if (!mediaExistsInManifest) {
            throw new IOException(
                    "The selected media is not present in the list manifest.");
        }

        // Get all physical files associated with this media.
        List<MediaFile> filesToRemove = originalManifest.files().stream()
                .filter(file -> file.getMediaId().equals(registeredMedia.getId()))
                .toList();

        // Resolve and validate physical paths only when necessary.
        List<Path> physicalFiles = deletePhysicalFiles
                ? resolveMediaFilesForDeletion(realDirectory, filesToRemove)
                : List.of();

        // Never delete a physical file that another media item also references.
        if (deletePhysicalFiles) {

            Set<String> selectedPaths = filesToRemove.stream()
                    .map(file -> file.getRelativePath().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());

            boolean sharedByAnotherMedia = originalManifest.files().stream()
                    .filter(file -> !file.getMediaId().equals(registeredMedia.getId()))
                    .anyMatch(file -> selectedPaths.contains(
                            file.getRelativePath().replace('\\', '/')));

            if (sharedByAnotherMedia) {
                throw new IOException(
                        "At least one physical file is also registered to another media item. "
                                + "Remove it from the other media first, or use database-only removal.");
            }
        }

        if (deletePhysicalFiles && physicalFiles.isEmpty()) {
            throw new IOException(
                    "The manifest contains no physical file records for this media. "
                            + "The files cannot be safely identified for deletion.");
        }

        Instant updatedAt = Instant.now();

        MediaList updatedManifestList = new MediaList(
                originalManifest.list().getId(),
                originalManifest.list().getName(),
                realDirectory.toString(),
                originalManifest.list().getManifestVersion(),
                originalManifest.list().getCreatedAt(),
                updatedAt);

        // Remove the selected media from the manifest's media array.
        List<Media> remainingMedia = originalManifest.media().stream()
                .filter(item -> !item.getId().equals(registeredMedia.getId()))
                .toList();

        // Remove its associated file metadata.
        List<MediaFile> remainingFiles = originalManifest.files().stream()
                .filter(file -> !file.getMediaId().equals(registeredMedia.getId()))
                .toList();

        // Remove only its MEDIA entry.
        // LIST entries representing nested lists remain untouched.
        List<ListEntryRepository.Entry> remainingEntries = originalManifest.entries().stream()
                .filter(entry -> !(entry.entryType().equals("MEDIA")
                        && registeredMedia.getId().equals(entry.mediaId())))
                .toList();

        // For physical removal, delete only the files registered in the manifest.
        // This happens before metadata removal so a filesystem error leaves the
        // database and manifest entries available for another attempt.
        if (deletePhysicalFiles) {
            for (Path file : physicalFiles) {
                Files.deleteIfExists(file);
            }
        }

        // Update the manifest first. The database transaction mirrors this change.
        boolean manifestUpdated = false;

        try (Connection connection = DatabaseManager.getConnection()) {

            connection.setAutoCommit(false);

            try {
                manifestWriter.write(
                        updatedManifestList,
                        remainingMedia,
                        groupFilesByMediaId(remainingFiles),
                        remainingEntries);

                manifestUpdated = true;

                boolean deleted = mediaRepository.deleteById(
                        connection, registeredMedia.getId());

                if (!deleted) {
                    throw new SQLException(
                            "Media not found while updating the database: "
                                    + registeredMedia.getId());
                }

                // Update the list's modification timestamp.
                mediaListRepository.touch(
                        connection, registeredList.getId(), updatedAt);

                connection.commit();

            } catch (SQLException | IOException | RuntimeException exception) {

                rollback(connection, exception);

                // Restore the original manifest if the database transaction fails.
                if (manifestUpdated) {
                    try {
                        manifestWriter.write(
                                originalManifest.list(),
                                originalManifest.media(),
                                groupFilesByMediaId(originalManifest.files()),
                                originalManifest.entries());

                    } catch (IOException restoreException) {
                        exception.addSuppressed(restoreException);
                    }
                }

                throw exception;
            }
        }
    }

    private Map<String, List<MediaFile>> groupFilesByMediaId(
            List<MediaFile> files) {

        Map<String, List<MediaFile>> filesByMediaId = new LinkedHashMap<>();

        for (MediaFile file : files) {
            filesByMediaId.computeIfAbsent(
                    file.getMediaId(), ignored -> new ArrayList<>()).add(file);
        }

        return filesByMediaId;
    }

    private List<Path> resolveMediaFilesForDeletion(
            Path listDirectory,
            List<MediaFile> files) throws IOException {

        Path realDirectory = listDirectory.toRealPath();
        Set<Path> paths = new LinkedHashSet<>();

        for (MediaFile file : files) {

            String portablePath = file.getRelativePath().replace('\\', '/');
            Path relative = Path.of(portablePath).normalize();

            if (portablePath.isBlank()
                    || portablePath.startsWith("/")
                    || portablePath.matches("^[A-Za-z]:.*")
                    || relative.isAbsolute()
                    || relative.startsWith("..")
                    || relative.toString().isBlank()) {

                throw new IOException(
                        "Invalid media path in manifest: " + file.getRelativePath());
            }

            Path target = realDirectory.resolve(relative).normalize();

            if (!target.startsWith(realDirectory)) {
                throw new IOException(
                        "Media path escapes the list directory: "
                                + file.getRelativePath());
            }

            // Check every path component for symbolic links.
            Path current = realDirectory;

            for (Path component : relative) {
                current = current.resolve(component);

                if (Files.isSymbolicLink(current)) {
                    throw new IOException(
                            "Refusing to delete a media file through a symbolic link: "
                                    + current);
                }
            }

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {

                throw new IOException("Not a regular media file: " + target);
            }

            if (Files.exists(target)) {
                Path realTarget = target.toRealPath();

                if (!realTarget.startsWith(realDirectory)) {
                    throw new IOException(
                            "Media file resolves outside the list directory: " + target);
                }
            }

            paths.add(target);
        }

        return List.copyOf(paths);
    }

    private void insertMissingEntries(
            Connection connection,
            String listId,
            List<ListEntryRepository.Entry> importedEntries)
            throws SQLException, IOException {

        List<ListEntryRepository.Entry> existingEntries = listEntryRepository.findByListId(connection, listId);

        for (ListEntryRepository.Entry imported : importedEntries) {

            boolean sameId = existingEntries.stream()
                    .anyMatch(existing -> existing.id().equals(imported.id()));

            if (sameId) {
                continue;
            }

            boolean sameRelationship = existingEntries.stream()
                    .anyMatch(existing -> existing.entryType().equals(imported.entryType())
                            && Objects.equals(existing.mediaId(), imported.mediaId())
                            && Objects.equals(existing.childListId(), imported.childListId()));

            if (sameRelationship) {
                continue;
            }

            boolean positionOccupied = existingEntries.stream()
                    .anyMatch(existing -> existing.position() == imported.position());

            if (positionOccupied) {
                throw new SQLException(
                        "List position is already occupied: " + imported.position());
            }

            listEntryRepository.insert(connection, imported);
            existingEntries.add(imported);
        }
    }

    private record ImportedList(
            Path directory,
            ListManifestReader.ManifestData manifest) {
    }

    private void refuseUnregisteredManifest(Path directory) throws IOException {
        Path manifest = directory.resolve(ListManifestWriter.MANIFEST_FILE_NAME);
        if (Files.exists(manifest)) {
            throw new IOException(
                    "This directory already contains a PlaylistLib manifest, but the list "
                            + "is not registered in the current database: " + manifest
                            + ". Import the existing list instead of replacing its manifest.");
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
            List<DownloadedFile> files) {
        public DownloadedMedia {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Downloaded media title cannot be blank");
            }
            if (durationSeconds != null && (!Double.isFinite(durationSeconds) || durationSeconds < 0)) {
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
                String mimeType) {
            this(
                    title,
                    sourceUrl,
                    sourceId,
                    extractor,
                    durationSeconds,
                    metadataJson,
                    List.of(new DownloadedFile(mediaFile, "MEDIA", format, mimeType)));
        }
    }

    /** One physical file produced for a downloaded media item. */
    public record DownloadedFile(
            Path path,
            String fileRole,
            String format,
            String mimeType) {
        public DownloadedFile {
            Objects.requireNonNull(path, "Downloaded file path cannot be null");
            fileRole = fileRole == null || fileRole.isBlank() ? "MEDIA" : fileRole;
        }

        public DownloadedFile(Path path) {
            this(path, "MEDIA", null, null);
        }
    }
}
