package apijson.boot;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KingbaseSpringIsolationTest {

    @Test
    public void disabledKingbaseCreatesNeitherDataSourcesNorRegistry() {
        try (AnnotationConfigApplicationContext context =
                     contextWith(properties(false))) {
            assertTrue(context.containsBean("druidDataSource"));
            assertTrue(context.containsBean("druidTestDataSource"));
            assertTrue(context.containsBean("druidOnlineDataSource"));

            assertFalse(context.containsBean("kingbaseMysqlDataSource"));
            assertFalse(context.containsBean("kingbaseOracleDataSource"));
            assertFalse(context.containsBean("kingbaseSqlserverDataSource"));
            assertTrue(context.getBeansOfType(
                    KingbaseDataSourceRegistry.class).isEmpty());
        }
    }

    @Test
    public void enabledKingbaseCreatesAndBindsAllThreeDataSources() {
        Map<String, Object> properties = properties(true);
        properties.put("KINGBASE_MODE", "KINGBASE_MYSQL");
        properties.put("apijson.kingbase.verify-on-startup", "false");
        properties.put(
                "spring.datasource.kingbase-mysql.url",
                "jdbc:kingbase8://mysql-mode/test");
        properties.put(
                "spring.datasource.kingbase-oracle.url",
                "jdbc:kingbase8://oracle-mode/test");
        properties.put(
                "spring.datasource.kingbase-sqlserver.url",
                "jdbc:kingbase8://sqlserver-mode/test");

        try (AnnotationConfigApplicationContext context =
                     contextWith(properties)) {
            assertEquals(
                    "jdbc:kingbase8://mysql-mode/test",
                    dataSource(context, "kingbaseMysqlDataSource").getUrl());
            assertEquals(
                    "jdbc:kingbase8://oracle-mode/test",
                    dataSource(context, "kingbaseOracleDataSource").getUrl());
            assertEquals(
                    "jdbc:kingbase8://sqlserver-mode/test",
                    dataSource(context, "kingbaseSqlserverDataSource").getUrl());
            assertEquals(
                    1,
                    context.getBeansOfType(
                            KingbaseDataSourceRegistry.class).size());
        }
    }

    private static Map<String, Object> properties(boolean enabled) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("apijson.kingbase.enabled", Boolean.toString(enabled));
        return properties;
    }

    private static AnnotationConfigApplicationContext contextWith(
            Map<String, Object> properties) {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("kingbase-test", properties));
        context.register(TestConfiguration.class);
        context.refresh();
        return context;
    }

    private static DruidDataSource dataSource(
            AnnotationConfigApplicationContext context, String name) {
        return context.getBean(name, DruidDataSource.class);
    }

    @Configuration
    @EnableConfigurationProperties
    @Import({
            DemoDataSourceConfig.class,
            KingbaseDataSourceRegistry.class
    })
    static class TestConfiguration {
    }
}
