#!/usr/bin/env bash
set -euo pipefail

JAR_PATH="${HOME}/.backyard/bin/playground.jar"

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "==> JAR not found at ${JAR_PATH}"
  echo "    Build first with: ./scripts/build-service-playground-home.sh"
  exit 1
fi

echo "==> Starting playground service (home profile)..."
echo "    URL: http://localhost:3100"
echo "    Endpoints:"
echo "      GET http://localhost:3100/api/v1/dory/reminders?owner=toddy"
echo "      GET http://localhost:3100/actuator/health"
echo ""

java -jar "${JAR_PATH}" --spring.profiles.active=home
