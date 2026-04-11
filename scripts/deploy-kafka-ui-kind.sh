#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-kafka-ui}"
NAMESPACE="${NAMESPACE:-kafka}"
CHART_PATH="${CHART_PATH:-infra/helm/kafka-ui}"

echo "==> Deploying kafka-ui to namespace ${NAMESPACE}"

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "deployment/${RELEASE}" --timeout=120s

echo "==> kafka-ui is ready"
echo "    From host: http://localhost:30880"
