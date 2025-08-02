#!/bin/bash

# Test script for route handler methods
# Make sure your server is running on localhost:8080

BASE_URL="http://localhost:8080"

echo "Testing Route Handler Methods..."
echo "================================"

# Test 1: Get route statistics
echo -e "\n1. Testing route statistics:"
curl -s "$BASE_URL/routes/statistics" | jq '.'

# Test 2: Get routes by price range
echo -e "\n2. Testing routes by price range (min_price=10, max_price=50):"
curl -s "$BASE_URL/routes/price-range?min_price=10&max_price=50" | jq '.'

# Test 3: Get routes by price range (min_price only)
echo -e "\n3. Testing routes by price range (min_price=20 only):"
curl -s "$BASE_URL/routes/price-range?min_price=20" | jq '.'

# Test 4: Get routes by price range (max_price only)
echo -e "\n4. Testing routes by price range (max_price=100 only):"
curl -s "$BASE_URL/routes/price-range?max_price=100" | jq '.'

# Test 5: Get routes by distance range
echo -e "\n5. Testing routes by distance range (min_distance=10000, max_distance=100000):"
curl -s "$BASE_URL/routes/distance-range?min_distance=10000&max_distance=100000" | jq '.'

# Test 6: Get routes by distance range (min_distance only)
echo -e "\n6. Testing routes by distance range (min_distance=5000 only):"
curl -s "$BASE_URL/routes/distance-range?min_distance=5000" | jq '.'

# Test 7: Get routes by distance range (max_distance only)
echo -e "\n7. Testing routes by distance range (max_distance=50000 only):"
curl -s "$BASE_URL/routes/distance-range?max_distance=50000" | jq '.'

# Test 8: Test invalid price range (min > max)
echo -e "\n8. Testing invalid price range (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes/price-range?min_price=50&max_price=10"

# Test 9: Test invalid distance range (min > max)
echo -e "\n9. Testing invalid distance range (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes/distance-range?min_distance=100000&max_distance=50000"

# Test 10: Test invalid price parameter
echo -e "\n10. Testing invalid price parameter (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes/price-range?min_price=invalid"

# Test 11: Test invalid distance parameter
echo -e "\n11. Testing invalid distance parameter (should return 400):"
curl -s -w "HTTP Status: %{http_code}\n" "$BASE_URL/routes/distance-range?min_distance=invalid"

# Test 12: Get all routes (to compare with filtered results)
echo -e "\n12. Getting all routes for comparison:"
curl -s "$BASE_URL/routes" | jq '.'

echo -e "\nRoute handler tests completed!" 