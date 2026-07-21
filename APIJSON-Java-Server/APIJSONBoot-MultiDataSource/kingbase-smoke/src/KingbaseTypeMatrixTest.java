import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Real-JDBC write/read matrix. It intentionally has no Spring or test-framework dependency. */
public final class KingbaseTypeMatrixTest {
    private static final String TABLE = "apijson_kb_types";

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

    @FunctionalInterface
    private interface Binder {
        void bind(Connection connection, PreparedStatement statement, int index) throws Exception;
    }

    @FunctionalInterface
    private interface Verifier {
        void verify(Object value) throws Exception;
    }

    private static final class TypeCase {
        final String name;
        final String ddl;
        final String binding;
        final Binder binder;
        final Verifier verifier;

        TypeCase(String name, String ddl, String binding, Binder binder, Verifier verifier) {
            this.name = name;
            this.ddl = ddl;
            this.binding = binding;
            this.binder = binder;
            this.verifier = verifier;
        }
    }

    private int passes;
    private int failures;

    public static void main(String[] args) throws Exception {
        Class.forName("com.kingbase8.Driver");
        KingbaseTypeMatrixTest test = new KingbaseTypeMatrixTest();
        test.line("RUN type-matrix started=" + Instant.now() + " java=" + System.getProperty("java.version"));
        for (Mode mode : Mode.values()) {
            test.runMode(mode);
        }
        test.line("RUN type-matrix completed=" + Instant.now() + " passes=" + test.passes
                + " failures=" + test.failures);
        if (test.failures != 0) {
            System.exit(1);
        }
    }

    private void runMode(Mode mode) {
        line("");
        line("=== " + mode.database + " TYPE MATRIX ===");
        String url = required(mode.envPrefix + "_URL");
        try (Connection connection = DriverManager.getConnection(url, required(mode.envPrefix + "_USERNAME"),
                required(mode.envPrefix + "_PASSWORD"))) {
            line("CONFIG url=" + redactUrl(url) + " schema=" + connection.getSchema());
            for (TypeCase typeCase : commonCases(mode)) {
                runCase(connection, mode, typeCase);
            }
            for (TypeCase typeCase : modeCases(mode)) {
                runCase(connection, mode, typeCase);
            }
            nullAndEmptyCase(connection, mode);
            recordKnownLimitations(mode);
        } catch (Throwable error) {
            fail(mode, "connection-or-runner", error);
        }
    }

