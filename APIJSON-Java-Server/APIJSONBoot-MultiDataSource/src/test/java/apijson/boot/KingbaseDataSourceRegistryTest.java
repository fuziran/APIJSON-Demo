package apijson.boot;

import apijson.orm.SQLConfig;
import org.junit.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class KingbaseDataSourceRegistryTest {

    @Test
    public void requiresConfiguredModeEvenWhenStartupVerificationIsDisabled() {
        KingbaseDataSourceRegistry registry = registry(
                dataSource("mysql"),
                dataSource("oracle"),
                dataSource("sqlserver"),
                "",
                false,
                false);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                registry::afterPropertiesSet);

        assertTrue(exception.getMessage().contains("KINGBASE_MODE"));
    }

    @Test
    public void rejectsUnsupportedConfiguredModeAtStartup() {
        KingbaseDataSourceRegistry registry = registry(
                dataSource("mysql"),
                dataSource("oracle"),
                dataSource("sqlserver"),
                "KINGBASE-POSTGRESQL",
                false,
                false);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                registry::afterPropertiesSet);

        assertTrue(exception.getMessage().contains("Unsupported"));
    }

    @Test
    public void verifiesOnlyConfiguredModeByDefault() throws Exception {
        AtomicInteger mysqlConnections = new AtomicInteger();
        AtomicInteger oracleConnections = new AtomicInteger();
        AtomicInteger sqlserverConnections = new AtomicInteger();
        DataSource mysql = dataSource("mysql", mysqlConnections);
        DataSource oracle = dataSource("oracle", oracleConnections);
        DataSource sqlserver =
                dataSource("sqlserver", sqlserverConnections);
        KingbaseDataSourceRegistry registry = registry(
                mysql,
                oracle,
                sqlserver,
                SQLConfig.DATABASE_KINGBASE_ORACLE,
                true,
                false);

        registry.afterPropertiesSet();

        assertEquals(0, mysqlConnections.get());
        assertEquals(1, oracleConnections.get());
        assertEquals(0, sqlserverConnections.get());
        assertSame(
                oracle,
                registry.getVerifiedDataSource(
                        SQLConfig.DATABASE_KINGBASE_ORACLE));
        assertEquals(1, oracleConnections.get());
    }

    @Test
    public void verifiesAllModesAtStartupWhenRequested() throws Exception {
        AtomicInteger mysqlConnections = new AtomicInteger();
        AtomicInteger oracleConnections = new AtomicInteger();
        AtomicInteger sqlserverConnections = new AtomicInteger();
        KingbaseDataSourceRegistry registry = registry(
                dataSource("mysql", mysqlConnections),
                dataSource("oracle", oracleConnections),
                dataSource("sqlserver", sqlserverConnections),
                SQLConfig.DATABASE_KINGBASE_MYSQL,
                true,
                true);

        registry.afterPropertiesSet();

        assertEquals(1, mysqlConnections.get());
        assertEquals(1, oracleConnections.get());
        assertEquals(1, sqlserverConnections.get());
    }

    @Test
    public void failsWhenRegisteredDataSourceModeDoesNotMatch()
            throws Exception {
        KingbaseDataSourceRegistry registry = registry(
                dataSource("oracle"),
                dataSource("oracle"),
                dataSource("sqlserver"),
                SQLConfig.DATABASE_KINGBASE_MYSQL,
                false,
                false);
        registry.afterPropertiesSet();

        Exception exception = assertThrows(
                Exception.class,
                () -> registry.getVerifiedDataSource(
                        SQLConfig.DATABASE_KINGBASE_MYSQL));

        assertTrue(exception.getMessage().contains(
                SQLConfig.DATABASE_KINGBASE_MYSQL));
        assertTrue(exception.getMessage().contains(
                SQLConfig.DATABASE_KINGBASE_ORACLE));
    }

    private static KingbaseDataSourceRegistry registry(
            DataSource mysql,
            DataSource oracle,
            DataSource sqlserver,
            String configuredDatabase,
            boolean verifyOnStartup,
            boolean verifyAllOnStartup) {
        return new KingbaseDataSourceRegistry(
                mysql,
                oracle,
                sqlserver,
                configuredDatabase,
                verifyOnStartup,
                verifyAllOnStartup);
    }

    private static DataSource dataSource(String serverMode) {
        return dataSource(serverMode, new AtomicInteger());
    }

    private static DataSource dataSource(
            String serverMode, AtomicInteger connectionCount) {
        return proxy(
                DataSource.class,
                (method, args) -> {
                    if ("getConnection".equals(method)) {
                        connectionCount.incrementAndGet();
                        return connection(serverMode);
                    }
                    return null;
                });
    }

    private static Connection connection(String serverMode) {
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
                (method, args) -> "executeQuery".equals(method)
                        ? resultSet : null);
        return proxy(
                Connection.class,
                (method, args) -> "createStatement".equals(method)
                        ? statement : null);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                KingbaseDataSourceRegistryTest.class.getClassLoader(),
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
