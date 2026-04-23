#!/usr/bin/env bash
set -euo pipefail

DATA_DIR="$HOME/osrm-data"
NORCAL_PBF="$DATA_DIR/norcal-latest.osm.pbf"
BAYAREA_PBF="$DATA_DIR/bayarea.osm.pbf"
PROFILE="/opt/car.lua"
IMAGE="osrm/osrm-backend"

# Bay Area bounding box: west, south, east, north
BBOX="-122.6,37.2,-121.5,37.9"

mkdir -p "$DATA_DIR"

# Step 1: download NorCal extract
if [[ -f "$NORCAL_PBF" ]]; then
    echo "NorCal OSM file already exists, skipping download."
else
    echo "Downloading NorCal OSM extract (~200MB)..."
    curl -L -o "$NORCAL_PBF" \
        "https://download.geofabrik.de/north-america/us/california/norcal-latest.osm.pbf"
    echo "Download complete."
fi

# Step 2: clip to Bay Area
if [[ -f "$BAYAREA_PBF" ]]; then
    echo "Bay Area clip already exists, skipping osmium step."
else
    echo "Clipping to Bay Area bounding box..."
    osmium extract --bbox="$BBOX" "$NORCAL_PBF" --output "$BAYAREA_PBF"
    echo "Clip complete."
fi

# Step 3: extract
echo "Running osrm-extract..."
docker run --rm -t     -v "$DATA_DIR:/data" \
    "$IMAGE" \
    osrm-extract -p "$PROFILE" /data/bayarea.osm.pbf

# Step 4: partition
echo "Running osrm-partition..."
docker run --rm -t     -v "$DATA_DIR:/data" \
    "$IMAGE" \
    osrm-partition /data/bayarea.osrm

# Step 5: customize
echo "Running osrm-customize..."
docker run --rm -t     -v "$DATA_DIR:/data" \
    "$IMAGE" \
    osrm-customize /data/bayarea.osrm

echo ""
echo "Preprocessing complete. Files are in: $DATA_DIR"
echo "To start the routing server:"
echo "  docker run -p 5000:5000 -v $DATA_DIR:/data $IMAGE osrm-routed --algorithm mld /data/bayarea.osrm"
