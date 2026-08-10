package apijson.boot;

import apijson.orm.SQLConfig;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class KingbaseModeDetectorTest {

    @Test
    public void normalizesSupportedConfiguredModesIgnoringCaseAndWhitespace() {
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_MYSQL,
                KingbaseModeDetector.normalizeConfiguredDatabase(
                        " kingbase_mysql "));
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_ORACLE,
                KingbaseModeDetector.normalizeConfiguredDatabase(
                        "KingBase_Oracle"));
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_SQLSERVER,
                KingbaseModeDetector.normalizeConfiguredDatabase(
                        "KINGBASE_SQLSERVER"));
    }

    @Test
    public void rejectsUnsupportedConfiguredMode() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> KingbaseModeDetector.normalizeConfiguredDatabase(
                        "KINGBASE-POSTGRESQL"));

        assertTrue(exception.getMessage().contains("Unsupported"));
        assertTrue(exception.getMessage().contains("KINGBASE_MYSQL"));
        assertTrue(exception.getMessage().contains("KINGBASE_ORACLE"));
        assertTrue(exception.getMessage().contains("KINGBASE_SQLSERVER"));
    }

    @Test
    public void mapsSupportedServerModes() throws SQLException {
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_MYSQL,
                KingbaseModeDetector.fromServerMode(" MySQL "));
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_ORACLE,
                KingbaseModeDetector.fromServerMode("ORACLE"));
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_SQLSERVER,
                KingbaseModeDetector.fromServerMode("sqlserver"));
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_SQLSERVER,
                KingbaseModeDetector.fromServerMode("sql_server"));
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_SQLSERVER,
                KingbaseModeDetector.fromServerMode("mssql"));
    }

    @Test
    public void rejectsUnsupportedServerMode() {
        SQLException exception = assertThrows(
                SQLException.class,
                () -> KingbaseModeDetector.fromServerMode("postgresql"));

        assertTrue(exception.getMessage().contains("Unsupported"));
        assertTrue(exception.getMessage().contains("postgresql"));
    }

    @Test
    public void detectsModeUsingShowDatabaseMode() throws SQLException {
        AtomicReference<String> executedSql = new AtomicReference<>();

        assertEquals(
                SQLConfig.DATABASE_KINGBASE_ORACLE,
                KingbaseModeDetector.detect(
                        connectionReturning("oracle", executedSql)));
        assertEquals("SHOW database_mode", executedSql.get());
    }

    @Test
    public void verifiesMatchingMode() throws SQLException {
        assertEquals(
                SQLConfig.DATABASE_KINGBASE_MYSQL,
                KingbaseModeDetector.verify(
                        connectionReturning("mysql", new AtomicReference<>()),
                        "kingbase_mysql"));
    }

    @Test
    public void rejectsMismatchWithExpectedAndActualModes() {
        SQLException exception = assertThrows(
                SQLException.class,
                () -> KingbaseModeDetector.verify(
                        connectionReturning(
                                "oracle", new AtomicReference<>()),
                        SQLConfig.DATABASE_KINGBASE_MYSQL));

        assertTrue(exception.getMessage().contains(
                SQLConfig.DATABASE_KINGBASE_MYSQL));
        assertTrue(exception.getMessage().contains(
                SQLConfig.DATABASE_KINGBASE_ORACLE));
    }

    private static Connection connectionReturning(
            String serverMode, AtomicReference<String> executedSql) {
        AtomicBoolean firstRow = new AtomicBoolean(true);
        ResultSet resultSet = proxy(
                ResultSet.class,
                (method, args) -> {
                    if ("next".equals(method)) {
                        return firstRow.getAndSet(false);
                    }
                    if ("getString".equals(method)) {
                        return serverMode;
                    }
                    return null;
                });

        Statement statement = proxy(
                Statement.class,
                (method, args) -> {
                    if ("executeQuery".equals(method)) {
                        executedSql.set((String) args[0]);
                        return resultSet;
                    }
                    return null;
                });

        return proxy(
                Connection.class,
                (method, args) -> "createStatement".equals(method)
                        ? statement : null);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                KingbaseModeDetectorTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object value = invocation.invoke(method.getName(), args);
                    return value != null
                            ? value : defaultValue(method.getReturnType());
                }));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args) throws Throwable;
    }
}
