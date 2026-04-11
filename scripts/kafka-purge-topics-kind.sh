#!/usr/bin/env bash
set -euo pipefail

TOPICS=(
  notification.fanout
  notification.resolve
  notification.send
  notification.retry
  notification.retry-dlq
)

PARTITIONS=(16 16 16 8 1)

echo "==> Deleting notification topics"

for topic in "${TOPICS[@]}"; do
  kubectl exec -n kafka kafka-0 -- /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --delete --topic "$topic" 2>/dev/null || true
  echo "    Deleted: $topic"
done

echo "==> Waiting for topics to be fully removed..."
for topic in "${TOPICS[@]}"; do
  for i in $(seq 1 30); do
    exists=$(kubectl exec -n kafka kafka-0 -- /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server localhost:9092 --list 2>/dev/null | grep -x "$topic" || true)
    if [ -z "$exists" ]; then
      echo "    Gone: $topic"
      break
    fi
    sleep 2
  done
done

echo "==> Recreating topics with 1-hour retention"

for i in "${!TOPICS[@]}"; do
  topic="${TOPICS[$i]}"
  partitions="${PARTITIONS[$i]}"
  kubectl exec -n kafka kafka-0 -- /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --create --topic "$topic" \
    --partitions "$partitions" --replication-factor 1 \
    --config retention.ms=3600000
  echo "    Created: $topic (partitions=$partitions)"
done

echo "==> Done. Restart consumer pods to re-register:"
echo "    kubectl rollout restart deployment/notification-consumer -n notification"
