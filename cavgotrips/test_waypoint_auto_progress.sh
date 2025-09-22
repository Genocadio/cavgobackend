#!/bin/bash

# Test script for automatic waypoint progress functionality
# This script tests the automatic waypoint progress tracking

BASE_URL="http://localhost:8080"

echo "🧪 Testing Automatic Waypoint Progress Functionality"
echo "=================================================="

# Test 1: Create a trip with waypoints
echo "📝 Test 1: Creating a trip with waypoints..."
TRIP_RESPONSE=$(curl -s -X POST "$BASE_URL/trips" \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 1,
    "vehicle_id": 1,
    "departure_time": 1703123400,
    "connection_mode": "ONLINE",
    "price": 25.50,
    "notes": "Test trip for waypoint progress",
    "is_reversed": false,
    "no_waypoints": false
  }')

echo "Trip created: $TRIP_RESPONSE"
TRIP_ID=$(echo $TRIP_RESPONSE | jq -r '.id')

if [ "$TRIP_ID" = "null" ] || [ -z "$TRIP_ID" ]; then
  echo "❌ Failed to create trip"
  exit 1
fi

echo "✅ Trip created with ID: $TRIP_ID"

# Test 2: Start the trip (should automatically set first waypoint as is_next)
echo "🚀 Test 2: Starting trip (should set first waypoint as is_next)..."
START_RESPONSE=$(curl -s -X POST "$BASE_URL/trips/$TRIP_ID/start")
echo "Start response: $START_RESPONSE"

# Test 3: Check waypoint status after start
echo "🔍 Test 3: Checking waypoint status after start..."
TRIP_DETAILS=$(curl -s "$BASE_URL/trips/$TRIP_ID")
echo "Trip details: $TRIP_DETAILS"

# Check if first waypoint is marked as is_next
FIRST_WAYPOINT_IS_NEXT=$(echo $TRIP_DETAILS | jq -r '.waypoints[0].is_next // false')
echo "First waypoint is_next: $FIRST_WAYPOINT_IS_NEXT"

if [ "$FIRST_WAYPOINT_IS_NEXT" = "true" ]; then
  echo "✅ First waypoint correctly marked as is_next"
else
  echo "❌ First waypoint not marked as is_next"
fi

# Test 4: Mark first waypoint as passed
echo "📍 Test 4: Marking first waypoint as passed..."
FIRST_WAYPOINT_ID=$(echo $TRIP_DETAILS | jq -r '.waypoints[0].id')

UPDATE_RESPONSE=$(curl -s -X PUT "$BASE_URL/trips/$TRIP_ID/progress" \
  -H "Content-Type: application/json" \
  -d "{
    \"passed_waypoint_id\": $FIRST_WAYPOINT_ID
  }")

echo "Update response: $UPDATE_RESPONSE"

# Test 5: Check waypoint status after marking first as passed
echo "🔍 Test 5: Checking waypoint status after marking first as passed..."
TRIP_DETAILS_AFTER=$(curl -s "$BASE_URL/trips/$TRIP_ID")

# Check if first waypoint is marked as passed
FIRST_WAYPOINT_IS_PASSED=$(echo $TRIP_DETAILS_AFTER | jq -r '.waypoints[0].is_passed // false')
echo "First waypoint is_passed: $FIRST_WAYPOINT_IS_PASSED"

# Check if second waypoint is now marked as is_next
SECOND_WAYPOINT_IS_NEXT=$(echo $TRIP_DETAILS_AFTER | jq -r '.waypoints[1].is_next // false')
echo "Second waypoint is_next: $SECOND_WAYPOINT_IS_NEXT"

if [ "$FIRST_WAYPOINT_IS_PASSED" = "true" ] && [ "$SECOND_WAYPOINT_IS_NEXT" = "true" ]; then
  echo "✅ Waypoint progress correctly updated - first passed, second is next"
else
  echo "❌ Waypoint progress not updated correctly"
fi

# Test 6: Update trip progress (should trigger automatic waypoint progress update)
echo "🔄 Test 6: Updating trip progress (should trigger automatic waypoint progress)..."
PROGRESS_UPDATE=$(curl -s -X PUT "$BASE_URL/trips/$TRIP_ID/progress" \
  -H "Content-Type: application/json" \
  -d '{
    "current_speed": 65.5,
    "current_latitude": 40.7128,
    "current_longitude": -74.0060,
    "remaining_time_to_destination": 1800,
    "remaining_distance_to_destination": 15.5
  }')

echo "Progress update response: $PROGRESS_UPDATE"

# Test 7: Final check of waypoint status
echo "🔍 Test 7: Final waypoint status check..."
FINAL_TRIP_DETAILS=$(curl -s "$BASE_URL/trips/$TRIP_ID")

echo "Final trip details: $FINAL_TRIP_DETAILS"

# Summary
echo ""
echo "📊 Test Summary:"
echo "================="
echo "Trip ID: $TRIP_ID"
echo "First waypoint passed: $FIRST_WAYPOINT_IS_PASSED"
echo "Second waypoint is next: $SECOND_WAYPOINT_IS_NEXT"

if [ "$FIRST_WAYPOINT_IS_PASSED" = "true" ] && [ "$SECOND_WAYPOINT_IS_NEXT" = "true" ]; then
  echo "🎉 All tests passed! Automatic waypoint progress is working correctly."
else
  echo "❌ Some tests failed. Check the implementation."
fi

echo ""
echo "🧹 Cleaning up: Deleting test trip..."
DELETE_RESPONSE=$(curl -s -X DELETE "$BASE_URL/trips/$TRIP_ID")
echo "Delete response: $DELETE_RESPONSE"
