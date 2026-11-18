package com.playlistlib.database;

import com.playlistlib.util.YtdlpLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public final class DatabaseManager {

    private static final String DATABASE_NAME = "library.db";

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(
                    1,
                    "/com/playlistlib/migrations/V1__initial_schema.sql"
            )
    );

    private DatabaseManager() {
    }

    /*
     * Represents one database migration.
     */
    private record Migration(int version, String resource) {
    }


    /*
     * Returns the location of library.db.
     */
    public static Path getDatabasePath() throws IOException {

        Path root = YtdlpLocator.getProjectRootDirectory();

        Files.createDirectories(root);

        return root.resolve(DATABASE_NAME);
    }


    /*
     * Creates and configures a database connection.
     */
    public static Connection getConnection()
            throws SQLException, IOException {

        Path databasePath = getDatabasePath();

        String url = "jdbc:sqlite:" + databasePath.toAbsolutePath();

        Connection connection = DriverManager.getConnection(url);

        boolean configured = false;

        try (Statement statement = connection.createStatement()) {

            // Enable foreign-key enforcement for this connection.
            statement.execute("PRAGMA foreign_keys = ON");

            // Wait up to 5 seconds if another connection holds a lock.
            statement.execute("PRAGMA busy_timeout = 5000");

            configured = true;

            return connection;

        } finally {

            if (!configured) {
                connection.close();
            }
        }
    }


    /*
     * Initializes the database and applies missing migrations.
     */
    public static void initialize()
            throws SQLException, IOException {

        Path databasePath = getDatabasePath();

        try (Connection connection = getConnection()) {

            int schemaVersion = getSchemaVersion(connection);

            int latestVersion =
                    MIGRATIONS.get(MIGRATIONS.size() - 1).version();

            // The database was created by a newer application version.
            if (schemaVersion > latestVersion) {

                throw new SQLException(
                        "Database schema is newer than this application. "
                        + "Current version: " + schemaVersion
                        + ", supported version: " + latestVersion
                );
            }

            // Apply all migrations that have not been executed yet.
            for (Migration migration : MIGRATIONS) {

                if (schemaVersion < migration.version()) {

                    applyMigration(connection, migration);

                    schemaVersion = getSchemaVersion(connection);
                }
            }

            if (schemaVersion != latestVersion) {

                throw new SQLException(
                        "Database initialization incomplete. "
                        + "Current schema version: " + schemaVersion
                        + ", expected: " + latestVersion
                );
            }

            // Display database information.
            try (Statement statement = connection.createStatement();
                 ResultSet result =
                         statement.executeQuery("SELECT sqlite_version()")) {

                if (result.next()) {

                    System.out.println(
                            "SQLite version: " + result.getString(1)
                    );
                }
            }

            System.out.println("Database location: " + databasePath);
            System.out.println("Database schema version: " + schemaVersion);
        }
    }


    /*
     * Reads the current database schema version.
     *
     * If schema_version does not exist, this is a new database.
     */
    private static int getSchemaVersion(Connection connection)
            throws SQLException {

        String checkTable = """
                SELECT 1
                FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'schema_version'
                """;

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(checkTable)) {

            if (!result.next()) {
                return 0;
            }
        }

        // The schema_version table exists. Read its version.
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT version FROM schema_version WHERE id = 1")) {

            if (!result.next()) {

                throw new SQLException(
                        "The schema_version table exists, "
                        + "but its version record is missing."
                );
            }

            return result.getInt("version");
        }
    }


    /*
     * Executes a migration inside a transaction.
     */
    private static void applyMigration(
            Connection connection,
            Migration migration
    ) throws SQLException, IOException {

        boolean previousAutoCommit = connection.getAutoCommit();

        connection.setAutoCommit(false);

        try {

            System.out.println(
                    "Applying database migration V"
                    + migration.version()
            );

            SqlScriptRunner.execute(
                    connection,
                    migration.resource()
            );

            // Verify that the script updated the schema version.
            int newVersion = getSchemaVersion(connection);

            if (newVersion != migration.version()) {

                throw new SQLException(
                        "Migration V" + migration.version()
                        + " did not update the schema version correctly. "
                        + "Found version: " + newVersion
                );
            }

            connection.commit();

            System.out.println(
                    "Migration V" + migration.version()
                    + " completed successfully."
            );

        } catch (SQLException | IOException | RuntimeException e) {

            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }

            throw e;

        } finally {

            connection.setAutoCommit(previousAutoCommit);
        }
    }
}
