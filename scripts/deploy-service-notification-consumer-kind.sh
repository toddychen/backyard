#!/usr/bin/env bash
set -euo pipefail

OWNER="${OWNER:-toddychen}"
RELEASE="${RELEASE:-service-notification-consumer}"
NAMESPACE="${NAMESPACE:-notification}"
CHART_PATH="${CHART_PATH:-infra/helm/notification-consumer}"
VALUES_KIND_PATH="${VALUES_KIND_PATH:-infra/helm/notification-consumer/values-kind.yaml}"

SHA="${1:-${SHA:-}}"
if [[ -z "${SHA}" ]]; then
  echo "Usage: $0 <git-sha>"
  echo "Example: $0 082c5528152e9cf1137aac7ff79cfc1c96218d10"
  exit 1
fi

echo "==> Deploying ${RELEASE} to namespace ${NAMESPACE}"
echo "    image: ghcr.io/${OWNER}/service-notification-consumer:${SHA}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}" \
  -f "${VALUES_KIND_PATH}" \
  --set image.repository="ghcr.io/${OWNER}/service-notification-consumer" \
  --set image.tag="${SHA}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}" --timeout=180s

echo "==> Pods"
kubectl -n "${NAMESPACE}" get pods -o wide
