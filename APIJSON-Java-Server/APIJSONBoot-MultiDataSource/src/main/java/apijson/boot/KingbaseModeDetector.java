package apijson.boot;

import apijson.orm.SQLConfig;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Detects and validates the compatibility mode of a KingbaseES connection.
 *
 * <p>All compatibility modes use the same JDBC driver and URL prefix, so the
 * server-side {@code database_mode} setting is the authoritative value.</p>
 */
public final class KingbaseModeDetector {

    private static final String DETECT_SQL = "SHOW database_mode";

    private KingbaseModeDetector() {
    }

    public static String normalizeConfiguredDatabase(String database) {
        if (database == null) {
            throw new IllegalArgumentException(
                    "Kingbase database mode must not be null");
        }

        String normalized = database.trim().toUpperCase(Locale.ROOT);
        if (SQLConfig.DATABASE_KINGBASE_MYSQL.equals(normalized)
                || SQLConfig.DATABASE_KINGBASE_ORACLE.equals(normalized)
                || SQLConfig.DATABASE_KINGBASE_SQLSERVER.equals(normalized)) {
            return normalized;
        }

        throw new IllegalArgumentException(
                "Unsupported Kingbase database mode: " + database
                        + "; expected one of ["
                        + SQLConfig.DATABASE_KINGBASE_MYSQL + ", "
                        + SQLConfig.DATABASE_KINGBASE_ORACLE + ", "
                        + SQLConfig.DATABASE_KINGBASE_SQLSERVER + "]");
    }

    public static String fromServerMode(String serverMode) throws SQLException {
        if (serverMode == null) {
            throw new SQLException("SHOW database_mode returned null");
        }

        String normalized = serverMode.trim().toLowerCase(Locale.ROOT);
        if ("mysql".equals(normalized)) {
            return SQLConfig.DATABASE_KINGBASE_MYSQL;
        }
        if ("oracle".equals(normalized)) {
            return SQLConfig.DATABASE_KINGBASE_ORACLE;
        }
        if ("sqlserver".equals(normalized)
                || "sql_server".equals(normalized)
                || "mssql".equals(normalized)) {
            return SQLConfig.DATABASE_KINGBASE_SQLSERVER;
        }

        throw new SQLException(
                "Unsupported Kingbase database_mode returned by server: "
                        + serverMode);
    }

    public static String detect(Connection connection) throws SQLException {
        if (connection == null) {
            throw new SQLException("Kingbase connection must not be null");
        }

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(DETECT_SQL)) {
            if (!resultSet.next()) {
                throw new SQLException("SHOW database_mode returned no rows");
            }
            return fromServerMode(resultSet.getString(1));
        }
    }

    public static String verify(
            Connection connection, String expectedDatabase) throws SQLException {
        String expected = normalizeConfiguredDatabase(expectedDatabase);
        String actual = detect(connection);
        if (!expected.equals(actual)) {
            throw new SQLException(
                    "Kingbase database mode mismatch: expected "
                            + expected + " but server reported " + actual);
        }
        return actual;
    }
}
