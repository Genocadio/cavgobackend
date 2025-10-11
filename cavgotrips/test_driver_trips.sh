#!/bin/bash

# Test script for driver trips functionality
# This script demonstrates how to fetch trips by driver ID

BASE_URL="http://localhost:8080"

echo "=== Testing Driver Trips Functionality ==="
echo

# Test 1: Get trips by driver ID (assuming driver ID 1 exists)
echo "1. Testing GET /trips/driver/1"
echo "   Fetching all trips for driver ID 1..."
curl -s -X GET "$BASE_URL/trips/driver/1" | jq '.' 2>/dev/null || echo "   Response received (install jq for formatted output)"
echo

# Test 2: Get trips by driver ID with pagination
echo "2. Testing GET /trips/driver/1 with pagination"
echo "   Fetching first 5 trips for driver ID 1..."
curl -s -X GET "$BASE_URL/trips/driver/1?limit=5&offset=0" | jq '.' 2>/dev/null || echo "   Response received (install jq for formatted output)"
echo

# Test 3: Get trips by driver ID with status filter
echo "3. Testing GET /trips/driver/1 with status filter"
echo "   Fetching SCHEDULED trips for driver ID 1..."
curl -s -X GET "$BASE_URL/trips/driver/1?status=SCHEDULED" | jq '.' 2>/dev/null || echo "   Response received (install jq for formatted output)"
echo

# Test 4: Get trips by driver ID with session UUID
echo "4. Testing GET /trips/driver/1 with session UUID"
echo "   Fetching trips for driver ID 1 with session management..."
curl -s -X GET "$BASE_URL/trips/driver/1?session_uuid=test-session-123" | jq '.' 2>/dev/null || echo "   Response received (install jq for formatted output)"
echo

# Test 5: Test with invalid driver ID
echo "5. Testing with invalid driver ID"
echo "   Testing error handling with invalid driver ID..."
curl -s -X GET "$BASE_URL/trips/driver/invalid" | jq '.' 2>/dev/null || echo "   Response received (install jq for formatted output)"
echo

echo "=== Test completed ==="
echo
echo "Expected behavior:"
echo "- Valid driver ID should return trips with driver information including driver.id"
echo "- Pagination should work with limit and offset parameters"
echo "- Status filtering should work"
echo "- Session management should work for SSE subscriptions"
echo "- Invalid driver ID should return 400 Bad Request"
echo
echo "Driver information in trip response should include:"
echo "- vehicle.driver.id: Driver's unique identifier"
echo "- vehicle.driver.name: Driver's full name"
echo "- vehicle.driver.phone: Driver's phone number"
