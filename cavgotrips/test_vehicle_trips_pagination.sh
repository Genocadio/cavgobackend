#!/bin/bash

# Test script to verify GetTripsByVehicleID returns paginated response
# Make sure the server is running first

echo "Testing GetTripsByVehicleID pagination..."

# Test with default pagination (limit=20, offset=0)
echo "1. Testing default pagination (limit=20, offset=0):"
curl -s "http://localhost:8080/trips/vehicle/1" | jq '.'

echo -e "\n2. Testing with custom pagination (limit=2, offset=0):"
curl -s "http://localhost:8080/trips/vehicle/1?limit=2&offset=0" | jq '.'

echo -e "\n3. Testing with custom pagination (limit=2, offset=2):"
curl -s "http://localhost:8080/trips/vehicle/1?limit=2&offset=2" | jq '.'

echo -e "\n4. Testing with status filter and pagination:"
curl -s "http://localhost:8080/trips/vehicle/1?status=SCHEDULED&limit=1&offset=0" | jq '.'

echo -e "\n5. Testing response structure validation:"
response=$(curl -s "http://localhost:8080/trips/vehicle/1?limit=1&offset=0")

# Check if response has the expected pagination fields
if echo "$response" | jq -e '.trips' > /dev/null 2>&1; then
    echo "✓ Response contains 'trips' field"
else
    echo "✗ Response missing 'trips' field"
fi

if echo "$response" | jq -e '.total' > /dev/null 2>&1; then
    echo "✓ Response contains 'total' field"
else
    echo "✗ Response missing 'total' field"
fi

if echo "$response" | jq -e '.limit' > /dev/null 2>&1; then
    echo "✓ Response contains 'limit' field"
else
    echo "✗ Response missing 'limit' field"
fi

if echo "$response" | jq -e '.offset' > /dev/null 2>&1; then
    echo "✓ Response contains 'offset' field"
else
    echo "✗ Response missing 'offset' field"
fi

echo -e "\nTest completed!"
