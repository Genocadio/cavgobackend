#!/bin/bash

# Test script for route CRUD operations
# Make sure your server is running on localhost:8080

BASE_URL="http://localhost:8080"

echo "Testing Route CRUD Operations..."
echo "================================"

# Test 1: Create a route
echo -e "\n1. Creating a new route:"
CREATE_RESPONSE=$(curl -s -X POST "$BASE_URL/routes" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Route",
    "origin_id": 1,
    "destination_id": 2,
    "route_price": 45.50,
    "distance_meters": 25000,
    "estimated_duration_minutes": 45,
    "city_route": false,
    "waypoints": [
      {
        "location_id": 3,
        "order": 1
      },
      {
        "location_id": 4,
        "order": 2
      }
    ]
  }')

echo "$CREATE_RESPONSE" | jq '.'

# Extract route ID from response
ROUTE_ID=$(echo "$CREATE_RESPONSE" | jq -r '.id')
echo "Created route ID: $ROUTE_ID"

# Test 2: Get the created route
echo -e "\n2. Getting the created route:"
curl -s "$BASE_URL/routes/$ROUTE_ID" | jq '.'

# Test 3: Update the route
echo -e "\n3. Updating the route:"
UPDATE_RESPONSE=$(curl -s -X PUT "$BASE_URL/routes/$ROUTE_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated Test Route",
    "origin_id": 1,
    "destination_id": 2,
    "route_price": 55.75,
    "distance_meters": 30000,
    "estimated_duration_minutes": 50,
    "city_route": true,
    "waypoints": [
      {
        "location_id": 5,
        "order": 1
      },
      {
        "location_id": 6,
        "order": 2
      }
    ]
  }')

echo "$UPDATE_RESPONSE" | jq '.'

# Test 4: Verify the update
echo -e "\n4. Verifying the update:"
curl -s "$BASE_URL/routes/$ROUTE_ID" | jq '.'

# Test 5: Get all routes (should include our created route)
echo -e "\n5. Getting all routes:"
curl -s "$BASE_URL/routes" | jq '.'

# Test 6: Test route statistics
echo -e "\n6. Getting route statistics:"
curl -s "$BASE_URL/routes/statistics" | jq '.'

# Test 7: Test routes by price range
echo -e "\n7. Testing routes by price range:"
curl -s "$BASE_URL/routes/price-range?min_price=50&max_price=60" | jq '.'

# Test 8: Test routes by distance range
echo -e "\n8. Testing routes by distance range:"
curl -s "$BASE_URL/routes/distance-range?min_distance=25000&max_distance=35000" | jq '.'

# Test 9: Delete the route
echo -e "\n9. Deleting the route:"
DELETE_RESPONSE=$(curl -s -X DELETE "$BASE_URL/routes/$ROUTE_ID")
echo "$DELETE_RESPONSE" | jq '.'

# Test 10: Verify deletion (should return 404)
echo -e "\n10. Verifying deletion (should return 404):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes/$ROUTE_ID"

# Test 11: Test invalid route ID
echo -e "\n11. Testing invalid route ID (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes/invalid"

# Test 12: Test non-existent route ID
echo -e "\n12. Testing non-existent route ID (should return 404):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes/99999"

echo -e "\nRoute CRUD tests completed!" 