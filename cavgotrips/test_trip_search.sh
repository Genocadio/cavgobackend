#!/bin/bash

# Test script for enhanced trip search functionality
# This script tests both name-based and code-based search capabilities

BASE_URL="http://localhost:8080"
API_BASE="$BASE_URL/api/v1"

echo "🧪 Testing Enhanced Trip Search Functionality"
echo "=============================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test counter
TESTS_PASSED=0
TESTS_FAILED=0

# Function to run a test
run_test() {
    local test_name="$1"
    local endpoint="$2"
    local expected_status="$3"
    
    echo -e "\n${BLUE}Testing: $test_name${NC}"
    echo "Endpoint: $endpoint"
    
    response=$(curl -s -w "\n%{http_code}" "$endpoint")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" -eq "$expected_status" ]; then
        echo -e "${GREEN}✅ PASSED${NC} - Status: $http_code"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        echo -e "${RED}❌ FAILED${NC} - Expected: $expected_status, Got: $http_code"
        echo "Response: $body"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
}

# Function to run a test and check response content
run_test_with_content() {
    local test_name="$1"
    local endpoint="$2"
    local expected_status="$3"
    local content_check="$4"
    
    echo -e "\n${BLUE}Testing: $test_name${NC}"
    echo "Endpoint: $endpoint"
    
    response=$(curl -s -w "\n%{http_code}" "$endpoint")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" -eq "$expected_status" ]; then
        if echo "$body" | grep -q "$content_check"; then
            echo -e "${GREEN}✅ PASSED${NC} - Status: $http_code, Content check passed"
            TESTS_PASSED=$((TESTS_PASSED + 1))
        else
            echo -e "${YELLOW}⚠️  PARTIAL${NC} - Status: $http_code, Content check failed"
            echo "Expected content: $content_check"
            echo "Response: $body"
            TESTS_FAILED=$((TESTS_FAILED + 1))
        fi
    else
        echo -e "${RED}❌ FAILED${NC} - Expected: $expected_status, Got: $http_code"
        echo "Response: $body"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
}

echo -e "\n${YELLOW}1. Testing Basic Trip Search (Name-based)${NC}"
run_test "Get all trips" "$API_BASE/trips" 200
run_test "Search by origin name" "$API_BASE/trips?origin=kigali" 200
run_test "Search by destination name" "$API_BASE/trips?destination=musanze" 200
run_test "Search by both origin and destination" "$API_BASE/trips?origin=kigali&destination=musanze" 200

echo -e "\n${YELLOW}2. Testing Enhanced Trip Search (Code-based)${NC}"
run_test "Search by origin code (province)" "$API_BASE/trips?origin=1" 200
run_test "Search by origin code (district)" "$API_BASE/trips?origin=11" 200
run_test "Search by origin code (full)" "$API_BASE/trips?origin=110" 200
run_test "Search by destination code" "$API_BASE/trips?destination=23" 200
run_test "Search by both origin and destination codes" "$API_BASE/trips?origin=110&destination=230" 200

echo -e "\n${YELLOW}3. Testing Mixed Search (Name + Code)${NC}"
run_test "Search origin by name, destination by code" "$API_BASE/trips?origin=kigali&destination=230" 200
run_test "Search origin by code, destination by name" "$API_BASE/trips?origin=110&destination=musanze" 200

echo -e "\n${YELLOW}4. Testing Search with Additional Filters${NC}"
run_test "Search with company filter" "$API_BASE/trips?origin=110&destination=230&company=express" 200
run_test "Search with status filter" "$API_BASE/trips?origin=110&status=SCHEDULED" 200
run_test "Search with city_route filter" "$API_BASE/trips?origin=110&city_route=true" 200

echo -e "\n${YELLOW}5. Testing Pagination${NC}"
run_test "Search with limit" "$API_BASE/trips?origin=110&limit=5" 200
run_test "Search with offset" "$API_BASE/trips?origin=110&limit=5&offset=10" 200

echo -e "\n${YELLOW}6. Testing Edge Cases${NC}"
run_test "Search with empty parameters" "$API_BASE/trips?origin=&destination=" 200
run_test "Search with non-numeric code" "$API_BASE/trips?origin=abc" 200
run_test "Search with very long code" "$API_BASE/trips?origin=123456789" 200

echo -e "\n${YELLOW}7. Testing Error Cases${NC}"
run_test "Invalid limit parameter" "$API_BASE/trips?limit=invalid" 400
run_test "Invalid offset parameter" "$API_BASE/trips?offset=invalid" 400
run_test "Invalid city_route parameter" "$API_BASE/trips?city_route=invalid" 400

echo -e "\n${YELLOW}8. Testing Response Format${NC}"
run_test_with_content "Check response has trips array" "$API_BASE/trips?origin=110" 200 '"trips"'
run_test_with_content "Check response has total count" "$API_BASE/trips?origin=110" 200 '"total"'
run_test_with_content "Check response has pagination info" "$API_BASE/trips?origin=110&limit=5" 200 '"limit"'

echo -e "\n${YELLOW}9. Testing Location Code Matching${NC}"
# Test that code-based search returns appropriate results
run_test_with_content "Code search returns results" "$API_BASE/trips?origin=110" 200 '"trips"'
run_test_with_content "Name search returns results" "$API_BASE/trips?origin=kigali" 200 '"trips"'

echo -e "\n${YELLOW}10. Testing Performance${NC}"
echo "Testing response time for code-based search..."
start_time=$(date +%s.%N)
curl -s "$API_BASE/trips?origin=110&limit=10" > /dev/null
end_time=$(date +%s.%N)
code_search_time=$(echo "$end_time - $start_time" | bc)

echo "Testing response time for name-based search..."
start_time=$(date +%s.%N)
curl -s "$API_BASE/trips?origin=kigali&limit=10" > /dev/null
end_time=$(date +%s.%N)
name_search_time=$(echo "$end_time - $start_time" | bc)

echo -e "${GREEN}Code search time: ${code_search_time}s${NC}"
echo -e "${GREEN}Name search time: ${name_search_time}s${NC}"

# Summary
echo -e "\n${BLUE}=============================================="
echo "Test Summary"
echo "=============================================="
echo -e "${GREEN}Tests Passed: $TESTS_PASSED${NC}"
echo -e "${RED}Tests Failed: $TESTS_FAILED${NC}"
echo -e "Total Tests: $((TESTS_PASSED + TESTS_FAILED))${NC}"

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "\n${GREEN}🎉 All tests passed! Enhanced trip search is working correctly.${NC}"
    exit 0
else
    echo -e "\n${RED}⚠️  Some tests failed. Please check the implementation.${NC}"
    exit 1
fi 