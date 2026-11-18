
package com.playlistlib.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqlScriptRunner {

    private SqlScriptRunner() {
    }

    public static void execute(Connection connection, String resource)
            throws IOException, SQLException {

        try (InputStream input =
                     SqlScriptRunner.class.getResourceAsStream(resource)) {

            if (input == null) {
                throw new IOException(
                        "SQL resource not found: " + resource
                );
            }

            String script = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );

            try (Statement statement = connection.createStatement()) {

                // Initial migration uses semicolon-separated
                for (String sql : script.split(";")) {

                    sql = sql.trim();

                    if (!sql.isEmpty()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }
}
