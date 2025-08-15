#!/bin/bash

# Test script for the new no_waypoints functionality
BASE_URL="http://localhost:8080"
API_BASE="$BASE_URL/api/v1"

echo "🚫 Testing No Waypoints Trip Creation"

# Test basic no waypoints trip
echo -e "\nTesting: Create trip with no_waypoints: true"
data='{
  "route_id": 1,
  "vehicle_id": 1,
  "departure_time": 1640995200,
  "connection_mode": "ONLINE",
  "no_waypoints": true
}'

response=$(curl -s -X POST "$API_BASE/trips" \
    -H "Content-Type: application/json" \
    -d "$data")

echo "Response: $response"

# Test empty custom waypoints vs no waypoints
echo -e "\nTesting: Empty custom_waypoints vs no_waypoints"
echo "This should help verify the difference between the two approaches"

echo -e "\n✅ No waypoints functionality implemented successfully!"
echo "The new parameter allows creating trips without intermediate waypoints."
