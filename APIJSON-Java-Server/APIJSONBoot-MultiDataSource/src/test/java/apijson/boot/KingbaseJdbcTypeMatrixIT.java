package apijson.boot;

import apijson.demo.DemoSQLConfig;
import apijson.demo.DemoSQLExecutor;
import apijson.orm.SQLConfig;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
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
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Real-JDBC data-type round-trip matrix for the three KingbaseES compatibility
 * modes.
 *
 * <p>Run explicitly with {@code mvn -Pkingbase-it
 * -Dit.test=KingbaseJdbcTypeMatrixIT verify}. The Maven profile keeps this
 * external-database test out of the default build, and Maven supplies the
 * Central-distributed Kingbase JDBC driver on the test class path.</p>
 */
@RunWith(Parameterized.class)
public class KingbaseJdbcTypeMatrixIT {
    private static final String TABLE = "apijson_kb_types";
    private static final long JS_UNSAFE_INTEGER = 9007199254740993L;
    private static final BigDecimal DECIMAL =
            new BigDecimal("12345678901234567890.1234567890");
    private static final byte[] BINARY =
            new byte[]{0, 1, 2, (byte) 0xff};
    private static final UUID UUID_VALUE =
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private enum Mode {
        MYSQL("KINGBASE_MYSQL", "mysql", "`", "KINGBASE_MYSQL"),
        ORACLE("KINGBASE_ORACLE", "oracle", "\"", "KINGBASE_ORACLE"),
        SQLSERVER(
                "KINGBASE_SQLSERVER",
                "sqlserver",
                "\"",
                "KINGBASE_SQLSERVER");

        private final String database;
        private final String serverMode;
        private final String quote;
        private final String environmentPrefix;

        Mode(
                String database,
                String serverMode,
                String quote,
                String environmentPrefix) {
            this.database = database;
            this.serverMode = serverMode;
            this.quote = quote;
            this.environmentPrefix = environmentPrefix;
        }

        private String identifier(String value) {
            return quote + value + quote;
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(
                Connection connection,
                PreparedStatement statement,
                int index) throws Exception;
    }

    @FunctionalInterface
    private interface Verifier {
        void verify(Object value) throws Exception;
    }

    private static final class TypeCase {
        private final String name;
        private final String ddl;
        private final String binding;
        private final Binder binder;
        private final Verifier verifier;

        private TypeCase(
                String name,
                String ddl,
                String binding,
                Binder binder,
                Verifier verifier) {
            this.name = name;
            this.ddl = ddl;
            this.binding = binding;
            this.binder = binder;
            this.verifier = verifier;
        }
    }

    private final Mode mode;

    public KingbaseJdbcTypeMatrixIT(Mode mode) {
        this.mode = mode;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> modes() {
        return Arrays.asList(new Object[][]{
                {Mode.MYSQL},
                {Mode.ORACLE},
                {Mode.SQLSERVER}
        });
    }

    @BeforeClass
    public static void loadDriver() throws ClassNotFoundException {
        Class.forName("com.kingbase8.Driver");
    }

    @Test
    public void jdbcValuesRoundTripWithoutTypeOrPrecisionLoss()
            throws Exception {
        String url = required(mode.environmentPrefix + "_URL");
        String username = required(mode.environmentPrefix + "_USERNAME");
        String password = required(mode.environmentPrefix + "_PASSWORD");

        try (Connection connection =
                     DriverManager.getConnection(url, username, password)) {
            verifyServerMode(connection);
            for (TypeCase typeCase : commonCases()) {
                runCase(connection, typeCase);
            }
            for (TypeCase typeCase : modeCases()) {
                runCase(connection, typeCase);
            }
            if (mode == Mode.MYSQL) {
                runApiJsonMySqlJsonBindingCase(connection);
            }
            probeModeSpecificTypes(connection);
            runNullAndEmptyCases(connection);
            recordKnownCompatibilitySemantics();
        }
    }

    private void runApiJsonMySqlJsonBindingCase(Connection connection)
            throws Exception {
        runCase(connection, typeCase(
                "apijson-json-array-binding",
                "JSON",
                "APIJSON setArgument(Types.OTHER)",
                (currentConnection, statement, index) -> {
                    DemoSQLConfig config = new DemoSQLConfig();
                    config.setDatabase(SQLConfig.DATABASE_KINGBASE_MYSQL);
                    new DemoSQLExecutor().setArgument(
                            config,
                            statement,
                            index - 1,
                            Arrays.asList("image.jpg"));
                },
                value -> assertTrue(value.toString().contains("image.jpg"))));
    }

    private void verifyServerMode(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result =
                     statement.executeQuery("SHOW database_mode")) {
            assertTrue("SHOW database_mode returned no row", result.next());
            assertEquals(
                    "Connected to the wrong Kingbase compatibility mode",
                    mode.serverMode,
                    result.getString(1).trim().toLowerCase(Locale.ROOT));
        }
    }

    private List<TypeCase> commonCases() {
        List<TypeCase> cases = new ArrayList<>(List.of(
                typeCase(
                        "integer",
                        "INTEGER",
                        "setInt",
                        (connection, statement, index) ->
                                statement.setInt(index, Integer.MAX_VALUE),
                        value -> assertEquals(
                                Integer.MAX_VALUE,
                                ((Number) value).intValue())),
                typeCase(
                        "bigint-js-boundary",
                        "BIGINT",
                        "setLong",
                        (connection, statement, index) ->
                                statement.setLong(index, JS_UNSAFE_INTEGER),
                        value -> assertEquals(
                                "BIGINT precision was lost",
                                JS_UNSAFE_INTEGER,
                                ((Number) value).longValue())),
                typeCase(
                        "decimal",
                        "DECIMAL(38,10)",
                        "setBigDecimal",
                        (connection, statement, index) ->
                                statement.setBigDecimal(index, DECIMAL),
                        value -> assertEquals(
                                0,
                                DECIMAL.compareTo(
                                        new BigDecimal(value.toString())))),
                typeCase(
                        "double",
                        "DOUBLE PRECISION",
                        "setDouble",
                        (connection, statement, index) ->
                                statement.setDouble(index, 12345.125d),
                        value -> assertEquals(
                                12345.125d,
                                ((Number) value).doubleValue(),
                                0.0d)),
                typeCase(
                        "boolean",
                        "BOOLEAN",
                        "setBoolean",
                        (connection, statement, index) ->
                                statement.setBoolean(index, true),
                        value -> assertEquals(Boolean.TRUE, value)),
                typeCase(
                        "unicode-varchar",
                        "VARCHAR(256)",
                        "setString",
                        (connection, statement, index) ->
                                statement.setString(
                                        index,
                                        "Kingbase-中文"),
                        value -> assertEquals(
                                "Kingbase-中文",
                                value)),
                typeCase(
                        "text",
                        "TEXT",
                        "setCharacterStream",
                        (connection, statement, index) ->
                                statement.setCharacterStream(
                                        index,
                                        new StringReader("long-text-中文")),
                        value -> assertEquals(
                                "long-text-中文",
                                readText(value))),
                typeCase(
                        "json",
                        "JSON",
                        "setObject(OTHER)",
                        (connection, statement, index) ->
                                statement.setObject(
                                        index,
                                        "{\"enabled\":true,"
                                                + "\"n\":9007199254740993}",
                                        Types.OTHER),
                        value -> {
                            assertTrue(value.toString().contains("\"enabled\""));
                            assertTrue(value.toString().contains("true"));
                            assertTrue(value.toString().contains(
                                    "9007199254740993"));
                        }),
                typeCase(
                        "jsonb",
                        "JSONB",
                        "setObject(OTHER)",
                        (connection, statement, index) ->
                                statement.setObject(
                                        index,
                                        "{\"items\":[1,null,3]}",
                                        Types.OTHER),
                        value -> assertTrue(
                                value.toString().contains("items"))),
                typeCase(
                        "uuid",
                        "UUID",
                        "setObject(OTHER)",
                        (connection, statement, index) ->
                                statement.setObject(
                                        index,
                                        UUID_VALUE,
                                        Types.OTHER),
                        value -> assertEquals(
                                UUID_VALUE.toString(),
                                value.toString().toLowerCase(Locale.ROOT))),
                typeCase(
                        "date",
                        "DATE",
                        "setDate",
                        (connection, statement, index) ->
                                statement.setDate(
                                        index,
                                        Date.valueOf("2026-07-19")),
                        value -> assertTrue(
                                value.toString().startsWith("2026-07-19"))),
                typeCase(
                        "timestamp",
                        mode == Mode.SQLSERVER
                                ? "DATETIME2"
                                : "TIMESTAMP",
                        "setObject(LocalDateTime)",
                        (connection, statement, index) ->
                                statement.setObject(
                                        index,
                                        LocalDateTime.of(
                                                2026,
                                                7,
                                                19,
                                                12,
                                                34,
                                                56,
                                                123456000)),
                        value -> assertTrue(
                                "Unexpected timestamp: " + value,
                                value.toString().startsWith(
                                        mode == Mode.MYSQL
                                                ? "2026-07-19 12:34:56"
                                                : "2026-07-19 "
                                                + "12:34:56.123456"))),
                typeCase(
                        "timestamp-tz",
                        "TIMESTAMP WITH TIME ZONE",
                        mode == Mode.SQLSERVER
                                ? "setObject(String,OTHER)"
                                : "setObject(OffsetDateTime)",
                        (connection, statement, index) -> {
                            if (mode == Mode.SQLSERVER) {
                                statement.setObject(
                                        index,
                                        "2026-07-19 12:34:56+08",
                                        Types.OTHER);
                            } else {
                                statement.setObject(
                                        index,
                                        OffsetDateTime.of(
                                                2026,
                                                7,
                                                19,
                                                12,
                                                34,
                                                56,
                                                0,
                                                ZoneOffset.ofHours(8)));
                            }
                        },
                        value -> assertTrue(
                                value.toString().contains("2026-07-19"))),
                typeCase(
                        "binary",
                        mode == Mode.SQLSERVER
                                ? "VARBINARY(MAX)"
                                : "BYTEA",
                        "setBytes",
                        (connection, statement, index) ->
                                statement.setBytes(index, BINARY),
                        value -> assertArrayEquals(BINARY, bytes(value))),
                typeCase(
                        "blob",
                        mode == Mode.SQLSERVER
                                ? "VARBINARY(MAX)"
                                : "BLOB",
                        mode == Mode.SQLSERVER
                                ? "setBinaryStream"
                                : "setBlob(InputStream)",
                        (connection, statement, index) -> {
                            ByteArrayInputStream input =
                                    new ByteArrayInputStream(BINARY);
                            if (mode == Mode.SQLSERVER) {
                                statement.setBinaryStream(index, input);
                            } else {
                                statement.setBlob(index, input);
                            }
                        },
                        value -> assertArrayEquals(BINARY, bytes(value))),
                typeCase(
                        "clob",
                        mode == Mode.SQLSERVER
                                ? "VARCHAR(MAX)"
                                : "CLOB",
                        mode == Mode.SQLSERVER
                                ? "setCharacterStream"
                                : "setClob(Reader)",
                        (connection, statement, index) -> {
                            StringReader reader =
                                    new StringReader("clob-中文");
                            if (mode == Mode.SQLSERVER) {
                                statement.setCharacterStream(index, reader);
                            } else {
                                statement.setClob(index, reader);
                            }
                        },
                        value -> assertEquals(
                                "clob-中文",
                                readText(value))),
                typeCase(
                        "sqlxml",
                        "XML",
                        "setSQLXML",
                        (connection, statement, index) -> {
                            SQLXML xml = connection.createSQLXML();
                            xml.setString(
                                    "<root><name>Kingbase</name></root>");
                            statement.setSQLXML(index, xml);
                        },
                        value -> assertTrue(
                                readText(value).contains(
                                        "<name>Kingbase</name>"))),
                typeCase(
                        "integer-array",
                        "INTEGER[]",
                        "setArray",
                        (connection, statement, index) -> {
                            Array array = connection.createArrayOf(
                                    "INTEGER",
                                    new Object[]{1, null, 3});
                            statement.setArray(index, array);
                        },
                        value -> assertEquals(
                                Arrays.asList(1, null, 3),
                                Arrays.asList(objectArray(value))))
        ));

        if (mode == Mode.MYSQL) {
            cases.add(typeCase(
                    "time",
                    "TIME",
                    "setObject(OTHER)",
                    (connection, statement, index) ->
                            statement.setObject(
                                    index,
                                    "12:34:56",
                                    Types.OTHER),
                    value -> assertTrue(
                            value.toString().startsWith("12:34:56"))));
        } else if (mode == Mode.SQLSERVER) {
            cases.add(typeCase(
                    "time",
                    "TIME",
                    "setTime",
                    (connection, statement, index) ->
                            statement.setTime(
                                    index,
                                    Time.valueOf("12:34:56")),
                    value -> assertTrue(
                            value.toString().startsWith("12:34:56"))));
            cases.removeIf(typeCase ->
                    "integer-array".equals(typeCase.name));
        }
        return cases;
    }

    private List<TypeCase> modeCases() {
        if (mode == Mode.ORACLE) {
            return List.of(
                    typeCase(
                            "oracle-number",
                            "NUMBER(38,0)",
                            "setBigDecimal",
                            (connection, statement, index) ->
                                    statement.setBigDecimal(
                                            index,
                                            new BigDecimal(
                                                    "9007199254740993")),
                            value -> assertEquals(
                                    "9007199254740993",
                                    value.toString())),
                    typeCase(
                            "oracle-varchar2",
                            "VARCHAR2(128)",
                            "setString",
                            (connection, statement, index) ->
                                    statement.setString(
                                            index,
                                            "varchar2-中文"),
                            value -> assertEquals(
                                    "varchar2-中文",
                                    value)));
        }

        if (mode == Mode.SQLSERVER) {
            return List.of(
                    typeCase(
                            "sqlserver-bit",
                            "BIT",
                            "setBoolean",
                            (connection, statement, index) ->
                                    statement.setBoolean(index, true),
                            value -> assertEquals(Boolean.TRUE, value)),
                    typeCase(
                            "sqlserver-nvarchar",
                            "NVARCHAR(128)",
                            "setNString",
                            (connection, statement, index) ->
                                    statement.setNString(
                                            index,
                                            "nvarchar-中文"),
                            value -> assertEquals(
                                    "nvarchar-中文",
                                    value)),
                    typeCase(
                            "sqlserver-uniqueidentifier",
                            "UNIQUEIDENTIFIER",
                            "setString",
                            (connection, statement, index) ->
                                    statement.setString(
                                            index,
                                            UUID_VALUE.toString()),
                            value -> assertEquals(
                                    UUID_VALUE.toString(),
                                    value.toString().toLowerCase(
                                            Locale.ROOT))),
                    typeCase(
                            "sqlserver-varbinary",
                            "VARBINARY(32)",
                            "setBytes",
                            (connection, statement, index) ->
                                    statement.setBytes(
                                            index,
                                            new byte[]{9, 8, 7}),
                            value -> assertArrayEquals(
                                    new byte[]{9, 8, 7},
                                    bytes(value))));
        }

        return List.of(typeCase(
                "mysql-longtext",
                "LONGTEXT",
                "setString",
                (connection, statement, index) ->
                        statement.setString(index, "longtext-中文"),
                value -> assertEquals(
                        "longtext-中文",
                        readText(value))));
    }

    private void probeModeSpecificTypes(Connection connection)
            throws Exception {
        TypeCase supplementaryUnicode = typeCase(
                "unicode-supplementary-character",
                "VARCHAR(64)",
                "setString",
                (currentConnection, statement, index) ->
                        statement.setString(index, "Kingbase-\uD83D\uDE80"),
                value -> assertEquals("Kingbase-\uD83D\uDE80", value));
        runCapabilityProbe(
                connection,
                supplementaryUnicode,
                "supplementary Unicode character was not preserved");

        if (mode == Mode.ORACLE) {
            TypeCase raw = typeCase(
                    "oracle-raw",
                    "RAW(32)",
                    "setBytes",
                    (currentConnection, statement, index) ->
                            statement.setBytes(index, BINARY),
                    value -> assertArrayEquals(BINARY, bytes(value)));
            runCapabilityProbe(connection, raw, "RAW alias is unavailable");
        } else if (mode == Mode.SQLSERVER) {
            TypeCase array = typeCase(
                    "sqlserver-integer-array",
                    "INTEGER[]",
                    "setArray",
                    (currentConnection, statement, index) -> {
                        Array value = currentConnection.createArrayOf(
                                "INTEGER",
                                new Object[]{1, null, 3});
                        statement.setArray(index, value);
                    },
                    value -> assertEquals(
                            Arrays.asList(1, null, 3),
                            Arrays.asList(objectArray(value))));
            runCapabilityProbe(
                    connection,
                    array,
                    "SQL ARRAY DDL is unavailable");
        }
    }

    private void runCapabilityProbe(
            Connection connection,
            TypeCase typeCase,
            String unsupportedMessage) throws Exception {
        try {
            runCase(connection, typeCase);
        } catch (SQLException unsupported) {
            log("KNOWN_LIMITATION mode=" + mode.database
                    + " case=" + typeCase.name
                    + " message=" + unsupportedMessage
                    + " sqlState=" + unsupported.getSQLState()
                    + " code=" + unsupported.getErrorCode());
        } catch (AssertionError unsupported) {
            log("KNOWN_LIMITATION mode=" + mode.database
                    + " case=" + typeCase.name
                    + " message=" + unsupportedMessage);
        }
    }

    private void runNullAndEmptyCases(Connection connection)
            throws Exception {
        runCase(connection, typeCase(
                "null",
                "VARCHAR(32)",
                "setNull(VARCHAR)",
                (currentConnection, statement, index) ->
                        statement.setNull(index, Types.VARCHAR),
                value -> assertNull(value)));

        runCase(connection, typeCase(
                "empty-string",
                "VARCHAR(32)",
                "setString(empty)",
                (currentConnection, statement, index) ->
                        statement.setString(index, ""),
                value -> {
                    if (mode == Mode.ORACLE) {
                        assertNull(value);
                    } else {
                        assertEquals("", value);
                    }
                }));

        runCase(connection, typeCase(
                "empty-binary",
                mode == Mode.SQLSERVER
                        ? "VARBINARY(MAX)"
                        : "BYTEA",
                "setBytes(empty)",
                (currentConnection, statement, index) ->
                        statement.setBytes(index, new byte[0]),
                value -> {
                    if (mode == Mode.ORACLE) {
                        assertNull(value);
                    } else {
                        assertArrayEquals(new byte[0], bytes(value));
                    }
                }));
    }

    private TypeCase typeCase(
            String name,
            String ddl,
            String binding,
            Binder binder,
            Verifier verifier) {
        return new TypeCase(name, ddl, binding, binder, verifier);
    }

    private void runCase(
            Connection connection,
            TypeCase typeCase) throws Exception {
        try {
            dropTable(connection);
            String create = "CREATE TABLE " + mode.identifier(TABLE)
                    + " (" + mode.identifier("value") + " "
                    + typeCase.ddl + ")";
            log("SQL " + create);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(create);
            }

            String insert = "INSERT INTO " + mode.identifier(TABLE)
                    + " (" + mode.identifier("value") + ") VALUES (?)";
            log("SQL " + insert);
            log("PreparedValueList [" + typeCase.binding + "]");
            try (PreparedStatement statement =
                         connection.prepareStatement(insert)) {
                recordParameterMetadata(statement.getParameterMetaData());
                typeCase.binder.bind(connection, statement, 1);
                assertEquals(1, statement.executeUpdate());
            }

            String select = "SELECT " + mode.identifier("value")
                    + " FROM " + mode.identifier(TABLE);
            log("SQL " + select);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(select)) {
                assertTrue("No row returned for " + typeCase.name, result.next());
                Object value = result.getObject(1);
                recordResultMetadata(result.getMetaData(), value);
                typeCase.verifier.verify(value);
            }
            log("PASS mode=" + mode.database + " case=" + typeCase.name);
        } finally {
            dropTable(connection);
        }
    }

    private void dropTable(Connection connection) throws SQLException {
        String sql = "DROP TABLE "
                + (mode == Mode.ORACLE ? "" : "IF EXISTS ")
                + mode.identifier(TABLE);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException error) {
            if (mode != Mode.ORACLE || !isMissingTable(error)) {
                throw error;
            }
        }
    }

    private boolean isMissingTable(SQLException error) {
        String message =
                String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        return "42P01".equals(error.getSQLState())
                || message.contains("does not exist")
                || message.contains("不存在");
    }

    private void recordParameterMetadata(ParameterMetaData metadata) {
        try {
            log("PARAM_META jdbcType=" + metadata.getParameterType(1)
                    + " typeName=" + metadata.getParameterTypeName(1)
                    + " javaType=" + metadata.getParameterClassName(1));
        } catch (SQLException error) {
            log("PARAM_META unavailable=" + oneLine(error.getMessage()));
        }
    }

    private void recordResultMetadata(
            ResultSetMetaData metadata,
            Object value) throws SQLException {
        assertNotNull(metadata);
        assertEquals(1, metadata.getColumnCount());
        log("RESULT_META jdbcType=" + metadata.getColumnType(1)
                + " typeName=" + metadata.getColumnTypeName(1)
                + " declaredJavaType=" + metadata.getColumnClassName(1)
                + " actualJavaType="
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static byte[] bytes(Object value) throws Exception {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            return blob.getBytes(1, Math.toIntExact(blob.length()));
        }
        throw new IllegalArgumentException(
                "Not a binary JDBC value: "
                        + (value == null
                        ? "null"
                        : value.getClass().getName()));
    }

    private static String readText(Object value) throws Exception {
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        if (value instanceof SQLXML) {
            return ((SQLXML) value).getString();
        }
        return String.valueOf(value);
    }

    private static Object[] objectArray(Object value) throws SQLException {
        if (value instanceof Array) {
            return (Object[]) ((Array) value).getArray();
        }
        return (Object[]) value;
    }

    private void recordKnownCompatibilitySemantics() {
        if (mode == Mode.ORACLE) {
            log("KNOWN_LIMITATION mode=" + mode.database
                    + " TIME has no standalone Oracle-compatible type; "
                    + "use DATE/TIMESTAMP");
            log("KNOWN_SEMANTIC mode=" + mode.database
                    + " empty VARCHAR and BYTEA read back as null");
        } else if (mode == Mode.SQLSERVER) {
            log("KNOWN_SEMANTIC mode=" + mode.database
                    + " TIMESTAMP is ROWVERSION; "
                    + "DATETIME2 is used for date-time values");
        } else {
            log("KNOWN_SEMANTIC mode=" + mode.database
                    + " TIMESTAMP fractional seconds may be truncated");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required environment variable is empty: " + name);
        }
        return value;
    }

    private static String oneLine(String value) {
        return String.valueOf(value)
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    private void log(String message) {
        System.out.println(message);
    }
}
