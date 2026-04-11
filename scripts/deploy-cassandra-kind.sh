#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-cassandra}"
NAMESPACE="${NAMESPACE:-cassandra}"
CHART_PATH="${CHART_PATH:-infra/helm/cassandra}"
VALUES_FILE="${VALUES_FILE:-infra/helm/cassandra/values-kind.yaml}"
SCHEMA_FILE="${SCHEMA_FILE:-infra/cassandra/schema.cql}"

echo "==> Deploying Cassandra to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}" \
  -f "${VALUES_FILE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "statefulset/${RELEASE}" --timeout=180s

echo "==> Applying schema"
kubectl -n "${NAMESPACE}" cp "${SCHEMA_FILE}" "${RELEASE}-0:/tmp/schema.cql"
kubectl -n "${NAMESPACE}" exec "${RELEASE}-0" -- cqlsh -f /tmp/schema.cql

echo "==> Cassandra is ready"
echo "    In-cluster: cassandra.${NAMESPACE}:9042"
echo "    From host:  cqlsh 127.0.0.1 30942"
