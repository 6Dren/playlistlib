-- Remove the temporary table from previous database tests.
DROP TABLE IF EXISTS connection_test;

-- Tracks the database schema version.
CREATE TABLE schema_version (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version INTEGER NOT NULL CHECK (version >= 1)
);


INSERT INTO schema_version (id, version)
VALUES (1, 1);


-- One record per physical list directory.
CREATE TABLE media_lists (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    directory_path TEXT NOT NULL UNIQUE,
    manifest_version INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT (
        strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    ),
    updated_at TEXT NOT NULL DEFAULT (
        strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    )
);


-- One record per library media item.
-- Duplicate downloads are separate records.
CREATE TABLE media (
    id TEXT PRIMARY KEY NOT NULL,
    list_id TEXT NOT NULL,

    title TEXT NOT NULL CHECK (length(trim(title)) > 0),

    source_url TEXT,
    source_id TEXT,
    extractor TEXT,
    duration_seconds REAL CHECK (
        duration_seconds IS NULL OR duration_seconds >= 0
    ),
    metadata_json TEXT,

    created_at TEXT NOT NULL DEFAULT (
        strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    ),
    updated_at TEXT NOT NULL DEFAULT (
        strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    ),


    FOREIGN KEY (list_id)
        REFERENCES media_lists(id)
        ON DELETE CASCADE,

    UNIQUE (id, list_id)

);


-- Physical files belonging to a media item.
-- Paths are relative to the containing list directory.
CREATE TABLE media_files (
    id TEXT PRIMARY KEY NOT NULL,
    media_id TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    file_role TEXT NOT NULL DEFAULT 'MEDIA',
    format TEXT,
    mime_type TEXT,
    file_size INTEGER CHECK (
        file_size IS NULL OR file_size >= 0
    ),
    checksum TEXT,
    added_at TEXT NOT NULL DEFAULT (
        strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    ),

    FOREIGN KEY (media_id)
        REFERENCES media(id)
        ON DELETE CASCADE,

    UNIQUE (media_id, relative_path)
);

-- CREATE INDEX idx_media_list_id
--     ON media(list_id);


-- Ordered contents of a list.
-- Each entry represents either a media item or a nested list.
CREATE TABLE list_entries (
    id TEXT PRIMARY KEY NOT NULL,
    list_id TEXT NOT NULL,
    entry_type TEXT NOT NULL CHECK (
        entry_type IN ('MEDIA', 'LIST')
    ),
    media_id TEXT,
    child_list_id TEXT,
    position INTEGER NOT NULL CHECK (position >= 0),

    FOREIGN KEY (list_id)
        REFERENCES media_lists(id)
        ON DELETE CASCADE,

    FOREIGN KEY (media_id)
        REFERENCES media(id)
        ON DELETE CASCADE,

    FOREIGN KEY (child_list_id)
        REFERENCES media_lists(id)
        ON DELETE CASCADE,

    CHECK (
        (entry_type = 'MEDIA'
            AND media_id IS NOT NULL
            AND child_list_id IS NULL)
        OR
        (entry_type = 'LIST'
            AND media_id IS NULL
            AND child_list_id IS NOT NULL)
    ),

    CHECK (child_list_id IS NULL OR child_list_id <> list_id),

    UNIQUE (list_id, position),
    UNIQUE (media_id),
    UNIQUE (child_list_id)
);

CREATE INDEX idx_media_list_id
    ON media(list_id);


-- Useful indexes for common queries.
CREATE INDEX idx_media_title
    ON media(title COLLATE NOCASE);

CREATE INDEX idx_media_source
    ON media(source_id, extractor);

CREATE INDEX idx_media_files_media_id
    ON media_files(media_id);

CREATE INDEX idx_list_entries_list_id
    ON list_entries(list_id);
