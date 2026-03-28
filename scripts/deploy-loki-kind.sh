#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-loki}"
NAMESPACE="${NAMESPACE:-observability}"
CHART_PATH="${CHART_PATH:-infra/helm/loki}"

echo "==> Deploying Loki to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}" --timeout=60s

echo "==> Loki is ready"
echo "    In-cluster:  http://loki.${NAMESPACE}:3100"