    private List<TypeCase> commonCases(Mode mode) {
        byte[] binary = new byte[] {0, 1, 2, (byte) 0xff};
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        java.util.ArrayList<TypeCase> cases = new java.util.ArrayList<>(List.of(
                exact("integer", "INTEGER", "setInt", (c, s, i) -> s.setInt(i, Integer.MAX_VALUE),
                        v -> check(((Number) v).intValue() == Integer.MAX_VALUE, "integer mismatch")),
                exact("bigint-js-boundary", "BIGINT", "setLong", (c, s, i) -> s.setLong(i, 9007199254740993L),
                        v -> check(((Number) v).longValue() == 9007199254740993L, "bigint precision lost")),
                exact("decimal", "DECIMAL(38,10)", "setBigDecimal",
                        (c, s, i) -> s.setBigDecimal(i, new BigDecimal("12345678901234567890.1234567890")),
                        v -> check(new BigDecimal(v.toString()).compareTo(
                                new BigDecimal("12345678901234567890.1234567890")) == 0, "decimal mismatch")),
                exact("double", "DOUBLE PRECISION", "setDouble", (c, s, i) -> s.setDouble(i, 12345.125d),
                        v -> check(((Number) v).doubleValue() == 12345.125d, "double mismatch")),
                exact("boolean", "BOOLEAN", "setBoolean", (c, s, i) -> s.setBoolean(i, true),
                        v -> check(Boolean.TRUE.equals(v), "boolean mismatch: " + v)),
                exact("unicode-varchar", "VARCHAR(256)", "setString",
                        (c, s, i) -> s.setString(i, "Kingbase-中文-\uD83D\uDE80"),
                        v -> check("Kingbase-中文-\uD83D\uDE80".equals(v), "unicode mismatch")),
                exact("text", "TEXT", "setCharacterStream", (c, s, i) ->
                        s.setCharacterStream(i, new StringReader("long-text-中文")),
                        v -> check("long-text-中文".equals(readText(v)), "text mismatch")),
                exact("json", "JSON", "setObject(OTHER)",
                        (c, s, i) -> s.setObject(i, "{\"enabled\":true,\"n\":9007199254740993}", Types.OTHER),
                        v -> check(v.toString().contains("\"enabled\"") && v.toString().contains("true"),
                                "json mismatch: " + v)),
                exact("jsonb", "JSONB", "setObject(OTHER)",
                        (c, s, i) -> s.setObject(i, "{\"items\":[1,null,3]}", Types.OTHER),
                        v -> check(v.toString().contains("items"), "jsonb mismatch: " + v)),
                exact("uuid", "UUID", "setObject(OTHER)", (c, s, i) -> s.setObject(i, uuid, Types.OTHER),
                        v -> check(uuid.toString().equalsIgnoreCase(v.toString()), "uuid mismatch")),
                exact("date", "DATE", "setDate", (c, s, i) -> s.setDate(i, Date.valueOf("2026-07-19")),
                        v -> check(v.toString().startsWith("2026-07-19"), "date mismatch")),
                exact("timestamp", mode == Mode.SQLSERVER ? "DATETIME2" : "TIMESTAMP",
                        "setObject(LocalDateTime)",
                        (c, s, i) -> s.setObject(i, LocalDateTime.of(2026, 7, 19, 12, 34, 56, 123456000)),
                        v -> check(v.toString().startsWith(mode == Mode.MYSQL
                                ? "2026-07-19 12:34:56" : "2026-07-19 12:34:56.123456"),
                                "timestamp mismatch: " + v)),
                exact("timestamp-tz", "TIMESTAMP WITH TIME ZONE",
                        mode == Mode.SQLSERVER ? "setObject(String,OTHER)" : "setObject(OffsetDateTime)",
                        (c, s, i) -> {
                            if (mode == Mode.SQLSERVER) s.setObject(i, "2026-07-19 12:34:56+08", Types.OTHER);
                            else s.setObject(i, OffsetDateTime.of(2026, 7, 19, 12, 34, 56, 0,
                                    ZoneOffset.ofHours(8)));
                        },
                        v -> check(v.toString().contains("2026-07-19"), "timestamp-tz mismatch")),
                exact("binary", mode == Mode.SQLSERVER ? "VARBINARY(MAX)" : "BYTEA", "setBytes",
                        (c, s, i) -> s.setBytes(i, binary),
                        v -> check(Arrays.equals(binary, bytes(v)), "bytea mismatch")),
                exact("blob", mode == Mode.SQLSERVER ? "VARBINARY(MAX)" : "BLOB",
                        mode == Mode.SQLSERVER ? "setBinaryStream" : "setBlob(InputStream)", (c, s, i) -> {
                            if (mode == Mode.SQLSERVER) s.setBinaryStream(i, new ByteArrayInputStream(binary));
                            else s.setBlob(i, new ByteArrayInputStream(binary));
                        },
                        v -> check(Arrays.equals(binary, bytes(v)), "blob mismatch")),
                exact("clob", mode == Mode.SQLSERVER ? "VARCHAR(MAX)" : "CLOB",
                        mode == Mode.SQLSERVER ? "setCharacterStream" : "setClob(Reader)", (c, s, i) -> {
                            if (mode == Mode.SQLSERVER) s.setCharacterStream(i, new StringReader("clob-中文"));
                            else s.setClob(i, new StringReader("clob-中文"));
                        },
                        v -> check("clob-中文".equals(readText(v)), "clob mismatch")),
                exact("xml", "XML", "setSQLXML", (c, s, i) -> {
                    SQLXML xml = c.createSQLXML();
                    xml.setString("<root><name>Kingbase</name></root>");
                    s.setSQLXML(i, xml);
                }, v -> check(readText(v).contains("<name>Kingbase</name>"), "xml mismatch")),
                exact("integer-array", "INTEGER[]", "setArray", (c, s, i) -> {
                    Array array = c.createArrayOf("INTEGER", new Object[] {1, null, 3});
                    s.setArray(i, array);
                }, v -> check(Arrays.deepEquals(new Object[] {1, null, 3}, objectArray(v)),
                        "array mismatch: " + v))
        ));
        if (mode == Mode.MYSQL) {
            cases.add(12, exact("time", "TIME", "setObject(OTHER)",
                    (c, s, i) -> s.setObject(i, "12:34:56", Types.OTHER),
                    v -> check(v.toString().startsWith("12:34:56"), "time mismatch")));
        } else if (mode == Mode.SQLSERVER) {
            cases.add(12, exact("time", "TIME", "setTime", (c, s, i) -> s.setTime(i, Time.valueOf("12:34:56")),
                    v -> check(v.toString().startsWith("12:34:56"), "time mismatch")));
            cases.removeIf(typeCase -> "integer-array".equals(typeCase.name));
        }
        return cases;
    }

