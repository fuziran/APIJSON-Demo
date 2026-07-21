import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class KingbaseSmokeTest {
    private static final String TABLE = "apijson_kb_smoke";

    private enum Mode {
        MYSQL("KINGBASE-MYSQL", "`", "KINGBASE_MYSQL"),
        ORACLE("KINGBASE-ORACLE", "\"", "KINGBASE_ORACLE"),
        SQLSERVER("KINGBASE-SQLSERVER", "\"", "KINGBASE_SQLSERVER");

        final String database;
        final String quote;
        final String envPrefix;

        Mode(String database, String quote, String envPrefix) {
            this.database = database;
            this.quote = quote;
            this.envPrefix = envPrefix;
        }

        String id(String value) {
            return quote + value + quote;
        }
    }

    private int failures;

    public static void main(String[] args) throws Exception {
        Class.forName("com.kingbase8.Driver");
        KingbaseSmokeTest test = new KingbaseSmokeTest();
        test.line("RUN started=" + Instant.now() + " java=" + System.getProperty("java.version"));
        for (Mode mode : Mode.values()) {
            test.runMode(mode);
        }
        test.line("RUN completed=" + Instant.now() + " failures=" + test.failures);
        if (test.failures != 0) {
            System.exit(1);
        }
    }

    private void runMode(Mode mode) {
        String url = required(mode.envPrefix + "_URL");
        String username = required(mode.envPrefix + "_USERNAME");
        String password = required(mode.envPrefix + "_PASSWORD");
        line("");
        line("=== " + mode.database + " ===");
        line("CONFIG url=" + redactUrl(url) + " username=" + username);

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            pass(mode, "connect");
            recordEnvironment(connection, mode);
            executeScalar(connection, mode, "SELECT 1", "select-one");
            dropTable(connection, mode);
            createTable(connection, mode);
            long firstId = insert(connection, mode, "alpha", 10);
            insert(connection, mode, "beta", 20);
            insert(connection, mode, "gamma", 30);
            insert(connection, mode, "delta", 40);
            selectAndMetadata(connection, mode);
            paginate(connection, mode);
            update(connection, mode, firstId);
            delete(connection, mode);
            transaction(connection, mode);
            scrollableResultSet(connection, mode);
            dropTable(connection, mode);
            pass(mode, "cleanup");
        } catch (Throwable error) {
            fail(mode, "mode", error);
            try (Connection cleanup = DriverManager.getConnection(url, username, password)) {
                dropTable(cleanup, mode);
            } catch (Throwable cleanupError) {
                fail(mode, "cleanup-after-failure", cleanupError);
            }
        }
    }

    private void recordEnvironment(Connection connection, Mode mode) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        line("ENV databaseProduct=" + meta.getDatabaseProductName()
                + " databaseVersion=" + meta.getDatabaseProductVersion());
        line("ENV driver=" + meta.getDriverName() + " driverVersion=" + meta.getDriverVersion()
                + " jdbc=" + meta.getJDBCMajorVersion() + "." + meta.getJDBCMinorVersion());
        line("ENV currentCatalog=" + connection.getCatalog() + " currentSchema=" + connection.getSchema());
        executeDiagnostic(connection, "SELECT version()", "server-version");
        executeDiagnostic(connection, "SHOW database_mode", "database-mode");
        pass(mode, "environment-metadata");
    }

    private void executeDiagnostic(Connection connection, String sql, String label) {
        logSql(sql, List.of());
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            line("DIAGNOSTIC " + label + "=" + (result.next() ? result.getString(1) : "<empty>"));
        } catch (SQLException error) {
            line("DIAGNOSTIC " + label + " unavailable sqlState=" + error.getSQLState()
                    + " code=" + error.getErrorCode() + " message=" + oneLine(error.getMessage()));
        }
    }

    private void executeScalar(Connection connection, Mode mode, String sql, String label) throws SQLException {
        logSql(sql, List.of());
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            check(result.next() && result.getInt(1) == 1, mode, label, "expected scalar value 1");
        }
    }

    private void createTable(Connection connection, Mode mode) throws SQLException {
        String textType = mode == Mode.ORACLE ? "VARCHAR2(128)" : "VARCHAR(128)";
        String identity = mode == Mode.SQLSERVER
                ? " BIGINT IDENTITY(1,1) PRIMARY KEY"
                : " BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
        String sql = "CREATE TABLE " + mode.id(TABLE) + " ("
                + mode.id("id") + identity + ", "
                + mode.id("name") + " " + textType + " NOT NULL, "
                + mode.id("quantity") + " INTEGER NOT NULL)";
        executeUpdate(connection, sql, List.of());
        pass(mode, "create-table");
    }

    private void dropTable(Connection connection, Mode mode) throws SQLException {
        String sql = "DROP TABLE " + (mode == Mode.ORACLE ? "" : "IF EXISTS ") + mode.id(TABLE);
        logSql(sql, List.of());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException error) {
            if (mode != Mode.ORACLE || !isMissingTable(error)) {
                throw error;
            }
        }
    }

    private long insert(Connection connection, Mode mode, String name, int quantity) throws SQLException {
        String sql = "INSERT INTO " + mode.id(TABLE) + " (" + mode.id("name") + ", "
                + mode.id("quantity") + ") VALUES (?, ?)";
        List<Object> values = List.of(name, quantity);
        logSql(sql, values);
        PreparedStatement generatedKeyStatement = connection.prepareStatement(sql, new String[]{"id"});
        try (PreparedStatement statement = generatedKeyStatement) {
            recordParameterMetadata(statement.getParameterMetaData());
            statement.setString(1, name);
            statement.setInt(2, quantity);
            boolean hasResultSet = mode == Mode.SQLSERVER && statement.execute();
            int updateCount = mode == Mode.SQLSERVER ? statement.getUpdateCount() : statement.executeUpdate();
            line("UPDATE_COUNT operation=insert value=" + updateCount);
            line("EXECUTE_RESULT operation=insert hasResultSet=" + hasResultSet);
            if (mode != Mode.SQLSERVER) {
                check(updateCount == 1, mode, "insert-update-count", "expected one affected row");
            }
            if (mode == Mode.SQLSERVER && hasResultSet) {
                try (ResultSet directResult = statement.getResultSet()) {
                    if (directResult != null) {
                        recordResultMetadata(directResult.getMetaData());
                        if (directResult.next()) {
                            Object value = directResult.getObject(1);
                            line("DIRECT_RESULT javaType=" + className(value) + " value=" + value);
                            pass(mode, "insert-generated-key-direct-result");
                            return ((Number) value).longValue();
                        }
                    }
                }
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                ResultSetMetaData metadata = keys.getMetaData();
                recordResultMetadata(metadata);
                if (!keys.next()) {
                    throw new SQLException("getGeneratedKeys returned no row");
                }
                Object value = keys.getObject(1);
                line("GENERATED_KEY column=" + metadata.getColumnLabel(1) + " jdbcType="
                        + metadata.getColumnType(1) + " typeName=" + metadata.getColumnTypeName(1)
                        + " javaType=" + className(value) + " value=" + value);
                pass(mode, "insert-generated-key");
                return ((Number) value).longValue();
            }
        }
    }

    private void selectAndMetadata(Connection connection, Mode mode) throws SQLException {
        String sql = "SELECT " + mode.id("id") + ", " + mode.id("name") + ", "
                + mode.id("quantity") + " FROM " + mode.id(TABLE) + " ORDER BY " + mode.id("id");
        logSql(sql, List.of());
        int count = 0;
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql);
        try (statement; result) {
            recordResultMetadata(result.getMetaData());
            while (result.next()) {
                count++;
                line("ROW id=" + result.getObject(1) + " name=" + result.getObject(2)
                        + " quantity=" + result.getObject(3));
            }
        }
        check(count == 4, mode, "select", "expected four rows, got " + count);
        check(statement.isClosed() && result.isClosed(), mode, "resource-close", "statement/result set not closed");
    }

    private void paginate(Connection connection, Mode mode) throws SQLException {
        String base = "SELECT " + mode.id("id") + ", " + mode.id("name") + " FROM "
                + mode.id(TABLE) + " ORDER BY " + mode.id("id");
        String sql;
        if (mode == Mode.MYSQL) {
            sql = base + " LIMIT 2 OFFSET 1";
        } else if (mode == Mode.ORACLE) {
            sql = "SELECT * FROM (SELECT apijson_page_.*, ROWNUM \"RN\" FROM (" + base
                    + ") apijson_page_ WHERE ROWNUM <= 3) WHERE \"RN\" > 1";
        } else {
            sql = base + " OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY";
        }
        logSql(sql, List.of());
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            int count = 0;
            while (result.next()) {
                count++;
            }
            check(count == 2, mode, "pagination-offset", "expected two rows, got " + count);
        }
    }

    private void update(Connection connection, Mode mode, long id) throws SQLException {
        String sql = "UPDATE " + mode.id(TABLE) + " SET " + mode.id("quantity") + " = ? WHERE "
                + mode.id("id") + " = ?";
        logSql(sql, List.of(11, id));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            recordParameterMetadata(statement.getParameterMetaData());
            statement.setInt(1, 11);
            statement.setLong(2, id);
            check(statement.executeUpdate() == 1, mode, "update", "expected one affected row");
        }
    }

    private void delete(Connection connection, Mode mode) throws SQLException {
        String sql = "DELETE FROM " + mode.id(TABLE) + " WHERE " + mode.id("name") + " = ?";
        logSql(sql, List.of("delta"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "delta");
            check(statement.executeUpdate() == 1, mode, "delete", "expected one affected row");
        }
    }

    private void transaction(Connection connection, Mode mode) throws SQLException {
        boolean original = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            insert(connection, mode, "committed", 50);
            connection.commit();
            insert(connection, mode, "rolled-back", 60);
            connection.rollback();
        } finally {
            connection.setAutoCommit(original);
        }
        String sql = "SELECT COUNT(*) FROM " + mode.id(TABLE) + " WHERE " + mode.id("name") + " = ?";
        logSql(sql, List.of("rolled-back"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "rolled-back");
            try (ResultSet result = statement.executeQuery()) {
                check(result.next() && result.getInt(1) == 0, mode, "transaction-rollback",
                        "rolled-back row is visible");
            }
        }
        pass(mode, "transaction-commit");
    }

    private void scrollableResultSet(Connection connection, Mode mode) throws SQLException {
        String sql = "SELECT " + mode.id("id") + " FROM " + mode.id(TABLE) + " ORDER BY " + mode.id("id");
        logSql(sql, List.of());
        try (Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_READ_ONLY); ResultSet result = statement.executeQuery(sql)) {
            boolean supported = result.getType() != ResultSet.TYPE_FORWARD_ONLY;
            boolean moved = supported && result.last();
            line("CURSOR requested=TYPE_SCROLL_INSENSITIVE actual=" + result.getType()
                    + " supported=" + supported + " last=" + moved);
            check(!supported || moved, mode, "scrollable-result-set", "scrollable cursor could not move last");
        } catch (SQLException error) {
            line("CURSOR scrollable unsupported; verifying forward-only fallback");
            try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY); ResultSet result = statement.executeQuery(sql)) {
                check(result.next(), mode, "forward-only-fallback", "forward-only cursor returned no row");
            }
        }
    }

    private int executeUpdate(Connection connection, String sql, List<?> values) throws SQLException {
        logSql(sql, values);
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private void recordParameterMetadata(ParameterMetaData metadata) {
        if (metadata == null) {
            line("PARAM_META unavailable");
            return;
        }
        try {
            for (int index = 1; index <= metadata.getParameterCount(); index++) {
                line("PARAM_META index=" + index + " jdbcType=" + metadata.getParameterType(index)
                        + " typeName=" + metadata.getParameterTypeName(index)
                        + " javaType=" + metadata.getParameterClassName(index));
            }
        } catch (SQLException error) {
            line("PARAM_META unavailable sqlState=" + error.getSQLState() + " code=" + error.getErrorCode()
                    + " message=" + oneLine(error.getMessage()));
        }
    }

    private void recordResultMetadata(ResultSetMetaData metadata) throws SQLException {
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            line("RESULT_META index=" + index + " label=" + metadata.getColumnLabel(index)
                    + " jdbcType=" + metadata.getColumnType(index)
                    + " typeName=" + metadata.getColumnTypeName(index)
                    + " javaType=" + metadata.getColumnClassName(index));
        }
    }

    private boolean isMissingTable(SQLException error) {
        String state = error.getSQLState();
        String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        return "42P01".equals(state) || message.contains("does not exist") || message.contains("不存在");
    }

    private void logSql(String sql, List<?> values) {
        line("SQL " + sql);
        line("PreparedValueList " + values);
    }

    private void check(boolean condition, Mode mode, String label, String detail) {
        if (!condition) {
            throw new IllegalStateException(mode.database + " " + label + ": " + detail);
        }
        pass(mode, label);
    }

    private void pass(Mode mode, String label) {
        line("PASS mode=" + mode.database + " check=" + label);
    }

    private void fail(Mode mode, String label, Throwable error) {
        failures++;
        if (error instanceof SQLException sqlError) {
            line("FAIL mode=" + mode.database + " check=" + label + " sqlState=" + sqlError.getSQLState()
                    + " code=" + sqlError.getErrorCode() + " message=" + oneLine(sqlError.getMessage()));
        } else {
            line("FAIL mode=" + mode.database + " check=" + label + " error="
                    + error.getClass().getName() + " message=" + oneLine(error.getMessage()));
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required environment variable is empty: " + name);
        }
        return value;
    }

    private static String redactUrl(String url) {
        return url.replaceAll("(?i)(password|pwd)=[^&;]+", "$1=<redacted>");
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String oneLine(String value) {
        return String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
    }

    private void line(String value) {
        System.out.println(value);
    }
}
