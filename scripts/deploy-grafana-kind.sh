#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-grafana}"
NAMESPACE="${NAMESPACE:-observability}"
CHART_PATH="${CHART_PATH:-infra/helm/grafana}"

echo "==> Deploying Grafana to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}" --timeout=60s

echo "==> Grafana is ready"
echo "    UI:       http://127.0.0.1:30030"
echo "    Login:    admin / admin"