    private List<TypeCase> modeCases(Mode mode) {
        if (mode == Mode.ORACLE) {
            return List.of(
                    exact("oracle-number", "NUMBER(38,0)", "setBigDecimal",
                            (c, s, i) -> s.setBigDecimal(i, new BigDecimal("9007199254740993")),
                            v -> check("9007199254740993".equals(v.toString()), "NUMBER mismatch")),
                    exact("oracle-varchar2", "VARCHAR2(128)", "setString",
                            (c, s, i) -> s.setString(i, "varchar2-中文"),
                            v -> check("varchar2-中文".equals(v), "VARCHAR2 mismatch")));
        }
        if (mode == Mode.SQLSERVER) {
            UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            return List.of(
                    exact("sqlserver-bit", "BIT", "setBoolean", (c, s, i) -> s.setBoolean(i, true),
                            v -> check(Boolean.TRUE.equals(v), "BIT mismatch")),
                    exact("sqlserver-nvarchar", "NVARCHAR(128)", "setNString",
                            (c, s, i) -> s.setNString(i, "nvarchar-中文"),
                            v -> check("nvarchar-中文".equals(v), "NVARCHAR mismatch")),
                    exact("sqlserver-uniqueidentifier", "UNIQUEIDENTIFIER", "setObject(OTHER)",
                            (c, s, i) -> s.setString(i, uuid.toString()),
                            v -> check(uuid.toString().equalsIgnoreCase(v.toString()), "UNIQUEIDENTIFIER mismatch")),
                    exact("sqlserver-varbinary", "VARBINARY(32)", "setBytes",
                            (c, s, i) -> s.setBytes(i, new byte[] {9, 8, 7}),
                            v -> check(Arrays.equals(new byte[] {9, 8, 7}, bytes(v)), "VARBINARY mismatch")));
        }
        return List.of(
                exact("mysql-longtext", "LONGTEXT", "setString", (c, s, i) -> s.setString(i, "longtext-中文"),
                        v -> check("longtext-中文".equals(readText(v)), "LONGTEXT mismatch")));
    }

    private TypeCase exact(String name, String ddl, String binding, Binder binder, Verifier verifier) {
        return new TypeCase(name, ddl, binding, binder, verifier);
    }

    private void runCase(Connection connection, Mode mode, TypeCase typeCase) {
        try {
            dropTable(connection, mode);
            String create = "CREATE TABLE " + mode.id(TABLE) + " (" + mode.id("value") + " "
                    + typeCase.ddl + ")";
            logSql(create, "[]");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(create);
            }

            String insert = "INSERT INTO " + mode.id(TABLE) + " (" + mode.id("value") + ") VALUES (?)";
            logSql(insert, "[" + typeCase.binding + "]");
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                recordParameterMetadata(statement.getParameterMetaData());
                typeCase.binder.bind(connection, statement, 1);
                check(statement.executeUpdate() == 1, "insert count mismatch");
            }

