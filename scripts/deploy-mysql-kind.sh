#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-mysql}"
NAMESPACE="${NAMESPACE:-mysql}"
CHART_PATH="${CHART_PATH:-infra/helm/mysql}"
VALUES_FILE="${VALUES_FILE:-infra/helm/mysql/values-kind.yaml}"

echo "==> Deploying MySQL to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}" \
  -f "${VALUES_FILE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}" --timeout=90s

echo "==> MySQL is ready"
echo "    In-cluster: mysql.${NAMESPACE}:3306"
echo "    From host:  mysql -h 127.0.0.1 -P 30306 -u playground -pplayground-kind playground"
