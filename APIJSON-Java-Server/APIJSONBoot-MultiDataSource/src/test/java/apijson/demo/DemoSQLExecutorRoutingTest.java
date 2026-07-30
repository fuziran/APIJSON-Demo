package apijson.demo;

import apijson.boot.KingbaseDataSourceRegistry;
import apijson.orm.SQLConfig;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DemoSQLExecutorRoutingTest {

    @Test
    public void nonKingbaseRequestNeverLooksUpRegistry() throws Exception {
        AtomicInteger registryLookups = new AtomicInteger();
        DemoSQLExecutor executor = new TestExecutor(context(
                Collections.emptyMap(), null, registryLookups));
        DemoSQLConfig config = config(SQLConfig.DATABASE_MYSQL, null);

        assertThrows(SQLException.class,
                () -> executor.getConnection(config));
        assertEquals(0, registryLookups.get());
    }

    @Test
    public void existingDruidRoutesStillUseTheirOriginalBeans()
            throws Exception {
        String[] routes = {"DRUID", "DRUID-TEST", "DRUID-ONLINE"};
        String[] beanNames = {
                "druidDataSource",
                "druidTestDataSource",
                "druidOnlineDataSource"
        };

        for (int i = 0; i < routes.length; i++) {
            AtomicInteger selectedConnections = new AtomicInteger();
            Map<String, DruidDataSource> dataSources =
                    new LinkedHashMap<>();
            for (String beanName : beanNames) {
                dataSources.put(
                        beanName,
                        druidDataSource(
                                beanName.equals(beanNames[i])
                                        ? selectedConnections
                                        : new AtomicInteger()));
            }
            DemoSQLExecutor executor = new TestExecutor(context(
                    dataSources, null, new AtomicInteger()));
            Connection connection = executor.getConnection(
                    config(SQLConfig.DATABASE_MYSQL, routes[i]));

            assertEquals(routes[i], 1, selectedConnections.get());
            assertSame(
                    connection,
                    executor.getConnection(
                            executor.getConnectionKey(
                                    config(
                                            SQLConfig.DATABASE_MYSQL,
                                            routes[i]))));
            close(dataSources);
        }
    }

    @Test
    public void nativeDataSourceFailureKeepsOriginalFallbackFlow()
            throws Exception {
        Map<String, DruidDataSource> dataSources = new LinkedHashMap<>();
        DruidDataSource failing = new DruidDataSource();
        failing.setUrl("jdbc:routing-test:failure");
        failing.setDriver(new TestDriver(
                new AtomicInteger(), true));
        failing.setConnectionErrorRetryAttempts(0);
        failing.setBreakAfterAcquireFailure(true);
        failing.setFailFast(true);
        failing.setMaxWait(100);
        dataSources.put("druidDataSource", failing);
        DemoSQLExecutor executor = new TestExecutor(context(
                dataSources, null, new AtomicInteger()));
        SQLException exception = assertThrows(
                SQLException.class,
                () -> executor.getConnection(new InvalidFallbackConfig()));

        assertFalse(exception.getMessage(),
                exception.getMessage().contains(
                        "Kingbase data source verification or routing failed"));
        close(dataSources);
    }

    @Test
    public void kingbaseMismatchReportsExpectedAndActualModes()
            throws Exception {
        KingbaseDataSourceRegistry registry =
                new StubRegistry(null, new SQLException(
                        "expected KINGBASE-MYSQL but server reported "
                                + "KINGBASE-ORACLE"));
        DemoSQLExecutor executor = new TestExecutor(context(
                Collections.emptyMap(), registry, new AtomicInteger()));
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> executor.getConnection(config(
                        SQLConfig.DATABASE_KINGBASE_MYSQL, null)));

        assertTrue(exception.getMessage().contains("KINGBASE-MYSQL"));
        assertTrue(exception.getMessage().contains("KINGBASE-ORACLE"));
    }

    @Test
    public void kingbaseConnectionKeyDoesNotCollideWithNativeDatabases() {
        DemoSQLExecutor executor = new TestExecutor(null);
        String kingbaseKey = executor.getConnectionKey(
                config(SQLConfig.DATABASE_KINGBASE_MYSQL, null));

        assertNotEquals(
                kingbaseKey,
                executor.getConnectionKey(
                        config(SQLConfig.DATABASE_MYSQL, null)));
        assertNotEquals(
                kingbaseKey,
                executor.getConnectionKey(
                        config(SQLConfig.DATABASE_ORACLE, null)));
        assertNotEquals(
                kingbaseKey,
                executor.getConnectionKey(
                        config(SQLConfig.DATABASE_SQLSERVER, null)));
    }

    @Test
    public void finalSuperclassCallStillStartsTransactions()
            throws Exception {
        AtomicInteger isolationChanges = new AtomicInteger();
        AtomicBoolean autoCommitDisabled = new AtomicBoolean();
        Connection connection =
                connection(isolationChanges, autoCommitDisabled);
        KingbaseDataSourceRegistry registry =
                new StubRegistry(dataSource(connection), null);
        DemoSQLExecutor executor = new TestExecutor(context(
                Collections.emptyMap(), registry, new AtomicInteger()));
        executor.setTransactionIsolation(
                Connection.TRANSACTION_SERIALIZABLE);
        Connection actual = executor.getConnection(config(
                SQLConfig.DATABASE_KINGBASE_MYSQL, null));

        assertSame(connection, actual);
        assertEquals(1, isolationChanges.get());
        assertTrue(autoCommitDisabled.get());
    }

    private static DemoSQLConfig config(
            String database, String datasource) {
        DemoSQLConfig config = new DemoSQLConfig();
        config.setDatabase(database);
        config.setDatasource(datasource);
        return config;
    }

    private static ApplicationContext context(
            Map<String, DruidDataSource> dataSources,
            KingbaseDataSourceRegistry registry,
            AtomicInteger registryLookups) {
        return (ApplicationContext) Proxy.newProxyInstance(
                DemoSQLExecutorRoutingTest.class.getClassLoader(),
                new Class<?>[]{ApplicationContext.class},
                (proxy, method, args) -> {
                    if ("getBean".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && args[0] == KingbaseDataSourceRegistry.class) {
                        registryLookups.incrementAndGet();
                        return registry;
                    }
                    if ("getBeansOfType".equals(method.getName())) {
                        return dataSources;
                    }
                    if ("toString".equals(method.getName())) {
                        return "DemoSQLExecutorRoutingTestContext";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static DruidDataSource druidDataSource(
            AtomicInteger connections) {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl("jdbc:routing-test:success");
        dataSource.setDriver(new TestDriver(connections, false));
        dataSource.setInitialSize(0);
        dataSource.setMaxActive(1);
        return dataSource;
    }

    private static DataSource dataSource(Connection connection) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public Connection getConnection(
                    String username, String password) {
                return connection;
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger()
                    throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private static Connection connection(
            AtomicInteger isolationChanges,
            AtomicBoolean autoCommitDisabled) {
        return (Connection) Proxy.newProxyInstance(
                DemoSQLExecutorRoutingTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if ("setTransactionIsolation".equals(method.getName())) {
                        isolationChanges.incrementAndGet();
                        return null;
                    }
                    if ("setAutoCommit".equals(method.getName())) {
                        autoCommitDisabled.set(Boolean.FALSE.equals(args[0]));
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "RoutingTestConnection";
                    }
                    return defaultValue(method.getReturnType());
                });
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

    private static void close(Map<String, DruidDataSource> dataSources) {
        for (DruidDataSource dataSource : dataSources.values()) {
            dataSource.close();
        }
    }

    private static class TestDriver implements Driver {
        private final AtomicInteger connections;
        private final boolean fail;

        private TestDriver(AtomicInteger connections, boolean fail) {
            this.connections = connections;
            this.fail = fail;
        }

        @Override
        public Connection connect(String url, Properties info)
                throws SQLException {
            connections.incrementAndGet();
            if (fail) {
                throw new SQLException("expected pooled connection failure");
            }
            return connection(new AtomicInteger(), new AtomicBoolean());
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:routing-test:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(
                String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger()
                throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    private static class TestExecutor extends DemoSQLExecutor {
        private final ApplicationContext applicationContext;

        private TestExecutor(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        @Override
        protected ApplicationContext getApplicationContext() {
            return applicationContext;
        }
    }

    private static class StubRegistry
            extends KingbaseDataSourceRegistry {
        private final DataSource dataSource;
        private final SQLException failure;

        private StubRegistry(
                DataSource dataSource, SQLException failure) {
            super(
                    emptyDataSource(),
                    emptyDataSource(),
                    emptyDataSource(),
                    SQLConfig.DATABASE_KINGBASE_MYSQL,
                    false,
                    false);
            this.dataSource = dataSource;
            this.failure = failure;
        }

        @Override
        public DataSource getVerifiedDataSource(String database)
                throws SQLException {
            if (failure != null) {
                throw failure;
            }
            return dataSource;
        }

        private static DataSource emptyDataSource() {
            return dataSource(connection(
                    new AtomicInteger(), new AtomicBoolean()));
        }
    }

    private static class InvalidFallbackConfig extends DemoSQLConfig {
        private InvalidFallbackConfig() {
            setDatabase(SQLConfig.DATABASE_MYSQL);
            setDatasource("DRUID");
        }

        @Override
        public String gainDBUri() {
            return "jdbc:no-such-driver:routing-test";
        }

        @Override
        public String gainDBAccount() {
            return "";
        }

        @Override
        public String gainDBPassword() {
            return "";
        }
    }
}
