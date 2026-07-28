import apijson.boot.KingbaseModeDetector;
import apijson.orm.SQLConfig;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dependency-free unit test for Kingbase compatibility-mode detection.
 */
public class KingbaseModeDetectorTest {

    public static void main(String[] args) throws Exception {
        check(SQLConfig.DATABASE_KINGBASE_MYSQL.equals(
                KingbaseModeDetector.normalizeConfiguredDatabase("kingbase-mysql")),
                "configured MySQL mode normalization failed");
        check(SQLConfig.DATABASE_KINGBASE_ORACLE.equals(
                KingbaseModeDetector.fromServerMode(" ORACLE ")),
                "Oracle server mode mapping failed");
        check(SQLConfig.DATABASE_KINGBASE_SQLSERVER.equals(
                KingbaseModeDetector.fromServerMode("sql_server")),
                "SQL Server server mode mapping failed");

        Connection oracleConnection = connectionReturning("oracle");
        check(SQLConfig.DATABASE_KINGBASE_ORACLE.equals(
                KingbaseModeDetector.detect(oracleConnection)),
                "SHOW database_mode detection failed");

        KingbaseModeDetector.verify(connectionReturning("mysql"),
                SQLConfig.DATABASE_KINGBASE_MYSQL);

        boolean mismatchRejected = false;
        try {
            KingbaseModeDetector.verify(connectionReturning("oracle"),
                    SQLConfig.DATABASE_KINGBASE_MYSQL);
        }
        catch (SQLException expected) {
            mismatchRejected = expected.getMessage().contains("mismatch");
        }
        check(mismatchRejected, "mode mismatch was not rejected");

        System.out.println("KINGBASE_MODE_DETECTOR_TEST_PASS");
    }

    private static Connection connectionReturning(String serverMode) {
        AtomicBoolean firstRow = new AtomicBoolean(true);
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                KingbaseModeDetectorTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return firstRow.getAndSet(false);
                    }
                    if ("getString".equals(method.getName())) {
                        return serverMode;
                    }
                    return defaultValue(method.getReturnType());
                });

        Statement statement = (Statement) Proxy.newProxyInstance(
                KingbaseModeDetectorTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        check(args != null && args.length == 1
                                        && "SHOW database_mode".equals(args[0]),
                                "unexpected detection SQL");
                        return resultSet;
                    }
                    return defaultValue(method.getReturnType());
                });

        return (Connection) Proxy.newProxyInstance(
                KingbaseModeDetectorTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return statement;
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
