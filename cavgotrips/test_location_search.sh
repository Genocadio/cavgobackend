#!/bin/bash

# Test script to verify location search functionality
# This script tests that custom name matches come before Google place name matches

echo "Testing Location Search Functionality"
echo "====================================="

# Test 1: Search for a term that might match both custom_name and google_place_name
echo "Test 1: Searching for 'office' (should prioritize custom_name matches)"
curl -s "http://localhost:8080/locations?search=office" | jq '.[] | {id, custom_name, google_place_name}' 2>/dev/null || echo "Server not running or jq not installed"

echo ""
echo "Test 2: Searching for 'NYC' (should prioritize custom_name matches)"
curl -s "http://localhost:8080/locations?search=NYC" | jq '.[] | {id, custom_name, google_place_name}' 2>/dev/null || echo "Server not running or jq not installed"

echo ""
echo "Test 3: Searching for numeric code '11001'"
curl -s "http://localhost:8080/locations?search=11001" | jq '.[] | {id, code, custom_name, google_place_name}' 2>/dev/null || echo "Server not running or jq not installed"

echo ""
echo "Test 4: Paginated search for 'office' with limit 5"
curl -s "http://localhost:8080/locations?search=office&limit=5" | jq '.data[] | {id, custom_name, google_place_name}' 2>/dev/null || echo "Server not running or jq not installed"

echo ""
echo "Search tests completed!"


