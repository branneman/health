#!/usr/bin/env bash
# Seed the two automated-test accounts into the local dev database.
# Reads API_TEST_PASSWORD and DATABASE_URL from .env — never commits credentials.
#
# Usage (from repo root):
#   bash local-db-seed/seed-test-accounts.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found" >&2
  exit 1
fi

# Load .env
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${API_TEST_PASSWORD:?API_TEST_PASSWORD must be set in .env}"
: "${DATABASE_URL:?DATABASE_URL must be set in .env}"

HASH=$(python3 -c "import bcrypt; print(bcrypt.hashpw('${API_TEST_PASSWORD}'.encode(), bcrypt.gensalt(12)).decode())")

psql "$DATABASE_URL" <<SQL
INSERT INTO users (id, username, password_hash) VALUES
    ('00000000-0000-0000-0000-000000000010', 'test+api@bran.name',  '${HASH}'),
    ('00000000-0000-0000-0000-000000000020', 'test+e2e@bran.name', '${HASH}')
ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash;
SQL

echo "Test accounts seeded."
