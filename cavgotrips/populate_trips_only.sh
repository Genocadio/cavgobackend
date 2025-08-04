#!/bin/bash

# Usage: ./populate_trips_only.sh <database_name>

set -e

if [ -z "$1" ]; then
  echo "Usage: $0 <database_name>"
  echo "This script generates dummy car data and trips using existing routes"
  exit 1
fi

DB_NAME="$1"
DB_USER="postgres"
DB_PORT="5432"

# Helper to run SQL inside Docker container
run_sql() {
  docker exec -i cavgo-postgres psql -U "$DB_USER" -d "$DB_NAME" -c "$1"
}

echo "Generating dummy car data and trips for database: $DB_NAME"

# First, get existing route IDs to use for trips
echo "Fetching existing route IDs..."
ROUTE_IDS=$(docker exec -i cavgo-postgres psql -U "$DB_USER" -d "$DB_NAME" -t -c "SELECT id FROM routes ORDER BY id;" | tr -d ' ' | grep -v '^$')

if [ -z "$ROUTE_IDS" ]; then
  echo "No routes found in database. Please ensure routes exist before running this script."
  exit 1
fi

echo "Found routes: $ROUTE_IDS"

# Clear existing trips and trip waypoints
echo "Clearing existing trips and trip waypoints..."
run_sql "DELETE FROM trip_waypoints;"
run_sql "DELETE FROM trips;"

# Generate 30 trips with dummy car data
echo "Generating 30 trips with dummy car data..."

TRIP_VALUES=()
TRIP_ROUTE_IDS=()

