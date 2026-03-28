#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-redis}"
NAMESPACE="${NAMESPACE:-redis}"
CHART_PATH="${CHART_PATH:-infra/helm/redis}"

echo "==> Deploying Redis to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}" --timeout=60s

echo "==> Redis is ready"
echo "    In-cluster: redis.${NAMESPACE}:6379"
echo "    From host:  redis-cli -h 127.0.0.1 -p 30379"
