#!/bin/bash

# Usage: ./populate_dummy_data.sh <database_name> [--recreate-db]

set -e

if [ -z "$1" ]; then
  echo "Usage: $0 <database_name> [--recreate-db]"
  echo "  --recreate-db: Drop and recreate the database (recommended for clean state)"
  exit 1
fi

DB_NAME="$1"
DB_USER="postgres"
DB_PORT="5432"
RECREATE_DB=false

# Check for --recreate-db flag
if [ "$2" = "--recreate-db" ]; then
  RECREATE_DB=true
fi

# Helper to run SQL inside Docker container
run_sql() {
  docker exec -i cavgo-postgres psql -U "$DB_USER" -d "$DB_NAME" -c "$1"
}

# Helper to run SQL against postgres database (for database operations)
run_sql_postgres() {
  docker exec -i cavgo-postgres psql -U "$DB_USER" -d "postgres" -c "$1"
}

echo "Populating database: $DB_NAME"

if [ "$RECREATE_DB" = true ]; then
  echo "Dropping and recreating database..."
  run_sql_postgres "DROP DATABASE IF EXISTS $DB_NAME;"
  run_sql_postgres "CREATE DATABASE $DB_NAME;"
  echo "Database recreated successfully."
  echo "NOTE: You need to run database migrations after recreating the database."
else
  echo "Clearing existing data and resetting sequences..."
  # Drop all tables and recreate them (this will reset all sequences)
  run_sql "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
  echo "Schema reset successfully."
  echo "NOTE: You need to run database migrations after resetting the schema."
fi

