# Kingbase three-mode JDBC smoke test

This test is deliberately independent from Spring Boot. It uses the same Kingbase JDBC JAR as
the demo and records server/driver metadata, SQL, parameter metadata, result metadata, generated
keys, CRUD, pagination, transactions, scrollable result sets, and resource closing behavior.

## Configuration

Set all nine connection variables. No credential has a built-in default.

```bash
export KINGBASE_MYSQL_URL='jdbc:kingbase8://host:54321/apijson?currentSchema=public'
export KINGBASE_MYSQL_USERNAME='...'
export KINGBASE_MYSQL_PASSWORD='...'
export KINGBASE_ORACLE_URL='jdbc:kingbase8://host:54322/apijson?currentSchema=PUBLIC'
export KINGBASE_ORACLE_USERNAME='...'
export KINGBASE_ORACLE_PASSWORD='...'
export KINGBASE_SQLSERVER_URL='jdbc:kingbase8://host:54323/apijson?currentSchema=public'
export KINGBASE_SQLSERVER_USERNAME='...'
export KINGBASE_SQLSERVER_PASSWORD='...'
```

Run all modes:

```bash
./kingbase-smoke/run.sh
```

After the shallow smoke test passes, run the real-JDBC data-type matrix:

```bash
./kingbase-smoke/run-type-matrix.sh
```

The type matrix covers exact and floating-point numbers, the JavaScript safe-integer boundary,
Boolean/BIT, Unicode text, JSON/JSONB, UUID/UNIQUEIDENTIFIER, temporal values, binary/RAW,
Blob, Clob, SQLXML, SQL ARRAY, null and empty values. It records the binding method, parameter and
result metadata, actual JDBC Java class, stored value and a JSON-safe representation. Each case uses
the `apijson_kb_types` table and removes it in a `finally` block.

Reports are written to `kingbase-smoke/results/`. The tests use only the
`apijson_kb_smoke` and `apijson_kb_types` tables and drop them after each run. To prepare or clean the smoke table manually, run the
matching file under `sql/<mode>/` in KStudio.

The process exits non-zero if any mode fails. Passwords are never written to the report.
