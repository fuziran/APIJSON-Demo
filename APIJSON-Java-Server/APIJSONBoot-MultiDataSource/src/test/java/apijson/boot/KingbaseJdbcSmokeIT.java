package apijson.boot;

import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Direct JDBC smoke tests against the three KingbaseES compatibility modes.
 *
 * <p>Run explicitly with {@code mvn -Pkingbase-it verify}. Connection settings
 * are read only from the KINGBASE_* environment variables, so this test is not
 * part of the default build.</p>
 */
public class KingbaseJdbcSmokeIT {
    private static final String TABLE = "apijson_kb_smoke";

    private enum Mode {
        MYSQL("KINGBASE_MYSQL", "mysql", "`", "KINGBASE_MYSQL"),
        ORACLE("KINGBASE_ORACLE", "oracle", "\"", "KINGBASE_ORACLE"),
        SQLSERVER("KINGBASE_SQLSERVER", "sqlserver", "\"", "KINGBASE_SQLSERVER");

        private final String database;
        private final String serverMode;
        private final String quote;
        private final String environmentPrefix;

        Mode(String database, String serverMode, String quote, String environmentPrefix) {
            this.database = database;
            this.serverMode = serverMode;
            this.quote = quote;
            this.environmentPrefix = environmentPrefix;
        }

        private String identifier(String value) {
            return quote + value + quote;
        }
    }

    @BeforeClass
    public static void loadDriver() throws ClassNotFoundException {
        Class.forName("com.kingbase8.Driver");
    }

    @Test
    public void mysqlCompatibilityMode() throws Exception {
        runMode(Mode.MYSQL);
    }

    @Test
    public void oracleCompatibilityMode() throws Exception {
        runMode(Mode.ORACLE);
    }

    @Test
    public void sqlServerCompatibilityMode() throws Exception {
        runMode(Mode.SQLSERVER);
    }

    private void runMode(Mode mode) throws Exception {
        String url = required(mode.environmentPrefix + "_URL");
        String username = required(mode.environmentPrefix + "_USERNAME");
        String password = required(mode.environmentPrefix + "_PASSWORD");
        Connection connection = null;

        try {
            connection = DriverManager.getConnection(url, username, password);
            assertFalse(mode.database + " connection must be open", connection.isClosed());
            verifyEnvironment(connection, mode);
            selectOne(connection);
            dropTable(connection, mode);
            createTable(connection, mode);

            long firstId = insert(connection, mode, "alpha", 10);
            insert(connection, mode, "beta", 20);
            insert(connection, mode, "gamma", 30);
            insert(connection, mode, "delta", 40);

            verifySelectAndResultMetadata(connection, mode);
            verifyPagination(connection, mode);
            update(connection, mode, firstId);
            delete(connection, mode);
            verifyTransactions(connection, mode);
            verifyScrollableResultSet(connection, mode);
        } finally {
            if (connection != null) {
                try {
                    dropTable(connection, mode);
                } finally {
                    connection.close();
                    assertTrue(mode.database + " connection must be closed", connection.isClosed());
                }
            }
        }
    }