# Insert Locations
echo "Inserting 100 Rwandan locations..."
run_sql "INSERT INTO locations (latitude, longitude, custom_name, created_at, updated_at) VALUES
  (-1.9441, 30.0619, 'Kigali', NOW(), NOW()),
  (-2.4856, 29.5668, 'Butare', NOW(), NOW()),
  (-1.6836, 29.2356, 'Gisenyi', NOW(), NOW()),
  (-2.0744, 29.7569, 'Cyangugu', NOW(), NOW()),
  (-1.5058, 30.0037, 'Byumba', NOW(), NOW()),
  (-1.4833, 29.6333, 'Ruhengeri', NOW(), NOW()),
  (-2.4700, 29.5600, 'Huye', NOW(), NOW()),
  (-1.9500, 30.0588, 'Nyarugenge', NOW(), NOW()),
  (-1.9501, 30.0821, 'Kacyiru', NOW(), NOW()),
  (-1.9579, 30.1127, 'Remera', NOW(), NOW()),
  (-1.9696, 30.1044, 'Kimironko', NOW(), NOW()),
  (-1.9444, 30.0891, 'Nyamirambo', NOW(), NOW()),
  (-1.9536, 30.0911, 'Kibagabaga', NOW(), NOW()),
  (-1.9403, 30.0594, 'Gikondo', NOW(), NOW()),
  (-1.9398, 30.0444, 'Kicukiro', NOW(), NOW()),
  (-1.9447, 30.0612, 'Gasabo', NOW(), NOW()),
  (-1.9442, 30.0620, 'Nyarugenge Market', NOW(), NOW()),
  (-1.9500, 30.0600, 'Kigali Heights', NOW(), NOW()),
  (-1.9505, 30.0605, 'Kigali Convention Centre', NOW(), NOW()),
  (-1.9490, 30.0580, 'Amahoro Stadium', NOW(), NOW()),
  (-1.9502, 30.0610, 'Kimironko Market', NOW(), NOW()),
  (-1.9510, 30.0620, 'Kacyiru Police', NOW(), NOW()),
  (-1.9520, 30.0630, 'Kigali Genocide Memorial', NOW(), NOW()),
  (-1.9530, 30.0640, 'Nyabugogo', NOW(), NOW()),
  (-1.9540, 30.0650, 'Gisozi', NOW(), NOW()),
  (-1.9550, 30.0660, 'Kanombe', NOW(), NOW()),
  (-1.9560, 30.0670, 'Kimironko Bus Park', NOW(), NOW()),
  (-1.9570, 30.0680, 'Kibagabaga Hospital', NOW(), NOW()),
  (-1.9580, 30.0690, 'Kacyiru Hospital', NOW(), NOW()),
  (-1.9590, 30.0700, 'Kigali City Tower', NOW(), NOW()),
  (-1.9600, 30.0710, 'Kigali Arena', NOW(), NOW()),
  (-1.9610, 30.0720, 'Kigali Golf Club', NOW(), NOW()),
  (-1.9620, 30.0730, 'Kigali Public Library', NOW(), NOW()),
  (-1.9630, 30.0740, 'Kigali International Airport', NOW(), NOW()),
  (-1.9640, 30.0750, 'Kigali Business Centre', NOW(), NOW()),
  (-1.9650, 30.0760, 'Kigali Marriott Hotel', NOW(), NOW()),
  (-1.9660, 30.0770, 'Kigali Serena Hotel', NOW(), NOW()),
  (-1.9670, 30.0780, 'Kigali Car Free Zone', NOW(), NOW()),
  (-1.9680, 30.0790, 'Kigali City Market', NOW(), NOW()),
  (-1.9690, 30.0800, 'Kigali Central Hospital', NOW(), NOW()),
  (-1.9700, 30.0810, 'Kigali Memorial Centre', NOW(), NOW()),
  (-1.9710, 30.0820, 'Kigali City Hall', NOW(), NOW()),
  (-1.9720, 30.0830, 'Kigali Supreme Court', NOW(), NOW()),
  (-1.9730, 30.0840, 'Kigali Parliament', NOW(), NOW()),
  (-1.9740, 30.0850, 'Kigali National Museum', NOW(), NOW()),
  (-1.9750, 30.0860, 'Kigali Institute of Science', NOW(), NOW()),
  (-1.9760, 30.0870, 'Kigali Institute of Education', NOW(), NOW()),
  (-1.9770, 30.0880, 'Kigali Institute of Health', NOW(), NOW()),
  (-1.9780, 30.0890, 'Kigali Institute of Agriculture', NOW(), NOW()),
  (-1.9790, 30.0900, 'Kigali Institute of Management', NOW(), NOW()),
  (-2.0000, 30.1000, 'Muhanga', NOW(), NOW()),
  (-2.1000, 29.7000, 'Nyamagabe', NOW(), NOW()),
  (-2.2000, 29.8000, 'Karongi', NOW(), NOW()),
  (-2.3000, 29.9000, 'Rusizi', NOW(), NOW()),
  (-2.4000, 30.0000, 'Rubavu', NOW(), NOW()),
  (-2.5000, 30.1000, 'Ngororero', NOW(), NOW()),
  (-2.6000, 30.2000, 'Rutsiro', NOW(), NOW()),
  (-2.7000, 30.3000, 'Nyabihu', NOW(), NOW()),
  (-2.8000, 30.4000, 'Gakenke', NOW(), NOW()),
  (-2.9000, 30.5000, 'Burera', NOW(), NOW()),
  (-2.9500, 30.5500, 'Musanze', NOW(), NOW()),
  (-2.9600, 30.5600, 'Gicumbi', NOW(), NOW()),
  (-2.9700, 30.5700, 'Rulindo', NOW(), NOW()),
  (-2.9800, 30.5800, 'Kayonza', NOW(), NOW()),
  (-2.9900, 30.5900, 'Rwamagana', NOW(), NOW()),
  (-3.0000, 30.6000, 'Bugesera', NOW(), NOW()),
  (-3.0100, 30.6100, 'Ngoma', NOW(), NOW()),
  (-3.0200, 30.6200, 'Kirehe', NOW(), NOW()),
  (-3.0300, 30.6300, 'Gatsibo', NOW(), NOW()),
  (-3.0400, 30.6400, 'Nyagatare', NOW(), NOW()),
  (-3.0500, 30.6500, 'Nyanza', NOW(), NOW()),
  (-3.0600, 30.6600, 'Gisagara', NOW(), NOW()),
  (-3.0700, 30.6700, 'Kamonyi', NOW(), NOW()),
  (-3.0800, 30.6800, 'Ruhango', NOW(), NOW()),
  (-3.0900, 30.6900, 'Nyamasheke', NOW(), NOW()),
  (-3.1000, 30.7000, 'Nyaruguru', NOW(), NOW()),
  (-3.1100, 30.7100, 'Kicukiro', NOW(), NOW()),
  (-3.1200, 30.7200, 'Gasabo', NOW(), NOW()),
  (-3.1300, 30.7300, 'Nyarugenge', NOW(), NOW()),
  (-3.1400, 30.7400, 'Rusororo', NOW(), NOW()),
  (-3.1500, 30.7500, 'Kimironko', NOW(), NOW()),
  (-3.1600, 30.7600, 'Remera', NOW(), NOW()),
  (-3.1700, 30.7700, 'Kanombe', NOW(), NOW()),
  (-3.1800, 30.7800, 'Gikondo', NOW(), NOW()),
  (-3.1900, 30.7900, 'Kagugu', NOW(), NOW()),
  (-3.2000, 30.8000, 'Kibagabaga', NOW(), NOW()),
  (-3.2100, 30.8100, 'Kacyiru', NOW(), NOW()),
  (-3.2200, 30.8200, 'Nyamirambo', NOW(), NOW()),
  (-3.2300, 30.8300, 'Gisozi', NOW(), NOW()),
  (-3.2400, 30.8400, 'Kagugu', NOW(), NOW()),
  (-3.2500, 30.8500, 'Kibagabaga', NOW(), NOW()),
  (-3.2600, 30.8600, 'Kacyiru', NOW(), NOW()),
  (-3.2700, 30.8700, 'Nyamirambo', NOW(), NOW()),
  (-3.2800, 30.8800, 'Gisozi', NOW(), NOW()),
  (-3.2900, 30.8900, 'Kagugu', NOW(), NOW()),
  (-3.3000, 30.9000, 'Kibagabaga', NOW(), NOW()),
  (-3.3100, 30.9100, 'Kacyiru', NOW(), NOW()),
  (-3.3200, 30.9200, 'Nyamirambo', NOW(), NOW()),
  (-3.3300, 30.9300, 'Gisozi', NOW(), NOW()),
  (-3.3400, 30.9400, 'Kagugu', NOW(), NOW());"

