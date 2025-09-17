#!/bin/bash

# Test script for route waypoint uniqueness logic
# Make sure your server is running on localhost:8080

BASE_URL="http://localhost:8080"

echo "Testing Route Waypoint Uniqueness Logic..."
echo "=========================================="

# Test 1: Create route A-B (no waypoints)
echo -e "\n1. Creating route A-B (origin: 1, destination: 2, no waypoints):"
ROUTE1_RESPONSE=$(curl -s -X POST "$BASE_URL/routes" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Route A-B",
    "origin_id": 1,
    "destination_id": 2,
    "route_price": 25.00,
    "distance_meters": 15000,
    "estimated_duration_seconds": 1800,
    "city_route": false
  }')

echo "$ROUTE1_RESPONSE" | jq '.'
ROUTE1_ID=$(echo "$ROUTE1_RESPONSE" | jq -r '.id // empty')

# Test 2: Try to create another route A-B (should fail)
echo -e "\n2. Attempting to create duplicate route A-B (should fail):"
ROUTE2_RESPONSE=$(curl -s -X POST "$BASE_URL/routes" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Another Route A-B",
    "origin_id": 1,
    "destination_id": 2,
    "route_price": 30.00,
    "distance_meters": 16000,
    "estimated_duration_seconds": 2000,
    "city_route": false
  }')

echo "$ROUTE2_RESPONSE" | jq '.'

# Test 3: Create route A-C-B (with passthrough waypoint)
echo -e "\n3. Creating route A-C-B (origin: 1, destination: 2, passthrough waypoint: 3):"
ROUTE3_RESPONSE=$(curl -s -X POST "$BASE_URL/routes" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Route A-C-B",
    "origin_id": 1,
    "destination_id": 2,
    "route_price": 35.00,
    "distance_meters": 20000,
    "estimated_duration_seconds": 2400,
    "city_route": false,
    "waypoints": [
      {
        "location_id": 3,
        "order": 1,
        "is_pass_through": true
      }
    ]
  }')

echo "$ROUTE3_RESPONSE" | jq '.'
ROUTE3_ID=$(echo "$ROUTE3_RESPONSE" | jq -r '.id // empty')

# Test 4: Create route A-C-D-B (with different passthrough waypoints)
echo -e "\n4. Creating route A-C-D-B (origin: 1, destination: 2, passthrough waypoints: 3, 4):"
ROUTE4_RESPONSE=$(curl -s -X POST "$BASE_URL/routes" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Route A-C-D-B",
    "origin_id": 1,
    "destination_id": 2,
    "route_price": 45.00,
    "distance_meters": 25000,
    "estimated_duration_seconds": 3000,
    "city_route": false,
    "waypoints": [
      {
        "location_id": 3,
        "order": 1,
        "is_pass_through": true
      },
      {
        "location_id": 4,
        "order": 2,
        "is_pass_through": true
      }
    ]
  }')

echo "$ROUTE4_RESPONSE" | jq '.'
ROUTE4_ID=$(echo "$ROUTE4_RESPONSE" | jq -r '.id // empty')

# Test 5: Try to create another route A-C-D-B (should fail)
echo -e "\n5. Attempting to create duplicate route A-C-D-B (should fail):"
ROUTE5_RESPONSE=$(curl -s -X POST "$BASE_URL/routes" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Another Route A-C-D-B",
    "origin_id": 1,
    "destination_id": 2,
    "route_price": 50.00,
    "distance_meters": 26000,
    "estimated_duration_seconds": 3200,
    "city_route": false,
    "waypoints": [
      {
        "location_id": 3,
        "order": 1,
        "is_pass_through": true
      },
      {
        "location_id": 4,
        "order": 2,
        "is_pass_through": true
      }
    ]
  }')

echo "$ROUTE5_RESPONSE" | jq '.'

# Test 6: Create route A-D-C-B (different order, should succeed)
echo -e "\n6. Creating route A-D-C-B (origin: 1, destination: 2, passthrough waypoints: 4, 3):"
ROUTE6_RESPONSE=$(curl -s -X POST "$BASE_URL/routes" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Route A-D-C-B",
    "origin_id": 1,
    "destination_id": 2,
    "route_price": 47.00,
    "distance_meters": 24000,
    "estimated_duration_seconds": 2900,
    "city_route": false,
    "waypoints": [
      {
        "location_id": 4,
        "order": 1,
        "is_pass_through": true
      },
      {
        "location_id": 3,
        "order": 2,
        "is_pass_through": true
      }
    ]
  }')

echo "$ROUTE6_RESPONSE" | jq '.'
ROUTE6_ID=$(echo "$ROUTE6_RESPONSE" | jq -r '.id // empty')

# Summary
echo -e "\n=========================================="
echo "Test Summary:"
echo "- Route A-B (no waypoints): ${ROUTE1_ID:-FAILED}"
echo "- Duplicate A-B: ${ROUTE2_ID:-FAILED (expected)}"
echo "- Route A-C-B: ${ROUTE3_ID:-FAILED}"
echo "- Route A-C-D-B: ${ROUTE4_ID:-FAILED}"
echo "- Duplicate A-C-D-B: ${ROUTE5_ID:-FAILED (expected)}"
echo "- Route A-D-C-B (different order): ${ROUTE6_ID:-FAILED}"

# Clean up created routes
echo -e "\nCleaning up created routes..."
for id in "$ROUTE1_ID" "$ROUTE3_ID" "$ROUTE4_ID" "$ROUTE6_ID"; do
  if [ -n "$id" ] && [ "$id" != "null" ]; then
    echo "Deleting route $id..."
    curl -s -X DELETE "$BASE_URL/routes/$id" > /dev/null
  fi
done

echo "Test completed!"