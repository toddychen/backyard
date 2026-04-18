#!/usr/bin/env bash
set -euo pipefail

# Starts the collab service and Vite dev server locally.
# Requires MySQL and Redis to be running (e.g. via kind port-forward or docker-compose).
#
# Usage: ./scripts/run-service-collab-dev.sh [--no-client]

COLLAB_DIR="$(cd "$(dirname "$0")/../node-services/collab" && pwd)"

# Install server deps if needed
if [[ ! -d "${COLLAB_DIR}/node_modules" ]]; then
  echo "==> Installing server dependencies"
  (cd "${COLLAB_DIR}" && npm install)
fi

# Install client deps if needed
if [[ ! -d "${COLLAB_DIR}/client/node_modules" ]]; then
  echo "==> Installing client dependencies"
  (cd "${COLLAB_DIR}/client" && npm install)
fi

echo "==> Starting Hocuspocus + Express on :2070"
(cd "${COLLAB_DIR}" && DATABASE_URL="mysql://collab:collab-kind@127.0.0.1:30306/collab" npm run dev) &
SERVER_PID=$!

if [[ "${1:-}" != "--no-client" ]]; then
  echo "==> Starting Vite dev server on :5173"
  (cd "${COLLAB_DIR}/client" && npm run dev) &
  CLIENT_PID=$!
fi

trap 'kill ${SERVER_PID} ${CLIENT_PID:-} 2>/dev/null || true' EXIT INT TERM

echo ""
echo "  Backend : http://localhost:2070"
echo "  Frontend: http://localhost:5173  (proxies /api and /ws to :2070)"
echo ""
echo "Press Ctrl-C to stop."
wait
