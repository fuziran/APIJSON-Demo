package apijson.boot;

import apijson.Log;
import apijson.demo.DemoSQLConfig;
import apijson.orm.SQLConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds each Kingbase SQL dialect to a data source whose server mode has been
 * verified. Native database routing remains outside this registry.
 */
@Component
public class KingbaseDataSourceRegistry implements InitializingBean {

    private static final String TAG = "KingbaseDataSourceRegistry";

    private final Map<String, DataSource> dataSources;
    private final Set<String> verifiedDatabases = ConcurrentHashMap.newKeySet();
    private final boolean verifyOnStartup;
    private final boolean verifyAllOnStartup;

    public KingbaseDataSourceRegistry(
            @Qualifier("kingbaseMysqlDataSource") DataSource kingbaseMysqlDataSource,
            @Qualifier("kingbaseOracleDataSource") DataSource kingbaseOracleDataSource,
            @Qualifier("kingbaseSqlserverDataSource") DataSource kingbaseSqlserverDataSource,
            @Value("${apijson.kingbase.verify-on-startup:true}") boolean verifyOnStartup,
            @Value("${apijson.kingbase.verify-all-on-startup:false}") boolean verifyAllOnStartup) {
        Map<String, DataSource> configuredDataSources = new LinkedHashMap<>();
        configuredDataSources.put(SQLConfig.DATABASE_KINGBASE_MYSQL, kingbaseMysqlDataSource);
        configuredDataSources.put(SQLConfig.DATABASE_KINGBASE_ORACLE, kingbaseOracleDataSource);
        configuredDataSources.put(SQLConfig.DATABASE_KINGBASE_SQLSERVER, kingbaseSqlserverDataSource);
        this.dataSources = Collections.unmodifiableMap(configuredDataSources);
        this.verifyOnStartup = verifyOnStartup;
        this.verifyAllOnStartup = verifyAllOnStartup;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!verifyOnStartup) {
            return;
        }

        if (verifyAllOnStartup) {
            for (String database : dataSources.keySet()) {
                getVerifiedDataSource(database);
            }
            return;
        }

        String defaultDatabase = DemoSQLConfig.getConfiguredDefaultDatabase();
        if (dataSources.containsKey(defaultDatabase)) {
            getVerifiedDataSource(defaultDatabase);
        }
    }

    public DataSource getVerifiedDataSource(String database) throws SQLException {
        String normalizedDatabase;
        try {
            normalizedDatabase = KingbaseModeDetector.normalizeConfiguredDatabase(database);
        }
        catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), e);
        }

        DataSource dataSource = dataSources.get(normalizedDatabase);
        if (dataSource == null) {
            throw new SQLException("No Kingbase data source is registered for " + normalizedDatabase);
        }

        if (!verifiedDatabases.contains(normalizedDatabase)) {
            synchronized (dataSource) {
                if (!verifiedDatabases.contains(normalizedDatabase)) {
                    verifyDataSource(normalizedDatabase, dataSource);
                    verifiedDatabases.add(normalizedDatabase);
                }
            }
        }

        return dataSource;
    }

    private void verifyDataSource(String expectedDatabase, DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String actualDatabase = KingbaseModeDetector.verify(connection, expectedDatabase);
            Log.i(TAG, "Verified Kingbase data source mode: " + actualDatabase);
        }
        catch (SQLException e) {
            throw new SQLException("Failed to verify Kingbase data source for "
                    + expectedDatabase + ": " + e.getMessage(), e);
        }
    }
}
