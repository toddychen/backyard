#!/usr/bin/env bash
# Cassandra schema deploy + incremental migration runner.
#
# Usage:
#   ./infra/cassandra/migrate.sh [host] [port] [migrations_dir]
#
# Defaults: host=localhost  port=30942  (kind NodePort)
#           migrations_dir=$script_dir/migrations
#
# What it does:
#   1. Applies schema.cql  (all CREATE … IF NOT EXISTS — safe to re-run)
#   2. Loads all applied versions from chat.schema_migrations in one query
#   3. For each *.cql file in migrations/ (sorted by name):
#        - Skips if already recorded
#        - Applies and records it if not
#
# Error handling:
#   set -euo pipefail stops the script on any failure. If a migration file
#   fails, the version is never recorded and the next run will retry.
#   If the migration partially applied, manual inspection is required
#   regardless — no scripting can recover from broken schema state.

set -euo pipefail

HOST="${1:-localhost}"
PORT="${2:-30942}"
DIR="$(cd "$(dirname "$0")" && pwd)"
MIGRATIONS_DIR="${3:-$DIR/migrations}"

CQLSH="cqlsh $HOST $PORT"

echo "==> Cassandra: $HOST:$PORT"

# ── Step 1: apply base schema (idempotent) ────────────────────────────────────
echo "==> Applying schema.cql ..."
$CQLSH -f "$DIR/schema.cql"
echo "    schema.cql OK"

# ── Step 2: load full migrations table in one query ──────────────────────────
if [ ! -d "$MIGRATIONS_DIR" ] || [ -z "$(ls "$MIGRATIONS_DIR"/*.cql 2>/dev/null)" ]; then
    echo "==> No migration files found — done."
    exit 0
fi

echo "==> Loading applied migrations ..."

# cqlsh output rows look like:   V001_add_edited_to_replies.cql
applied=$($CQLSH -e "SELECT version FROM chat.schema_migrations;" 2>/dev/null \
    | grep -E '^\s+V[0-9]+' | tr -d ' ' \
    || true)

echo "==> Checking migrations ..."

for file in $(ls "$MIGRATIONS_DIR"/*.cql | sort); do
    version="$(basename "$file")"

    if echo "$applied" | grep -qxF "$version"; then
        echo "    [skip]  $version"
        continue
    fi

    echo "    [apply] $version ..."
    if ! $CQLSH -f "$file"; then
        echo ""
        echo "  *** MIGRATION FAILED: $version ***"
        echo "      The schema change above was not recorded."
        echo "      Inspect the cqlsh error, fix manually if needed, then re-run."
        exit 1
    fi
    echo "    [ok]    $version — schema change applied successfully"

    if ! $CQLSH -e \
        "INSERT INTO chat.schema_migrations (version, applied_at)
         VALUES ('$version', toTimestamp(now()));"; then
        echo ""
        echo "  *** RECORDING FAILED: $version ***"
        echo "      Schema change was applied but could not be written to schema_migrations."
        echo "      Fix the recording issue and insert the row manually:"
        echo "      INSERT INTO chat.schema_migrations (version, applied_at)"
        echo "        VALUES ('$version', toTimestamp(now()));"
        exit 1
    fi
    echo "    [done]  $version — recorded in schema_migrations"
done

echo "==> Migration complete."
