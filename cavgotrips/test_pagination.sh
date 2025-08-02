#!/bin/bash

# Test script for route pagination
# Make sure your server is running on localhost:8080

BASE_URL="http://localhost:8080"

echo "Testing Route Pagination..."
echo "=========================="

# Test 1: Basic pagination
echo -e "\n1. Testing basic pagination (page=1, limit=5):"
curl -s "$BASE_URL/routes?page=1&limit=5" | jq '.'

# Test 2: Second page
echo -e "\n2. Testing second page (page=2, limit=5):"
curl -s "$BASE_URL/routes?page=2&limit=5" | jq '.'

# Test 3: Search with pagination
echo -e "\n3. Testing search with pagination (origin=kigali, page=1, limit=3):"
curl -s "$BASE_URL/routes?origin=kigali&page=1&limit=3" | jq '.'

# Test 4: Filter with pagination
echo -e "\n4. Testing filter with pagination (city_route=true, page=1, limit=5):"
curl -s "$BASE_URL/routes?city_route=true&page=1&limit=5" | jq '.'

# Test 5: Complex search and filter with pagination
echo -e "\n5. Testing complex search and filter with pagination:"
curl -s "$BASE_URL/routes?origin=kigali&city_route=true&page=1&limit=3" | jq '.'

# Test 6: Invalid pagination parameters
echo -e "\n6. Testing invalid pagination parameters (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes?page=0&limit=5"

echo -e "\n7. Testing invalid limit (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes?page=1&limit=150"

echo -e "\nPagination tests completed!" 