#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-redis}"

echo "==> Flushing all Redis keys in namespace ${NAMESPACE}"
kubectl -n "${NAMESPACE}" exec deploy/redis -- redis-cli flushall
echo "==> Done"
