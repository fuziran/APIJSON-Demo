package apijson.demo;

import apijson.RequestMethod;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DemoSQLConfigDefaultTest {

    @Test
    public void avgCommentId4UsesPortableFixedScaleExpression() {
        DemoSQLConfig config = new DemoSQLConfig(
                RequestMethod.GET, "Comment");

        assertEquals(
                "CAST(AVG(id) AS DECIMAL(38,4)) AS avgId",
                config.parseSQLExpression(
                        "@column", "avgCommentId4", true, true));
    }

    @Test
    public void noKingbaseEnvironmentKeepsMysqlAndSysDefaults()
            throws Exception {
        String javaExecutable = new File(
                System.getProperty("java.home"),
                "bin" + File.separator + "java").getAbsolutePath();
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable,
                "-cp",
                classPath,
                DefaultValueProbe.class.getName());
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().remove("APIJSON_KINGBASE_ENABLED");
        processBuilder.environment().remove("KINGBASE_MODE");

        Process process = processBuilder.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        assertTrue("default-value probe timed out",
                process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
        assertTrue(output, output.contains("DEFAULTS=MYSQL|sys"));
    }

    public static class DefaultValueProbe {
        public static void main(String[] args) {
            System.out.println(
                    "DEFAULTS="
                            + DemoSQLConfig.getConfiguredDefaultDatabase()
                            + "|" + DemoSQLConfig.DEFAULT_SCHEMA);
        }
    }
}
