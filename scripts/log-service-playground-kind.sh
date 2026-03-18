#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-playground-dev}"
CONTAINER="${CONTAINER:-playground}"
LABEL_SELECTOR="${LABEL_SELECTOR:-app.kubernetes.io/instance=service-playground}"

LEVEL="${1:-INFO}"

if [[ -z "${LEVEL}" ]]; then
  echo "Usage: $0 [INFO|DEBUG|WARN|ERROR]" >&2
  exit 1
fi

echo "==> Streaming logs for '${LABEL_SELECTOR}' in namespace '${NAMESPACE}'"
echo "==> Filtering level: ${LEVEL}"

# NOTE: --prefix=true prints pod name prefix per line (kubectl >= 1.20-ish depending on build).
# Keep it piped to rg with line buffering so -f behaves interactively.
kubectl -n "${NAMESPACE}" logs -l "${LABEL_SELECTOR}" -c "${CONTAINER}" -f --prefix=true \
  | rg -e "\\b${LEVEL}\\b" --line-buffered