# Insert 14 routes
ROUTE_VALUES=()
ROUTE_ORIGINS=()
ROUTE_DESTS=()
for i in {1..14}; do
  ORIGIN_ID=$(( (i-1)*7+1 ))
  DEST_ID=$(( (i-1)*7+2 ))
  ROUTE_ORIGINS+=("$ORIGIN_ID")
  ROUTE_DESTS+=("$DEST_ID")
  NAME="'Route $i'"
  DISTANCE=$(( 5000 + i*1000 ))
  DURATION=$(( 3600 + i*100 ))
  PRICE=$(echo "scale=2; 10 + $i*2" | bc)
  # Alternate between city and non-city routes
  if [ $((i % 2)) -eq 0 ]; then
    CITY_ROUTE="true"
  else
    CITY_ROUTE="false"
  fi
  ROUTE_VALUES+=("($NAME, $DISTANCE, $DURATION, $ORIGIN_ID, $DEST_ID, $PRICE, $CITY_ROUTE, NOW(), NOW())")
done
IFS=,; run_sql "INSERT INTO routes (name, distance_meters, estimated_duration_seconds, origin_id, destination_id, route_price, city_route, created_at, updated_at) VALUES ${ROUTE_VALUES[*]};"; unset IFS

echo "Inserting route waypoints..."
ROUTE_WP_VALUES=()
for i in {1..14}; do
  ORIGIN_ID=${ROUTE_ORIGINS[$((i-1))]}
  DEST_ID=${ROUTE_DESTS[$((i-1))]}
  COUNT=0
  LOC_IDX=$(( (i-1)*7+3 ))
  USED_LOCS=()
  while [ $COUNT -lt 3 ] && [ $LOC_IDX -le 100 ]; do
    # Skip if location is origin, destination, or already used in this route
    if [ $LOC_IDX -ne $ORIGIN_ID ] && [ $LOC_IDX -ne $DEST_ID ]; then
      # Check if location is already used in this route
      DUPLICATE=false
      for used_loc in "${USED_LOCS[@]}"; do
        if [ $LOC_IDX -eq $used_loc ]; then
          DUPLICATE=true
          break
        fi
      done
      if [ "$DUPLICATE" = false ]; then
        ORDER=$COUNT
        PRICE=$(echo "scale=2; 5 + $COUNT*1.5 + $i" | bc)
        ROUTE_WP_VALUES+=("($i, $LOC_IDX, $ORDER, $PRICE, NOW())")
        USED_LOCS+=("$LOC_IDX")
        COUNT=$((COUNT+1))
      fi
    fi
    LOC_IDX=$((LOC_IDX+1))
  done