            String select = "SELECT " + mode.id("value") + " FROM " + mode.id(TABLE);
            logSql(select, "[]");
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(select)) {
                check(result.next(), "no row returned");
                ResultSetMetaData metadata = result.getMetaData();
                Object value = result.getObject(1);
                recordResultMetadata(metadata, value);
                typeCase.verifier.verify(value);
                line("ROUND_TRIP mode=" + mode.database + " case=" + typeCase.name + " jdbcValue="
                        + printable(value) + " jsonSafeValue=" + jsonSafe(value));
            }
            pass(mode, typeCase.name);
        } catch (Throwable error) {
            fail(mode, typeCase.name, error);
        } finally {
            try {
                dropTable(connection, mode);
            } catch (SQLException cleanupError) {
                fail(mode, typeCase.name + "-cleanup", cleanupError);
            }
        }
    }

    private void nullAndEmptyCase(Connection connection, Mode mode) {
        TypeCase nullCase = exact("null", "VARCHAR(32)", "setNull(VARCHAR)",
                (c, s, i) -> s.setNull(i, Types.VARCHAR), v -> check(v == null, "null mismatch"));
        runCase(connection, mode, nullCase);
        TypeCase empty = exact("empty-string", "VARCHAR(32)", "setString(empty)",
                (c, s, i) -> s.setString(i, ""),
                v -> check(mode == Mode.ORACLE ? v == null : "".equals(v),
                        "empty string unexpected value " + v));
        runCase(connection, mode, empty);
        TypeCase emptyBinary = exact("empty-binary", mode == Mode.SQLSERVER ? "VARBINARY(MAX)" : "BYTEA",
                "setBytes(empty)",
                (c, s, i) -> s.setBytes(i, new byte[0]),
                v -> check(mode == Mode.ORACLE ? v == null : bytes(v).length == 0, "empty binary mismatch"));
        runCase(connection, mode, emptyBinary);
    }

    private void recordKnownLimitations(Mode mode) {
        if (mode == Mode.ORACLE) {
            line("KNOWN_LIMITATION mode=" + mode.database
                    + " TIME has no standalone Oracle-compatible type; use DATE/TIMESTAMP");
            line("KNOWN_LIMITATION mode=" + mode.database
                    + " RAW alias is unavailable on this instance; BYTEA provides binary storage");
            line("KNOWN_SEMANTIC mode=" + mode.database + " empty string and empty BYTEA read back as null");
        }
        if (mode == Mode.SQLSERVER) {
            line("KNOWN_LIMITATION mode=" + mode.database
                    + " SQL ARRAY DDL is rejected; use JSON or a child table");
            line("KNOWN_SEMANTIC mode=" + mode.database
                    + " TIMESTAMP is ROWVERSION; DATETIME2 is used for date-time values");
        }
        if (mode == Mode.MYSQL) {
            line("KNOWN_SEMANTIC mode=" + mode.database
                    + " TIMESTAMP fractional seconds are truncated by this driver/server mode");
        }
    }

    private void dropTable(Connection connection, Mode mode) throws SQLException {
        String sql = "DROP TABLE " + (mode == Mode.ORACLE ? "" : "IF EXISTS ") + mode.id(TABLE);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException error) {
            String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
            if (mode != Mode.ORACLE || !("42P01".equals(error.getSQLState())
                    || message.contains("does not exist") || message.contains("不存在"))) {
                throw error;
            }
        }
    }

    private void recordParameterMetadata(ParameterMetaData metadata) {
        try {
            line("PARAM_META jdbcType=" + metadata.getParameterType(1) + " typeName="
                    + metadata.getParameterTypeName(1) + " javaType=" + metadata.getParameterClassName(1));
        } catch (Exception error) {
            line("PARAM_META unavailable=" + oneLine(error.getMessage()));
        }
    }

    private void recordResultMetadata(ResultSetMetaData metadata, Object value) throws SQLException {
        line("RESULT_META jdbcType=" + metadata.getColumnType(1) + " typeName=" + metadata.getColumnTypeName(1)
                + " declaredJavaType=" + metadata.getColumnClassName(1) + " actualJavaType=" + className(value));
    }

    private byte[] bytes(Object value) throws Exception {
        if (value instanceof byte[]) return (byte[]) value;
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            return blob.getBytes(1, (int) blob.length());
        }
        throw new IllegalArgumentException("not binary: " + className(value));
    }

    private String readText(Object value) throws Exception {
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            return clob.getSubString(1, (int) clob.length());
        }
        if (value instanceof SQLXML) return ((SQLXML) value).getString();
        return String.valueOf(value);
    }

    private Object[] objectArray(Object value) throws Exception {
        if (value instanceof Array) return (Object[]) ((Array) value).getArray();
        return (Object[]) value;
    }

    private String printable(Object value) {
        try {
            if (value instanceof byte[] || value instanceof Blob) return Base64.getEncoder().encodeToString(bytes(value));
            if (value instanceof Clob || value instanceof SQLXML) return readText(value);
            if (value instanceof Array) return Arrays.deepToString(objectArray(value));
            return String.valueOf(value);
        } catch (Exception error) {
            return "<unreadable:" + oneLine(error.getMessage()) + ">";
        }
    }

    private String jsonSafe(Object value) {
        try {
            if (value == null) return "null";
            if (value instanceof byte[] || value instanceof Blob) return Base64.getEncoder().encodeToString(bytes(value));
            if (value instanceof Clob || value instanceof SQLXML) return readText(value);
            if (value instanceof Array) return Arrays.deepToString(objectArray(value));
            if (value instanceof Number || value instanceof Boolean) return value.toString();
            return value.toString();
        } catch (Exception error) {
            return "<mapping-error:" + oneLine(error.getMessage()) + ">";
        }
    }

    private void logSql(String sql, String values) {
        line("SQL " + sql);
        line("PreparedValueList " + values);
    }

    private void pass(Mode mode, String label) {
        passes++;
        line("PASS mode=" + mode.database + " case=" + label);
    }

    private void fail(Mode mode, String label, Throwable error) {
        failures++;
        Throwable cause = error;
        while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof SQLException) {
            SQLException sql = (SQLException) cause;
            line("FAIL mode=" + mode.database + " case=" + label + " sqlState=" + sql.getSQLState()
                    + " code=" + sql.getErrorCode() + " message=" + oneLine(sql.getMessage()));
        } else {
            line("FAIL mode=" + mode.database + " case=" + label + " error=" + cause.getClass().getName()
                    + " message=" + oneLine(cause.getMessage()));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required variable: " + name);
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
