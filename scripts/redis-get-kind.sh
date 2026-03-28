#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-redis}"
KEY="${1:-}"

if [[ -z "${KEY}" ]]; then
  echo "Usage: $0 <key>"
  echo "Example: $0 'game-details::nfl.g.123'"
  echo "To list all keys: ./scripts/redis-keys-kind.sh"
  exit 1
fi

echo "==> GET ${KEY}"
kubectl -n "${NAMESPACE}" exec deploy/redis -- redis-cli get "${KEY}"

echo ""
echo "==> TTL ${KEY} (seconds, -1=no expiry, -2=not found)"
kubectl -n "${NAMESPACE}" exec deploy/redis -- redis-cli ttl "${KEY}"