# Convert route IDs to array
ROUTE_IDS_ARRAY=($ROUTE_IDS)
ROUTE_COUNT=${#ROUTE_IDS_ARRAY[@]}

for i in {1..30}; do
  # Randomly select a route (cycle through available routes)
  ROUTE_INDEX=$(( (i-1) % ROUTE_COUNT ))
  ROUTE_ID=${ROUTE_IDS_ARRAY[$ROUTE_INDEX]}
  TRIP_ROUTE_IDS+=("$ROUTE_ID")
  
  VEHICLE_ID=$(( (i-1)%10+1 ))  # 10 different vehicles
  
  # Generate trip seats between 15-45
  SEATS=$(( 15 + (i % 31) ))
  
  # Vehicle capacity should be at least 5 more than seats, max 50
  VEHICLE_CAPACITY=$(( SEATS + 5 + (i % 10) ))
  if [ $VEHICLE_CAPACITY -gt 50 ]; then
    VEHICLE_CAPACITY=50
  fi
  
  # Generate departure time (starting from current time + random offset)
  BASE_TIME=$(date +%s)
  DEPARTURE=$(( BASE_TIME + i*3600 + (i % 24)*3600 ))  # Each trip 1 hour apart, some next day
  
  # Generate random company names
  COMPANIES=("Rwanda Express" "Kigali Transport" "East Africa Bus" "Central Transit" "Northern Routes" "Southern Express" "Western Connect" "Highland Travel" "Valley Transport" "City Link")
  COMPANY_INDEX=$(( (i-1) % 10 ))
  COMPANY_NAME="${COMPANIES[$COMPANY_INDEX]}"
  
  # Generate random driver names
  DRIVER_FIRST_NAMES=("Jean" "Pierre" "Marie" "Claude" "Francois" "Joseph" "Paul" "Andre" "Louis" "Michel" "Philippe" "Jacques" "Henri" "Robert" "Daniel")
  DRIVER_LAST_NAMES=("Ndayisaba" "Uwimana" "Mukamana" "Niyonsenga" "Habyarimana" "Nkurunziza" "Kagame" "Bizimana" "Nshimiyimana" "Mugisha" "Niyongabo" "Rutaganda" "Ntahobari" "Ndayambaje" "Munyaneza")
  
  DRIVER_FIRST_INDEX=$(( (i-1) % 15 ))
  DRIVER_LAST_INDEX=$(( (i-1) % 15 ))
  DRIVER_NAME="${DRIVER_FIRST_NAMES[$DRIVER_FIRST_INDEX]} ${DRIVER_LAST_NAMES[$DRIVER_LAST_INDEX]}"
  
  # Generate phone number
  PHONE="078${i}${i}${i}${i}${i}"
  if [ ${#PHONE} -gt 10 ]; then
    PHONE="078${i}${i}${i}${i}"
  fi
  
  # Generate license plate
  LICENSE_PLATE="RAB${i}${i}${i}${i}"
  if [ ${#LICENSE_PLATE} -gt 8 ]; then
    LICENSE_PLATE="RAB${i}${i}${i}"
  fi
  
  # Random status (mostly SCHEDULED, some IN_PROGRESS)
  STATUS="SCHEDULED"
  if [ $((i % 5)) -eq 0 ]; then
    STATUS="IN_PROGRESS"
  fi
  
  # Random connection mode
  CONNECTION_MODES=("ONLINE" "OFFLINE" "HYBRID")
  CONNECTION_INDEX=$(( (i-1) % 3 ))
  CONNECTION_MODE="${CONNECTION_MODES[$CONNECTION_INDEX]}"
  
  # Create vehicle JSON
  VEHICLE_JSON="'{\"id\":$VEHICLE_ID,\"company_id\":$((i % 5 + 1)),\"company_name\":\"$COMPANY_NAME\",\"capacity\":$VEHICLE_CAPACITY,\"license_plate\":\"$LICENSE_PLATE\",\"driver\":{\"name\":\"$DRIVER_NAME\",\"phone\":\"$PHONE\"}}'"
  
  TRIP_VALUES+=("($ROUTE_ID, $VEHICLE_ID, $VEHICLE_JSON, '$STATUS', $DEPARTURE, '$CONNECTION_MODE', $SEATS, false, false, NOW(), NOW())")
done

IFS=,; run_sql "INSERT INTO trips (route_id, vehicle_id, vehicle, status, departure_time, connection_mode, seats, is_reversed, has_custom_waypoints, created_at, updated_at) VALUES ${TRIP_VALUES[*]};"; unset IFS

echo "Generated 30 trips successfully!"

# Generate trip waypoints based on route waypoints
echo "Generating trip waypoints..."

# Get all route waypoints to copy to trip waypoints
ROUTE_WAYPOINTS=$(docker exec -i cavgo-postgres psql -U "$DB_USER" -d "$DB_NAME" -t -c "SELECT route_id, location_id, \"order\", price FROM route_waypoints ORDER BY route_id, \"order\";")

TRIP_WP_VALUES=()
TRIP_ID=1

for i in {1..30}; do
  ROUTE_ID=${TRIP_ROUTE_IDS[$((i-1))]}
  
  # Get waypoints for this route
  ROUTE_WPS=$(echo "$ROUTE_WAYPOINTS" | grep "^$ROUTE_ID " || true)
  
  if [ -n "$ROUTE_WPS" ]; then
    echo "$ROUTE_WPS" | while read -r wp_line; do
      if [ -n "$wp_line" ]; then
        # Parse waypoint data: route_id location_id order price
        LOCATION_ID=$(echo "$wp_line" | awk '{print $2}')
        ORDER=$(echo "$wp_line" | awk '{print $3}')
        PRICE=$(echo "$wp_line" | awk '{print $4}')
        
        # Add some variation to prices
        VARIED_PRICE=$(echo "scale=2; $PRICE + $((RANDOM % 5))" | bc)
        
        # Randomly mark some waypoints as passed or next
        IS_PASSED="false"
        IS_NEXT="false"
        
        if [ "$STATUS" = "IN_PROGRESS" ] && [ $ORDER -lt 2 ]; then
          IS_PASSED="true"
        fi
        
        if [ "$STATUS" = "IN_PROGRESS" ] && [ $ORDER -eq 2 ]; then
          IS_NEXT="true"
        fi
        
        TRIP_WP_VALUES+=("($i, $LOCATION_ID, $ORDER, $VARIED_PRICE, $IS_PASSED, $IS_NEXT, false, NOW(), NOW())")
      fi
    done
  fi
done

if [ ${#TRIP_WP_VALUES[@]} -gt 0 ]; then
  IFS=,; run_sql "INSERT INTO trip_waypoints (trip_id, location_id, \"order\", price, is_passed, is_next, is_custom, created_at, updated_at) VALUES ${TRIP_WP_VALUES[*]};"; unset IFS
  echo "Generated trip waypoints successfully!"
else
  echo "No route waypoints found to copy to trips."
fi

echo "Trip generation complete!"
echo "Generated 30 trips with dummy car data using existing routes." 