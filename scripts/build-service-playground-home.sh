#!/usr/bin/env bash
set -euo pipefail

SRC_JAR="services/playground/target/playground-0.0.1-SNAPSHOT.jar"
DEST_DIR="${HOME}/.backyard/bin"
DEST_JAR="${DEST_DIR}/playground.jar"

echo "==> Building playground service..."
./mvnw -f services/playground/pom.xml package -DskipTests

mkdir -p "${DEST_DIR}"
cp "${SRC_JAR}" "${DEST_JAR}"

echo "==> Jar deployed to ${DEST_JAR}"
echo "    Run with: ./scripts/run-service-playground-home.sh"
