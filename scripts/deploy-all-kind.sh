#!/usr/bin/env bash
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "========================================"
echo " Deploying all services to kind cluster"
echo " (playground excluded — deploy manually)"
echo "========================================"

bash "${SCRIPTS_DIR}/deploy-redis-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-mysql-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-cassandra-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-kafka-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-kafka-ui-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-jaeger-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-prometheus-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-loki-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-grafana-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/deploy-promtail-kind.sh"
echo ""

bash "${SCRIPTS_DIR}/set-secrets-kind-cluster.sh"
echo ""

echo "========================================"
echo " All services deployed"
echo "  Redis:      redis-cli -h 127.0.0.1 -p 30379"
echo "  MySQL:      127.0.0.1:30306"
echo "  Cassandra:  cqlsh 127.0.0.1 30942"
echo "  Kafka:      localhost:30092"
echo "  Kafka UI:   http://localhost:30880"
echo "  Jaeger UI:  http://127.0.0.1:30686"
echo "  Prometheus: http://127.0.0.1:30090"
echo "  Grafana:    http://127.0.0.1:30030  (admin / admin)"
echo "========================================"
