#!/usr/bin/env bash
# Apply pending SQL migrations (services/postgres/init/NN_*.sql, shipped in the
# bundle under migrations/) to RDS before the new jars start. Services run with
# ddl-auto=validate, so a jar that needs a new table must never start first.
#
# - Tracks applied files in public.schema_migrations (filename, sha256, time).
# - First run on an existing database: every file numbered <= MIGRATION_BASELINE
#   is recorded as already applied (production was migrated by hand up to it).
# - Takes a pg_dump backup before applying anything; no backup → no migration.
# - Each file runs with ON_ERROR_STOP; the first failure aborts the deploy
#   before any service is restarted, so the old jars keep serving.
# - A recorded file whose content changed is reported, never re-run.
#
# Needs DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD in the environment.
set -euo pipefail

MIGRATIONS_DIR="${1:?usage: run-migrations.sh <migrations-dir>}"
BASELINE="${MIGRATION_BASELINE:-115}"
BACKUP_DIR="${MIGRATION_BACKUP_DIR:-/opt/repair-shop-saas/backups}"
KEEP_BACKUPS="${MIGRATION_KEEP_BACKUPS:-5}"

export PGHOST="$DB_HOST" PGPORT="${DB_PORT:-5432}" PGDATABASE="$DB_NAME" PGUSER="$DB_USER" PGPASSWORD="$DB_PASSWORD"
export PGSSLMODE="${PGSSLMODE:-require}" PGCONNECT_TIMEOUT=15

if ! command -v psql >/dev/null 2>&1 || ! command -v pg_dump >/dev/null 2>&1; then
  echo "Installing PostgreSQL client..."
  sudo dnf install -y -q postgresql16 >/dev/null
fi

q() { psql -X -q -At -v ON_ERROR_STOP=1 -c "$1"; }
num() { local n="${1%%_*}"; echo $((10#$n)); }

# pg_dump refuses a server newer than itself (RDS was upgraded to 18 while the
# box had the 16 client). Install the client matching the server's major.
SERVER_MAJOR=$(q "SHOW server_version_num")
SERVER_MAJOR=$((SERVER_MAJOR / 10000))
CLIENT_MAJOR=$(pg_dump --version | grep -oE '[0-9]+' | head -1)
if [[ "$CLIENT_MAJOR" -lt "$SERVER_MAJOR" ]]; then
  echo "Upgrading PostgreSQL client $CLIENT_MAJOR -> $SERVER_MAJOR to match the server..."
  sudo dnf install -y -q --allowerasing "postgresql${SERVER_MAJOR}" >/dev/null \
    || { echo "ERROR: postgresql${SERVER_MAJOR} client not installable; no backup possible, not migrating." >&2; exit 1; }
  hash -r
  CLIENT_MAJOR=$(pg_dump --version | grep -oE '[0-9]+' | head -1)
  [[ "$CLIENT_MAJOR" -ge "$SERVER_MAJOR" ]] \
    || { echo "ERROR: pg_dump is still $CLIENT_MAJOR (server $SERVER_MAJOR); not migrating." >&2; exit 1; }
fi

q "CREATE TABLE IF NOT EXISTS public.schema_migrations (
     filename   VARCHAR(200) PRIMARY KEY,
     checksum   VARCHAR(64)  NOT NULL,
     applied_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
     baseline   BOOLEAN      NOT NULL DEFAULT FALSE)" >/dev/null

mapfile -t FILES < <(cd "$MIGRATIONS_DIR" && ls | grep -E '^[0-9]+_.*\.sql$' | sort -t_ -k1,1n)
[[ ${#FILES[@]} -gt 0 ]] || { echo "No migration files in $MIGRATIONS_DIR"; exit 1; }

# Baseline once: an existing database with no history yet.
if [[ "$(q "SELECT count(*) FROM public.schema_migrations")" == "0" && "$(q "SELECT to_regclass('public.shops') IS NOT NULL")" == "t" ]]; then
  echo "Baselining existing database at migration $BASELINE"
  for f in "${FILES[@]}"; do
    [[ $(num "$f") -le $BASELINE ]] || continue
    sum=$(sha256sum "$MIGRATIONS_DIR/$f" | cut -d' ' -f1)
    q "INSERT INTO public.schema_migrations(filename, checksum, baseline) VALUES ('$f', '$sum', TRUE) ON CONFLICT DO NOTHING" >/dev/null
  done
fi

PENDING=()
for f in "${FILES[@]}"; do
  sum=$(sha256sum "$MIGRATIONS_DIR/$f" | cut -d' ' -f1)
  have=$(q "SELECT checksum FROM public.schema_migrations WHERE filename = '$f'")
  if [[ -z "$have" ]]; then
    PENDING+=("$f")
  elif [[ "$have" != "$sum" ]]; then
    echo "WARNING: $f changed after it was applied (not re-run)."
  fi
done

if [[ ${#PENDING[@]} -eq 0 ]]; then
  echo "Migrations: database is up to date."
  exit 0
fi
echo "Pending migrations: ${PENDING[*]}"

sudo mkdir -p "$BACKUP_DIR"
backup="$BACKUP_DIR/pre-migrate-$(date -u +%Y%m%dT%H%M%SZ).dump"
echo "Backing up database to $backup"
pg_dump -Fc -f /tmp/pre-migrate.dump
sudo mv /tmp/pre-migrate.dump "$backup"
[[ -s "$backup" ]] || { echo "ERROR: backup is empty; not migrating." >&2; exit 1; }
ls -1t "$BACKUP_DIR"/pre-migrate-*.dump 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r sudo rm -f

for f in "${PENDING[@]}"; do
  echo "Applying $f"
  if ! psql -X -q -v ON_ERROR_STOP=1 -f "$MIGRATIONS_DIR/$f"; then
    echo "ERROR: migration $f failed. Deploy stopped; services were not restarted." >&2
    echo "Restore if needed: pg_restore --clean --if-exists -d \$DB_NAME $backup" >&2
    exit 1
  fi
  sum=$(sha256sum "$MIGRATIONS_DIR/$f" | cut -d' ' -f1)
  q "INSERT INTO public.schema_migrations(filename, checksum) VALUES ('$f', '$sum')" >/dev/null
done
echo "Migrations applied: ${#PENDING[@]}"
