#!/usr/bin/env bash
set -euo pipefail

SERVICE_DIR="services/playground"

echo "==> Formatting playground service Java sources..."

./mvnw -f "${SERVICE_DIR}/pom.xml" spotless:apply

echo "==> Done."
