#!/bin/bash

# Test script for the new vehicle trips endpoint
# This script tests the GET /trips/vehicle/{vehicle_id} endpoint

BASE_URL="http://localhost:8080"
API_BASE="$BASE_URL/api/v1"

echo "🚗 Testing Vehicle Trips Endpoint"
echo "=================================="

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

echo -e "\n${YELLOW}1. Testing Basic Vehicle Trips Endpoint${NC}"
run_test "Get trips for vehicle ID 1" "$API_BASE/trips/vehicle/1" 200
run_test "Get trips for vehicle ID 100" "$API_BASE/trips/vehicle/100" 200
run_test "Get trips for vehicle ID 999" "$API_BASE/trips/vehicle/999" 200

echo -e "\n${YELLOW}2. Testing Vehicle Trips with Status Filter${NC}"
run_test "Get SCHEDULED trips for vehicle 1" "$API_BASE/trips/vehicle/1?status=SCHEDULED" 200
run_test "Get IN_PROGRESS trips for vehicle 1" "$API_BASE/trips/vehicle/1?status=IN_PROGRESS" 200
run_test "Get COMPLETED trips for vehicle 1" "$API_BASE/trips/vehicle/1?status=COMPLETED" 200
run_test "Get NOT_COMPLETED trips for vehicle 1" "$API_BASE/trips/vehicle/1?status=NOT_COMPLETED" 200

echo -e "\n${YELLOW}3. Testing Vehicle Trips with Pagination${NC}"
run_test "Get trips for vehicle 1 with limit 5" "$API_BASE/trips/vehicle/1?limit=5" 200
run_test "Get trips for vehicle 1 with limit 10 and offset 5" "$API_BASE/trips/vehicle/1?limit=10&offset=5" 200
run_test "Get trips for vehicle 1 with limit 1" "$API_BASE/trips/vehicle/1?limit=1" 200

echo -e "\n${YELLOW}4. Testing Vehicle Trips with Combined Filters${NC}"
run_test "Get SCHEDULED trips for vehicle 1 with limit 5" "$API_BASE/trips/vehicle/1?status=SCHEDULED&limit=5" 200
run_test "Get IN_PROGRESS trips for vehicle 1 with limit 3 and offset 2" "$API_BASE/trips/vehicle/1?status=IN_PROGRESS&limit=3&offset=2" 200

echo -e "\n${YELLOW}5. Testing Vehicle Trips with Session Support${NC}"
run_test "Get trips for vehicle 1 with new session" "$API_BASE/trips/vehicle/1?limit=5" 200
run_test "Get trips for vehicle 1 with existing session" "$API_BASE/trips/vehicle/1?limit=5&session_uuid=test123" 200

echo -e "\n${YELLOW}6. Testing Error Cases${NC}"
run_test "Invalid vehicle ID (non-numeric)" "$API_BASE/trips/vehicle/abc" 400
run_test "Invalid vehicle ID (negative)" "$API_BASE/trips/vehicle/-1" 400
run_test "Invalid limit parameter" "$API_BASE/trips/vehicle/1?limit=invalid" 400
run_test "Invalid offset parameter" "$API_BASE/trips/vehicle/1?offset=invalid" 400
run_test "Invalid status parameter" "$API_BASE/trips/vehicle/1?status=INVALID_STATUS" 200

echo -e "\n${YELLOW}7. Testing Response Format${NC}"
run_test_with_content "Check response has trips array" "$API_BASE/trips/vehicle/1" 200 '"trips"'
run_test_with_content "Check response has total count" "$API_BASE/trips/vehicle/1" 200 '"total"'
run_test_with_content "Check response has pagination info" "$API_BASE/trips/vehicle/1?limit=5" 200 '"limit"'
run_test_with_content "Check response has offset info" "$API_BASE/trips/vehicle/1?limit=5&offset=10" 200 '"offset"'

echo -e "\n${YELLOW}8. Testing Edge Cases${NC}"
run_test "Very large vehicle ID" "$API_BASE/trips/vehicle/999999999" 200
run_test "Vehicle ID 0" "$API_BASE/trips/vehicle/0" 400
run_test "Empty vehicle ID" "$API_BASE/trips/vehicle/" 404

echo -e "\n${YELLOW}9. Testing Performance${NC}"
echo "Testing response time for vehicle trips endpoint..."
start_time=$(date +%s.%N)
curl -s "$API_BASE/trips/vehicle/1?limit=10" > /dev/null
end_time=$(date +%s.%N)
response_time=$(echo "$end_time - $start_time" | bc)

echo -e "${GREEN}Response time: ${response_time}s${NC}"

echo -e "\n${YELLOW}10. Testing Comparison with Existing Endpoint${NC}"
echo "Comparing with existing /trips?vehicle_id=1 endpoint..."

# Test new endpoint
new_response=$(curl -s "$API_BASE/trips/vehicle/1?limit=5")
new_total=$(echo "$new_response" | grep -o '"total":[0-9]*' | cut -d: -f2)

# Test existing endpoint
existing_response=$(curl -s "$API_BASE/trips?vehicle_id=1&limit=5")
existing_total=$(echo "$existing_response" | grep -o '"total":[0-9]*' | cut -d: -f2)

if [ "$new_total" = "$existing_total" ]; then
    echo -e "${GREEN}✅ Results match${NC} - Both endpoints return same total: $new_total"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    echo -e "${RED}❌ Results don't match${NC} - New: $new_total, Existing: $existing_total"
    TESTS_FAILED=$((TESTS_FAILED + 1))
fi

# Summary
echo -e "\n${BLUE}=============================================="
echo "Test Summary"
echo "=============================================="
echo -e "${GREEN}Tests Passed: $TESTS_PASSED${NC}"
echo -e "${RED}Tests Failed: $TESTS_FAILED${NC}"
echo -e "Total Tests: $((TESTS_PASSED + TESTS_FAILED))${NC}"

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "\n${GREEN}🎉 All tests passed! Vehicle trips endpoint is working correctly.${NC}"
    exit 0
else
    echo -e "\n${RED}⚠️  Some tests failed. Please check the implementation.${NC}"
    exit 1
fi
