#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-playground-dev}"
SECRET_NAME="playground-secrets"

if [[ -z "${GOOGLE_LANGUAGE_API_KEY:-}" ]]; then
  echo "Error: GOOGLE_LANGUAGE_API_KEY is not set."
  echo "Set it in your shell environment before running this script."
  exit 1
fi

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

kubectl create secret generic "${SECRET_NAME}" \
  --namespace "${NAMESPACE}" \
  --from-literal=GOOGLE_LANGUAGE_API_KEY="${GOOGLE_LANGUAGE_API_KEY}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> Secret '${SECRET_NAME}' applied in namespace '${NAMESPACE}'"
