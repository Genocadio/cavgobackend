#!/bin/bash

# Test script for location code generation when province/district changes
# Make sure your server is running on localhost:8080

BASE_URL="http://localhost:8080"

echo "Testing Location Code Generation on Province/District Changes..."
echo "=============================================================="

# Test 1: Create initial location in Kigali/Gasabo
echo -e "\n1. Creating initial location in Kigali/Gasabo:"
CREATE_RESPONSE=$(curl -s -X POST "$BASE_URL/locations" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "Test Location",
    "custom_name": "Test Office",
    "province": "kigali",
    "district": "gasabo"
  }')

echo "$CREATE_RESPONSE" | jq '.'
LOCATION_ID=$(echo "$CREATE_RESPONSE" | jq -r '.id')
echo "Created location ID: $LOCATION_ID"
echo "Initial code: $(echo "$CREATE_RESPONSE" | jq -r '.code')"

# Test 2: Update to different district in same province (Kigali/Kicukiro)
echo -e "\n2. Updating to different district in same province (Kigali/Kicukiro):"
UPDATE_RESPONSE1=$(curl -s -X PUT "$BASE_URL/locations/$LOCATION_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "Test Location",
    "custom_name": "Test Office",
    "province": "kigali",
    "district": "kicukiro"
  }')

echo "$UPDATE_RESPONSE1" | jq '.'
echo "New code after district change: $(echo "$UPDATE_RESPONSE1" | jq -r '.code')"

# Test 3: Update to different province (North/Musanze)
echo -e "\n3. Updating to different province (North/Musanze):"
UPDATE_RESPONSE2=$(curl -s -X PUT "$BASE_URL/locations/$LOCATION_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "Test Location",
    "custom_name": "Test Office",
    "province": "north",
    "district": "musanze"
  }')

echo "$UPDATE_RESPONSE2" | jq '.'
echo "New code after province change: $(echo "$UPDATE_RESPONSE2" | jq -r '.code')"

# Test 4: Update to another province/district combination (East/Kayonza)
echo -e "\n4. Updating to another province/district combination (East/Kayonza):"
UPDATE_RESPONSE3=$(curl -s -X PUT "$BASE_URL/locations/$LOCATION_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "Test Location",
    "custom_name": "Test Office",
    "province": "east",
    "district": "kayonza"
  }')

echo "$UPDATE_RESPONSE3" | jq '.'
echo "New code after another change: $(echo "$UPDATE_RESPONSE3" | jq -r '.code')"

# Test 5: Test error case - changing province without district
echo -e "\n5. Testing error case - changing province without district (should fail):"
ERROR_RESPONSE=$(curl -s -X PUT "$BASE_URL/locations/$LOCATION_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "Test Location",
    "custom_name": "Test Office",
    "province": "south"
  }')

echo "$ERROR_RESPONSE" | jq '.'

# Test 6: Test error case - changing district without province
echo -e "\n6. Testing error case - changing district without province (should fail):"
ERROR_RESPONSE2=$(curl -s -X PUT "$BASE_URL/locations/$LOCATION_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 40.7128,
    "longitude": -74.0060,
    "google_place_name": "Test Location",
    "custom_name": "Test Office",
    "district": "huye"
  }')

echo "$ERROR_RESPONSE2" | jq '.'

# Test 7: Verify final state
echo -e "\n7. Verifying final state:"
FINAL_RESPONSE=$(curl -s "$BASE_URL/locations/$LOCATION_ID")
echo "$FINAL_RESPONSE" | jq '.'
echo "Final code: $(echo "$FINAL_RESPONSE" | jq -r '.code')"

# Test 8: Clean up - delete the test location
echo -e "\n8. Cleaning up - deleting test location:"
DELETE_RESPONSE=$(curl -s -X DELETE "$BASE_URL/locations/$LOCATION_ID")
echo "$DELETE_RESPONSE" | jq '.'

echo -e "\nLocation code generation tests completed!"
echo -e "\nSummary of code changes:"
echo "- Initial (Kigali/Gasabo): Should be 11001"
echo "- After district change (Kigali/Kicukiro): Should be 12001"
echo "- After province change (North/Musanze): Should be 23001"
echo "- After another change (East/Kayonza): Should be 33001" 