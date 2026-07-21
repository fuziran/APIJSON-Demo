# Kingbase three-mode JDBC data-type matrix result

Date: 2026-07-19 (Asia/Shanghai)

## Outcome

The real-JDBC type matrix completed with **71 passing cases and zero failures** across
`KINGBASE-MYSQL`, `KINGBASE-ORACLE` and `KINGBASE-SQLSERVER`. The final run removed
`apijson_kb_types` from every instance.

Environment: KingbaseES 12.1 / `V009R001C010`, Kingbase8 JDBC Driver `V009R001C010`
(file `kingbase8-9.0.0.jar`), JDBC 4.2, OpenJDK 17.0.19.

Final raw evidence (ignored by Git because it is environment-specific):
`results/type-matrix-20260719T113008Z.log`.

## Covered matrix

- Integer maximum and `BIGINT` value `9007199254740993`, beyond JavaScript's safe integer range.
- `DECIMAL(38,10)`, `NUMBER(38,0)`, double precision, Boolean and SQL Server `BIT`.
- Unicode `VARCHAR`/`VARCHAR2`/`NVARCHAR`, `TEXT`, `LONGTEXT`, CLOB-equivalent streams.
- JSON, JSONB, UUID and SQL Server `UNIQUEIDENTIFIER`.
- DATE, TIME where supported, TIMESTAMP/DATETIME2 and time-zone timestamp.
- BYTEA/VARBINARY, Blob-equivalent streams, SQLXML and integer arrays where supported.
- SQL `NULL`, empty string and empty binary values.

For every executed case the evidence records DDL, PreparedValueList/binding method,
`ParameterMetaData`, `ResultSetMetaData`, declared and actual Java type, raw JDBC value,
JSON-safe value, and round-trip assertion.

## Confirmed compatibility differences

| Mode | Evidence-backed behavior |
| --- | --- |
| MySQL | JSON parameters may be reported by `ParameterMetaData` as JDBC `DATE` / type `year`; returned JSON is a `KBobject` and JSON DDL normalizes to JSONB in metadata. `TIMESTAMP` fractional seconds were truncated in this driver/server combination. |
| Oracle | Standalone `TIME` and the `RAW` alias are unavailable on this instance; DATE/TIMESTAMP and BYTEA are the working forms. Empty VARCHAR and empty BYTEA values read back as `NULL`, matching Oracle empty-value semantics. |
| SQL Server | `TIMESTAMP` means ROWVERSION; `DATETIME2` is the working date-time type. BLOB/CLOB aliases are unavailable; `VARBINARY(MAX)` and `VARCHAR(MAX)` are the working stream targets. SQL ARRAY DDL is rejected. `UNIQUEIDENTIFIER` accepts a string binding and returns a `KBobject`. |

## Scope still open

This matrix establishes direct JDBC storage and retrieval behavior. A subsequent APIJSON endpoint
test should exercise the same values through `AbstractSQLExecutor.mapResultValue(...)` and final
JSON serialization. Vendor extension families (network, geometry, enum/domain) and very large LOB
streaming also remain to be added before Gate 2 is considered complete.
