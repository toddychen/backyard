#!/usr/bin/env bash
set -euo pipefail

JAR_PATH="${HOME}/.backyard/bin/playground.jar"

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "==> JAR not found at ${JAR_PATH}"
  echo "    Build first with: ./scripts/build-service-playground-home.sh"
  exit 1
fi

echo "==> Registering playground service with pm2..."
pm2 start "java -jar ${JAR_PATH} --spring.profiles.active=home" \
  --name playground

echo ""
echo "==> Done. Useful commands:"
echo "    pm2 logs playground       - tail logs"
echo "    pm2 status                - check status"
echo "    pm2 restart playground    - restart"
echo "    pm2 stop playground       - stop"
echo ""
echo "==> To survive reboots, run:"
echo "    pm2 startup && pm2 save"
