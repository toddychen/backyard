#!/usr/bin/env bash
set -euo pipefail

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-30306}"
USER="${MYSQL_USER:-playground}"
PASSWORD="${MYSQL_PASSWORD:-playground-kind}"
DATABASE="${DATABASE:-playground}"

mysql -h "${HOST}" -P "${PORT}" -u "${USER}" "-p${PASSWORD}" "${DATABASE}"
