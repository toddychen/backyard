#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-promtail}"
NAMESPACE="${NAMESPACE:-observability}"
CHART_PATH="${CHART_PATH:-infra/helm/promtail}"

echo "==> Deploying Promtail DaemonSet to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}"

echo "==> Waiting for DaemonSet rollout"
kubectl -n "${NAMESPACE}" rollout status "daemonset/${RELEASE}" --timeout=60s

echo "==> Promtail is ready"
echo "    DaemonSet pods (one per node):"
kubectl -n "${NAMESPACE}" get pods -l "app.kubernetes.io/name=promtail" -o wide
