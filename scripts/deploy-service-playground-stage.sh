#!/usr/bin/env bash
set -euo pipefail

RELEASE="playground-stage"
NAMESPACE="playground-stage"
CHART_PATH="infra/helm/playground"
VALUES_BASE="infra/helm/playground/values.yaml"
VALUES_STAGE="infra/helm/playground/values-stage.yaml"

# Step 1: Pull latest main (includes updated values-stage.yaml from CI)
echo "==> Pulling latest main..."
git pull origin main

# Step 2: Show image tag that will be deployed
SHA="$(grep 'tag:' "${VALUES_STAGE}" | head -1 | awk '{print $2}' | tr -d '"')"
echo "==> Deploying image tag: ${SHA}"

# Step 3: Helm upgrade
echo "==> Running helm upgrade..."
helm upgrade --install "${RELEASE}" "${CHART_PATH}" \
  -n "${NAMESPACE}" \
  -f "${VALUES_BASE}" \
  -f "${VALUES_STAGE}"

# Step 4: Wait for rollout
echo "==> Waiting for rollout..."
kubectl -n "${NAMESPACE}" rollout status "deployment/${RELEASE}" --timeout=180s

# Step 5: Show pod status
echo ""
echo "==> Pods"
kubectl -n "${NAMESPACE}" get pods -o wide

# Step 6: Quick health check via port-forward
echo ""
echo "==> Testing health endpoint..."
kubectl -n "${NAMESPACE}" port-forward "svc/${RELEASE}" 9000:8080 &
PF_PID=$!
sleep 3

READINESS_RESPONSE="$(curl -sf "http://localhost:9000/actuator/health/readiness" || true)"
if [[ -n "${READINESS_RESPONSE}" ]]; then
  echo "    Response: ${READINESS_RESPONSE}"
  echo "    ✅ readiness OK"
else
  echo "    ❌ readiness check failed (no response)"
fi

echo ""
echo "==> Testing echo endpoint..."
ECHO_RESPONSE="$(curl -sf "http://localhost:9000/api/v1/echo?message=hello&from=deploy-script" || true)"
if [[ -n "${ECHO_RESPONSE}" ]]; then
  echo "    Response: ${ECHO_RESPONSE}"
  echo "    ✅ echo OK"
else
  echo "    ❌ echo check failed (no response)"
fi

kill "${PF_PID}" 2>/dev/null || true

echo ""
echo "==> Done. To test manually:"
echo "    kubectl -n ${NAMESPACE} port-forward svc/${RELEASE} 9000:8080"
echo "    curl \"http://localhost:9000/api/v1/echo?message=hello\""
