#!/usr/bin/env bash
set -euo pipefail

OWNER="${OWNER:-toddychen}"
RELEASE="${RELEASE:-service-playground}"
NAMESPACE="${NAMESPACE:-playground-dev}"
CHART_PATH="${CHART_PATH:-infra/helm/playground}"
VALUES_KIND_PATH="${VALUES_KIND_PATH:-infra/helm/playground/values-kind.yaml}"

SHA="${1:-${SHA:-}}"
if [[ -z "${SHA}" ]]; then
  echo "Usage: $0 <git-sha>"
  echo "Example: $0 082c5528152e9cf1137aac7ff79cfc1c96218d10"
  exit 1
fi

echo "==> Deploying ${RELEASE} to namespace ${NAMESPACE}"
echo "    image: ghcr.io/${OWNER}/service-playground:${SHA}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}" \
  -f "${VALUES_KIND_PATH}" \
  --set image.repository="ghcr.io/${OWNER}/service-playground" \
  --set image.tag="${SHA}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}" --timeout=180s

echo "==> Pods"
kubectl -n "${NAMESPACE}" get pods -o wide

echo "==> Service (NodePort)"
kubectl -n "${NAMESPACE}" get svc "${RELEASE}" || true

NODE_PORT="$(kubectl -n "${NAMESPACE}" get svc "${RELEASE}" -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || true)"
if [[ -n "${NODE_PORT}" ]]; then
  echo "==> Curl via NodePort"
  echo "curl \"http://127.0.0.1:${NODE_PORT}/api/echo?message=hello&from=kind\""
  echo "curl \"http://127.0.0.1:${NODE_PORT}/actuator/health/readiness\""
else
  echo "==> NodePort not found yet. You can port-forward instead:"
  echo "kubectl -n ${NAMESPACE} port-forward svc/${RELEASE} 8080:8080"
fi

