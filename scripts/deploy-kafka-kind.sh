#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-kafka}"
NAMESPACE="${NAMESPACE:-kafka}"
CHART_PATH="${CHART_PATH:-infra/helm/kafka}"
VALUES_FILE="${VALUES_FILE:-infra/helm/kafka/values-kind.yaml}"

echo "==> Deploying Kafka to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}" \
  -f "${VALUES_FILE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "statefulset/${RELEASE}" --timeout=120s

echo "==> Kafka is ready"
echo "    In-cluster: kafka-0.kafka.${NAMESPACE}.svc.cluster.local:9092"
echo "    From host:  localhost:30092"
