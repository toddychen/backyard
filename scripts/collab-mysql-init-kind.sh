#!/usr/bin/env bash
# One-time setup: create the 'collab' database and user in the running kind MySQL.
# Only needed when the cluster already exists (init-configmap.yaml handles fresh clusters).
set -euo pipefail

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-30306}"
ROOT_PASSWORD="${ROOT_PASSWORD:-root-kind}"
COLLAB_PASSWORD="${COLLAB_PASSWORD:-collab-kind}"

echo "==> Creating collab database and user in kind MySQL"

mysql -h "${HOST}" -P "${PORT}" -u root "-p${ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS collab;
CREATE USER IF NOT EXISTS 'collab'@'%' IDENTIFIED BY '${COLLAB_PASSWORD}';
GRANT ALL PRIVILEGES ON collab.* TO 'collab'@'%';
FLUSH PRIVILEGES;
SQL

echo "==> Done. Connect with:"
echo "    mysql -h 127.0.0.1 -P 30306 -u collab -p${COLLAB_PASSWORD} collab"
