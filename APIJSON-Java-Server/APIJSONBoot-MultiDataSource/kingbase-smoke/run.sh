#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DRIVER_JAR="${KINGBASE_DRIVER_JAR:-$MODULE_DIR/libs/kingbase8-9.0.0.jar}"
BUILD_DIR="$SCRIPT_DIR/build"
RESULT_DIR="${KINGBASE_SMOKE_RESULT_DIR:-$SCRIPT_DIR/results}"

if [[ ! -f "$DRIVER_JAR" ]]; then
  echo "Kingbase JDBC driver not found: $DRIVER_JAR" >&2
  exit 2
fi

required=(
  KINGBASE_MYSQL_URL KINGBASE_MYSQL_USERNAME KINGBASE_MYSQL_PASSWORD
  KINGBASE_ORACLE_URL KINGBASE_ORACLE_USERNAME KINGBASE_ORACLE_PASSWORD
  KINGBASE_SQLSERVER_URL KINGBASE_SQLSERVER_USERNAME KINGBASE_SQLSERVER_PASSWORD
)

for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Required environment variable is empty: $name" >&2
    exit 2
  fi
done

mkdir -p "$BUILD_DIR" "$RESULT_DIR"
javac -encoding UTF-8 -d "$BUILD_DIR" "$SCRIPT_DIR/src/KingbaseSmokeTest.java"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
report="$RESULT_DIR/smoke-$timestamp.log"
java -cp "$BUILD_DIR:$DRIVER_JAR" KingbaseSmokeTest | tee "$report"
echo "Report: $report"

