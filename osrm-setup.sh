#!/bin/bash
set -e

echo "📥 Installing wget..."
apt-get update
apt-get install -y wget

cd /data

echo "🧹 Removing previous OSRM generated data..."
rm -f rwanda-latest.osrm*

echo "📥 Downloading latest Rwanda OSM data..."
wget -O rwanda-latest.osm.pbf \
  https://download.geofabrik.de/africa/rwanda-latest.osm.pbf

echo "🗺️ Extracting Rwanda OSM data..."
osrm-extract -p /opt/foot.lua rwanda-latest.osm.pbf

echo "🧩 Partitioning OSRM data..."
osrm-partition rwanda-latest.osrm

echo "⚙️ Customizing OSRM data..."
osrm-customize rwanda-latest.osrm

echo "✅ OSRM data preparation complete!"
