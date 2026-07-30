package apijson.boot;

import apijson.Log;
import apijson.orm.SQLConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Registers the Kingbase data sources and verifies that each server is running
 * in the compatibility mode represented by its APIJSON database type.
 *
 * <p>Native and other non-Kingbase data sources are intentionally outside this
 * registry.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "apijson.kingbase",
        name = "enabled",
        havingValue = "true")
public class KingbaseDataSourceRegistry implements InitializingBean {

    private static final String TAG = "KingbaseDataSourceRegistry";

    private final Map<String, DataSource> dataSources;
    private final Set<String> verifiedDatabases =
            ConcurrentHashMap.newKeySet();
    private final String configuredDatabase;
    private final boolean verifyOnStartup;
    private final boolean verifyAllOnStartup;

    public KingbaseDataSourceRegistry(
            @Qualifier("kingbaseMysqlDataSource")
            DataSource kingbaseMysqlDataSource,
            @Qualifier("kingbaseOracleDataSource")
            DataSource kingbaseOracleDataSource,
            @Qualifier("kingbaseSqlserverDataSource")
            DataSource kingbaseSqlserverDataSource,
            @Value("${KINGBASE_MODE:}") String configuredDatabase,
            @Value("${apijson.kingbase.verify-on-startup:true}")
            boolean verifyOnStartup,
            @Value("${apijson.kingbase.verify-all-on-startup:false}")
            boolean verifyAllOnStartup) {
        Map<String, DataSource> configuredDataSources =
                new LinkedHashMap<>();
        configuredDataSources.put(
                SQLConfig.DATABASE_KINGBASE_MYSQL,
                kingbaseMysqlDataSource);
        configuredDataSources.put(
                SQLConfig.DATABASE_KINGBASE_ORACLE,
                kingbaseOracleDataSource);
        configuredDataSources.put(
                SQLConfig.DATABASE_KINGBASE_SQLSERVER,
                kingbaseSqlserverDataSource);
        this.dataSources =
                Collections.unmodifiableMap(configuredDataSources);
        this.configuredDatabase = configuredDatabase;
        this.verifyOnStartup = verifyOnStartup;
        this.verifyAllOnStartup = verifyAllOnStartup;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        String normalizedConfiguredDatabase =
                normalizeStartupDatabase(configuredDatabase);

        if (!verifyOnStartup) {
            return;
        }

        if (verifyAllOnStartup) {
            for (String database : dataSources.keySet()) {
                getVerifiedDataSource(database);
            }
            return;
        }

        getVerifiedDataSource(normalizedConfiguredDatabase);
    }

    /**
     * Returns the data source registered for the supplied Kingbase database
     * type after verifying its real server compatibility mode.
     */
    public DataSource getVerifiedDataSource(String database)
            throws SQLException {
        String normalizedDatabase;
        try {
            normalizedDatabase =
                    KingbaseModeDetector.normalizeConfiguredDatabase(database);
        }
        catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), e);
        }

        DataSource dataSource = dataSources.get(normalizedDatabase);
        if (dataSource == null) {
            throw new SQLException(
                    "No Kingbase data source is registered for "
                            + normalizedDatabase);
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

    private static String normalizeStartupDatabase(String database) {
        if (database == null || database.trim().isEmpty()) {
            throw new IllegalStateException(
                    "KINGBASE_MODE is required when Kingbase is enabled");
        }

        try {
            return KingbaseModeDetector.normalizeConfiguredDatabase(database);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void verifyDataSource(
            String expectedDatabase, DataSource dataSource)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String actualDatabase =
                    KingbaseModeDetector.verify(
                            connection, expectedDatabase);
            Log.i(
                    TAG,
                    "Verified Kingbase data source mode: "
                            + actualDatabase);
        }
        catch (SQLException e) {
            throw new SQLException(
                    "Failed to verify Kingbase data source for "
                            + expectedDatabase + ": " + e.getMessage(),
                    e);
        }
    }
}
