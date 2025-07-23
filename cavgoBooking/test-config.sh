#!/bin/bash

echo "=== Testing Configuration ==="

echo "1. Testing local environment variables:"
echo "   DATABASE_URL: ${DATABASE_URL:-'not set'}"
echo "   SERVER_ADDRESS: ${SERVER_ADDRESS:-'not set'}"
echo "   PORT: ${PORT:-'not set'}"
echo "   ENVIRONMENT: ${ENVIRONMENT:-'not set'}"
echo "   TRIP_SERVICE_URL: ${TRIP_SERVICE_URL:-'not set'}"
echo "   EUREKA_SERVER_URL: ${EUREKA_SERVER_URL:-'not set'}"
echo "   EUREKA_APP_NAME: ${EUREKA_APP_NAME:-'not set'}"
echo "   EUREKA_REGISTER: ${EUREKA_REGISTER:-'not set'}"
echo "   EUREKA_PREFER_IP: ${EUREKA_PREFER_IP:-'not set'}"

echo ""
echo "2. Testing .env file (if exists):"
if [ -f ".env" ]; then
    echo "   .env file found"
    grep -E "^(DATABASE_URL|SERVER_ADDRESS|PORT|ENVIRONMENT|TRIP_SERVICE_URL|EUREKA_)" .env || echo "   No relevant variables found in .env"
else
    echo "   .env file not found (expected in Docker)"
fi

echo ""
echo "3. Testing network connectivity:"
echo "   Testing localhost:8761 (Eureka):"
if curl -s --connect-timeout 5 http://localhost:8761/actuator/health > /dev/null 2>&1; then
    echo "   ✓ Eureka is accessible"
else
    echo "   ✗ Eureka is not accessible"
fi

echo "   Testing localhost:5432 (PostgreSQL):"
if nc -z localhost 5432 2>/dev/null; then
    echo "   ✓ PostgreSQL is accessible"
else
    echo "   ✗ PostgreSQL is not accessible"
fi

echo ""
echo "4. Testing Docker environment (if running in container):"
if [ -f /.dockerenv ]; then
    echo "   ✓ Running in Docker container"
    echo "   Container hostname: $(hostname)"
    echo "   Container IP: $(hostname -i)"
else
    echo "   ✗ Not running in Docker container"
fi

echo ""
echo "=== Configuration Test Complete ===" 