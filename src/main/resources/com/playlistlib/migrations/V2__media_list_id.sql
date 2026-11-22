-- Adds the lookup index used when displaying media belonging to a list.
-- DatabaseManager adds media.list_id first when upgrading an older V1 database.
CREATE INDEX IF NOT EXISTS idx_media_list_id
    ON media(list_id);

UPDATE schema_version
SET version = 2
WHERE id = 1;