done
IFS=,; run_sql "INSERT INTO route_waypoints (route_id, location_id, \"order\", price, created_at) VALUES ${ROUTE_WP_VALUES[*]};"; unset IFS

# Insert 30 trips
TRIP_VALUES=()
TRIP_ROUTE_IDS=()
for i in {1..30}; do
  ROUTE_ID=$(( (i-1)%14+1 ))
  TRIP_ROUTE_IDS+=("$ROUTE_ID")
  VEHICLE_ID=$(( (i-1)%5+1 ))
  # Generate trip seats between 11-40 (to ensure vehicle capacity can be 10+ more and not exceed 50)
  SEATS=$(( 11 + (i % 30) ))
  # Vehicle capacity should be at least 10 more than seats, max 50
  VEHICLE_CAPACITY=$(( SEATS + 10 + (i % 5) ))
  if [ $VEHICLE_CAPACITY -gt 50 ]; then
    VEHICLE_CAPACITY=50
  fi
  DEPARTURE=$(( 1718000000 + i*10000 ))
  VEHICLE_JSON="'{\"id\":$VEHICLE_ID,\"company_id\":1,\"company_name\":\"Demo Company\",\"capacity\":$VEHICLE_CAPACITY,\"license_plate\":\"ABC$((100+i))\",\"driver\":{\"name\":\"Driver $i\",\"phone\":\"07800000$((10+i))\"}}'"
  TRIP_VALUES+=("($ROUTE_ID, $VEHICLE_ID, $VEHICLE_JSON, 'SCHEDULED', $DEPARTURE, 'ONLINE', $SEATS, false, false, NOW(), NOW())")
done
IFS=,; run_sql "INSERT INTO trips (route_id, vehicle_id, vehicle, status, departure_time, connection_mode, seats, is_reversed, has_custom_waypoints, created_at, updated_at) VALUES ${TRIP_VALUES[*]};"; unset IFS

echo "Inserting trip waypoints..."
TRIP_WP_VALUES=()
for i in {1..30}; do
  ROUTE_ID=${TRIP_ROUTE_IDS[$((i-1))]}
  ORIGIN_ID=${ROUTE_ORIGINS[$((ROUTE_ID-1))]}
  DEST_ID=${ROUTE_DESTS[$((ROUTE_ID-1))]}
  COUNT=0
  LOC_IDX=$(( (i-1)*3+1 ))
  USED_LOCS=()
  while [ $COUNT -lt 3 ] && [ $LOC_IDX -le 100 ]; do
    # Skip if location is origin or destination for this trip's route
    if [ $LOC_IDX -ne $ORIGIN_ID ] && [ $LOC_IDX -ne $DEST_ID ]; then
      # Check if location is already used in this trip
      DUPLICATE=false
      for used_loc in "${USED_LOCS[@]}"; do
        if [ $LOC_IDX -eq $used_loc ]; then
          DUPLICATE=true
          break
        fi
      done
      if [ "$DUPLICATE" = false ]; then
        ORDER=$COUNT
        PRICE=$(echo "scale=2; 5 + $COUNT*2 + $i" | bc)
        TRIP_WP_VALUES+=("($i, $LOC_IDX, $ORDER, $PRICE, false, false, false, NOW(), NOW())")
        USED_LOCS+=("$LOC_IDX")
        COUNT=$((COUNT+1))
      fi
    fi
    LOC_IDX=$((LOC_IDX+1))
  done
done
IFS=,; run_sql "INSERT INTO trip_waypoints (trip_id, location_id, \"order\", price, is_passed, is_next, is_custom, created_at, updated_at) VALUES ${TRIP_WP_VALUES[*]};"; unset IFS

echo "Dummy data population complete!" 