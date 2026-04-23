#!/usr/bin/env bash
set -euo pipefail

DATA_DIR="$HOME/osrm-data"
IMAGE="osrm/osrm-backend"
CONTAINER="osrm-bayarea"

if docker ps -q -f name="$CONTAINER" | grep -q .; then
    echo "OSRM container '$CONTAINER' is already running."
    exit 0
fi

if docker ps -aq -f name="$CONTAINER" | grep -q .; then
    echo "Removing stopped container '$CONTAINER'..."
    docker rm "$CONTAINER"
fi

echo "Starting OSRM routing server..."
docker run -d \
    --name "$CONTAINER" \
    -p 5001:5000 \
    -v "$DATA_DIR:/data" \
    "$IMAGE" \
    osrm-routed --algorithm mld /data/bayarea.osrm

echo "Waiting for server to be ready..."
until curl -sf "http://localhost:5001/route/v1/driving/-121.89,37.335;-122.08,37.42" > /dev/null 2>&1; do
    sleep 1
done

echo "OSRM routing server is up at http://localhost:5001"
