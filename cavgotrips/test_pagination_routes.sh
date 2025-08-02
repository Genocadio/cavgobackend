#!/bin/bash

# Test script for route pagination
# Make sure your server is running on localhost:8080

BASE_URL="http://localhost:8080"

echo "Testing Route Pagination..."
echo "=========================="

# Test 1: Get routes with pagination (page=1, limit=20)
echo -e "\n1. Testing routes with pagination (page=1, limit=20):"
curl -s "$BASE_URL/routes?page=1&limit=20" | jq '.'

# Test 2: Get routes with pagination (page=1, limit=5)
echo -e "\n2. Testing routes with pagination (page=1, limit=5):"
curl -s "$BASE_URL/routes?page=1&limit=5" | jq '.'

# Test 3: Get routes with pagination (page=2, limit=5)
echo -e "\n3. Testing routes with pagination (page=2, limit=5):"
curl -s "$BASE_URL/routes?page=2&limit=5" | jq '.'

# Test 4: Get routes with default pagination (no parameters)
echo -e "\n4. Testing routes with default pagination (no parameters):"
curl -s "$BASE_URL/routes" | jq '.'

# Test 5: Test invalid page parameter
echo -e "\n5. Testing invalid page parameter (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes?page=0"

# Test 6: Test invalid limit parameter
echo -e "\n6. Testing invalid limit parameter (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes?limit=0"

# Test 7: Test limit exceeding maximum
echo -e "\n7. Testing limit exceeding maximum (should cap at 100):"
curl -s "$BASE_URL/routes?limit=150" | jq '.pagination'

echo -e "\nRoute pagination tests completed!" 