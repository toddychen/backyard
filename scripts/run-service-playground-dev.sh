#!/usr/bin/env bash
set -euo pipefail

SERVICE_DIR="services/playground"

echo "==> Starting playground service locally (dev profile)..."
echo "    URL: http://localhost:2000"
echo "    Endpoints:"
echo "      GET http://localhost:2000/api/v1/echo?message=hello"
echo "      GET http://localhost:2000/api/v1/test/tracing"
echo "      GET http://localhost:2000/api/v1/dory/reminders?owner=toddy"
echo "      GET http://localhost:2000/actuator/health/liveness"
echo "      GET http://localhost:2000/actuator/health/readiness"
echo ""

./mvnw -f "${SERVICE_DIR}/pom.xml" process-resources spring-boot:run \
  -Dspring-boot.run.profiles=dev
  # -Dspring-boot.run.jvmArguments="-Djdk.httpclient.HttpClient.log=requests"  # JDK transport-level request logging
