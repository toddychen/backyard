#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-redis}"

echo "==> All keys in Redis:"
kubectl -n "${NAMESPACE}" exec deploy/redis -- redis-cli keys "*"
