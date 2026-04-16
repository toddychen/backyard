#!/usr/bin/env bash
set -euo pipefail

RELEASE="${RELEASE:-cassandra}"
NAMESPACE="${NAMESPACE:-cassandra}"
CHART_PATH="${CHART_PATH:-infra/helm/cassandra}"
VALUES_FILE="${VALUES_FILE:-infra/helm/cassandra/values-kind.yaml}"
INFRA_DIR="${INFRA_DIR:-infra/cassandra}"
POD="${RELEASE}-0"

echo "==> Deploying Cassandra to namespace ${NAMESPACE}"

kubectl create ns "${NAMESPACE}" 2>/dev/null || true

# ── Create ConfigMap: schema.cql + migrate.sh ────────────────────────────────
echo "==> Syncing ConfigMap cassandra-schema"
kubectl create configmap cassandra-schema \
    --from-file=schema.cql="${INFRA_DIR}/schema.cql" \
    --from-file=migrate.sh="${INFRA_DIR}/migrate.sh" \
    -n "${NAMESPACE}" \
    --dry-run=client -o yaml | kubectl apply -f -

# ── Create ConfigMap: migration .cql files ───────────────────────────────────
echo "==> Syncing ConfigMap cassandra-migrations"
CM_ARGS=("--from-literal=.gitkeep=")   # placeholder so the CM is never empty
if ls "${INFRA_DIR}/migrations/"*.cql 2>/dev/null | grep -q .; then
    for f in "${INFRA_DIR}/migrations/"*.cql; do
        CM_ARGS+=("--from-file=$(basename "$f")=${f}")
    done
fi
kubectl create configmap cassandra-migrations \
    "${CM_ARGS[@]}" \
    -n "${NAMESPACE}" \
    --dry-run=client -o yaml | kubectl apply -f -

# ── Deploy / upgrade the Helm release ────────────────────────────────────────
helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}" \
  -f "${VALUES_FILE}"

echo "==> Waiting for rollout"
kubectl -n "${NAMESPACE}" rollout status "statefulset/${RELEASE}" --timeout=180s

# ── Run migrations inside the pod ────────────────────────────────────────────
# Files are already mounted via ConfigMap:
#   /cassandra-schema/schema.cql
#   /cassandra-schema/migrate.sh
#   /cassandra-migrations/<V***.cql ...>
echo "==> Running migrations"
kubectl -n "${NAMESPACE}" exec "${POD}" -- \
    bash /cassandra-schema/migrate.sh localhost 9042 /cassandra-migrations

echo ""
echo "==> Cassandra is ready"
echo "    In-cluster: cassandra.${NAMESPACE}:9042"
echo "    From host:  cqlsh 127.0.0.1 30942"
