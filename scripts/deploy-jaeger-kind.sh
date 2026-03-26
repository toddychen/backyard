#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-jaeger}"
NAMESPACE="${NAMESPACE:-observability}"
CHART_PATH="${CHART_PATH:-infra/helm/jaeger}"

echo "==> Deploying Jaeger all-in-one to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}" --timeout=60s

echo "==> Jaeger is ready"
echo "    UI:        http://127.0.0.1:30686"
echo "    OTLP HTTP: http://jaeger.${NAMESPACE}:4318/v1/traces  (in-cluster)"
