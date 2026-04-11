#!/usr/bin/env bash
set -euo pipefail

SERVICE_DIR="services/notification-consumer"

echo "==> Building and starting notification-consumer service (dev profile)..."
echo "    Connects to:"
echo "      Kafka:     localhost:30092"
echo "      Cassandra: localhost:30942"
echo "    Topics created on startup:"
echo "      notification.fanout   (16 partitions)"
echo "      notification.resolve  (16 partitions)"
echo "      notification.send     (16 partitions)"
echo "      notification.retry    (8 partitions)"
echo "      notification.retry-dlq (auto, @RetryableTopic)"
echo "    Health:"
echo "      GET http://localhost:8080/actuator/health/liveness"
echo "      GET http://localhost:8080/actuator/health/readiness"
echo ""

./mvnw install -f services/pom.xml -DskipTests -q

./mvnw -f "${SERVICE_DIR}/pom.xml" spring-boot:run \
  -Dspring-boot.run.profiles=dev