    private void verifyEnvironment(Connection connection, Mode mode) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        assertNotNull(metadata.getDatabaseProductName());
        assertNotNull(metadata.getDatabaseProductVersion());
        assertNotNull(metadata.getDriverName());
        assertNotNull(metadata.getDriverVersion());

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW database_mode")) {
            assertTrue("SHOW database_mode returned no row", result.next());
            assertEquals("Connected to the wrong Kingbase compatibility mode",
                    mode.serverMode, result.getString(1).trim().toLowerCase(Locale.ROOT));
        }
    }

    private void selectOne(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    private void createTable(Connection connection, Mode mode) throws SQLException {
        String textType = mode == Mode.ORACLE ? "VARCHAR2(128)" : "VARCHAR(128)";
        String identity = mode == Mode.SQLSERVER
                ? " BIGINT IDENTITY(1,1) PRIMARY KEY"
                : " BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
        String sql = "CREATE TABLE " + mode.identifier(TABLE) + " ("
                + mode.identifier("id") + identity + ", "
                + mode.identifier("name") + " " + textType + " NOT NULL, "
                + mode.identifier("quantity") + " INTEGER NOT NULL)";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void dropTable(Connection connection, Mode mode) throws SQLException {
        String sql = "DROP TABLE " + (mode == Mode.ORACLE ? "" : "IF EXISTS ")
                + mode.identifier(TABLE);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException error) {
            if (mode != Mode.ORACLE || !isMissingTable(error)) {
                throw error;
            }
        }
    }

    private long insert(Connection connection, Mode mode, String name, int quantity)
            throws SQLException {
        String sql = "INSERT INTO " + mode.identifier(TABLE) + " ("
                + mode.identifier("name") + ", " + mode.identifier("quantity")
                + ") VALUES (?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"})) {
            verifyParameterMetadata(statement.getParameterMetaData(), 2);
            statement.setString(1, name);
            statement.setInt(2, quantity);

            if (mode == Mode.SQLSERVER) {
                assertTrue("SQL Server-compatible identity insert must return a result set",
                        statement.execute());
                try (ResultSet result = statement.getResultSet()) {
                    assertNotNull(result);
                    assertTrue("Identity insert returned no generated key", result.next());
                    verifyResultMetadata(result.getMetaData(), 1);
                    return ((Number) result.getObject(1)).longValue();
                }
            }

            assertEquals(1, statement.executeUpdate());
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue("getGeneratedKeys returned no row", keys.next());
                verifyResultMetadata(keys.getMetaData(), 1);
                return ((Number) keys.getObject(1)).longValue();
            }
        }
    }

    private void verifySelectAndResultMetadata(Connection connection, Mode mode)
            throws SQLException {
        String sql = "SELECT " + mode.identifier("id") + ", " + mode.identifier("name")
                + ", " + mode.identifier("quantity") + " FROM " + mode.identifier(TABLE)
                + " ORDER BY " + mode.identifier("id");
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql);
        int rowCount = 0;

        try {
            verifyResultMetadata(result.getMetaData(), 3);
            while (result.next()) {
                rowCount++;
            }
            assertEquals(4, rowCount);
        } finally {
            result.close();
            statement.close();
        }

        assertTrue("ResultSet must report closed", result.isClosed());
        assertTrue("Statement must report closed", statement.isClosed());
    }

    private void verifyPagination(Connection connection, Mode mode) throws SQLException {
        String base = "SELECT " + mode.identifier("id") + ", " + mode.identifier("name")
                + " FROM " + mode.identifier(TABLE) + " ORDER BY " + mode.identifier("id");
        String sql;
        if (mode == Mode.MYSQL) {
            sql = base + " LIMIT 2 OFFSET 1";
        } else if (mode == Mode.ORACLE) {
            sql = "SELECT * FROM (SELECT apijson_page_.*, ROWNUM \"RN\" FROM ("
                    + base + ") apijson_page_ WHERE ROWNUM <= 3) WHERE \"RN\" > 1";
        } else {
            sql = base + " OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY";
        }

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            int rowCount = 0;
            while (result.next()) {
                rowCount++;
            }
            assertEquals(2, rowCount);
        }
    }

    private void update(Connection connection, Mode mode, long id) throws SQLException {
        String sql = "UPDATE " + mode.identifier(TABLE) + " SET "
                + mode.identifier("quantity") + " = ? WHERE " + mode.identifier("id") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            verifyParameterMetadata(statement.getParameterMetaData(), 2);
            statement.setInt(1, 11);
            statement.setLong(2, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void delete(Connection connection, Mode mode) throws SQLException {
        String sql = "DELETE FROM " + mode.identifier(TABLE) + " WHERE "
                + mode.identifier("name") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            verifyParameterMetadata(statement.getParameterMetaData(), 1);
            statement.setString(1, "delta");
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void verifyTransactions(Connection connection, Mode mode) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            insert(connection, mode, "committed", 50);
            connection.commit();
            insert(connection, mode, "rolled-back", 60);
            connection.rollback();
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }

        assertEquals(1, countByName(connection, mode, "committed"));
        assertEquals(0, countByName(connection, mode, "rolled-back"));
    }

    private int countByName(Connection connection, Mode mode, String name) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + mode.identifier(TABLE) + " WHERE "
                + mode.identifier("name") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private void verifyScrollableResultSet(Connection connection, Mode mode) throws SQLException {
        String sql = "SELECT " + mode.identifier("id") + " FROM " + mode.identifier(TABLE)
                + " ORDER BY " + mode.identifier("id");
        try (Statement statement = connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet result = statement.executeQuery(sql)) {
            assertEquals("Driver did not create a scrollable ResultSet",
                    ResultSet.TYPE_SCROLL_INSENSITIVE, result.getType());
            assertTrue("Scrollable ResultSet could not move to the last row", result.last());
            assertTrue(result.getRow() > 0);
            assertTrue("Scrollable ResultSet could not move to the first row", result.first());
        }
    }

    private void verifyParameterMetadata(ParameterMetaData metadata, int expectedCount)
            throws SQLException {
        assertNotNull("ParameterMetaData must be available", metadata);
        assertEquals(expectedCount, metadata.getParameterCount());
        for (int index = 1; index <= expectedCount; index++) {
            metadata.getParameterType(index);
            assertNotNull(metadata.getParameterTypeName(index));
            assertNotNull(metadata.getParameterClassName(index));
        }
    }

    private void verifyResultMetadata(ResultSetMetaData metadata, int expectedCount)
            throws SQLException {
        assertNotNull("ResultSetMetaData must be available", metadata);
        assertEquals(expectedCount, metadata.getColumnCount());
        for (int index = 1; index <= expectedCount; index++) {
            assertNotNull(metadata.getColumnLabel(index));
            metadata.getColumnType(index);
            assertNotNull(metadata.getColumnTypeName(index));
            assertNotNull(metadata.getColumnClassName(index));
        }
    }

    private boolean isMissingTable(SQLException error) {
        String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        return "42P01".equals(error.getSQLState())
                || message.contains("does not exist")
                || message.contains("不存在");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required environment variable is empty: " + name);
        }
        return value;
    }
}
