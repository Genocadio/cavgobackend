#!/bin/bash

# Test script for location CRUD operations
# Make sure your server is running on localhost:8080

BASE_URL="http://localhost:8080"

echo "Testing Location CRUD Operations..."
echo "=================================="

# Test 1: Create a location
echo -e "\n1. Creating a new location:"
CREATE_RESPONSE=$(curl -s -X POST "$BASE_URL/locations" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "New York City",
    "custom_name": "NYC Office",
    "province": "kigali",
    "district": "gasabo"
  }')

echo "$CREATE_RESPONSE" | jq '.'

# Extract location ID from response
LOCATION_ID=$(echo "$CREATE_RESPONSE" | jq -r '.id')
echo "Created location ID: $LOCATION_ID"

# Test 2: Get the created location
echo -e "\n2. Getting the created location:"
curl -s "$BASE_URL/locations/$LOCATION_ID" | jq '.'

# Test 3: Update the location
echo -e "\n3. Updating the location:"
UPDATE_RESPONSE=$(curl -s -X PUT "$BASE_URL/locations/$LOCATION_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "Updated New York City",
    "custom_name": "Updated NYC Office",
    "province": "kigali",
    "district": "gasabo"
  }')

echo "$UPDATE_RESPONSE" | jq '.'

# Test 3.5: Update location with province/district change (should generate new code)
echo -e "\n3.5. Updating location with province/district change (should generate new code):"
UPDATE_RESPONSE2=$(curl -s -X PUT "$BASE_URL/locations/$LOCATION_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "Updated New York City",
    "custom_name": "Updated NYC Office",
    "province": "north",
    "district": "musanze"
  }')

echo "$UPDATE_RESPONSE2" | jq '.'

# Test 4: Verify the update
echo -e "\n4. Verifying the update:"
curl -s "$BASE_URL/locations/$LOCATION_ID" | jq '.'

# Test 5: Get all locations (should include our created location)
echo -e "\n5. Getting all locations:"
curl -s "$BASE_URL/locations" | jq '.'

# Test 6: Search for the location
echo -e "\n6. Searching for the location by custom name:"
curl -s "$BASE_URL/locations?search=NYC" | jq '.'

# Test 7: Delete the location
echo -e "\n7. Deleting the location:"
DELETE_RESPONSE=$(curl -s -X DELETE "$BASE_URL/locations/$LOCATION_ID")
echo "$DELETE_RESPONSE" | jq '.'

# Test 8: Verify deletion (should return 404)
echo -e "\n8. Verifying deletion (should return 404):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/locations/$LOCATION_ID"

# Test 9: Test invalid location ID
echo -e "\n9. Testing invalid location ID (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/locations/invalid"

# Test 10: Test non-existent location ID
echo -e "\n10. Testing non-existent location ID (should return 404):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/locations/99999"

echo -e "\nLocation CRUD tests completed!" 