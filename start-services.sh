#!/bin/bash

# CAVGO Microservices Startup Script

set -e

echo "🚀 Starting CAVGO Microservices..."

# Function to check if Docker is running
check_docker() {
    if ! docker info > /dev/null 2>&1; then
        echo "❌ Docker is not running. Please start Docker and try again."
        exit 1
    fi
}

# Function to check if ports are available
check_ports() {
    local ports=("5432" "6060" "6070" "6080" "8080" "8761")
    local conflicts=()
    
    for port in "${ports[@]}"; do
        if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
            conflicts+=($port)
        fi
    done
    
    if [ ${#conflicts[@]} -ne 0 ]; then
        echo "⚠️  Warning: The following ports are already in use:"
        printf '   %s\n' "${conflicts[@]}"
        echo "   Some services may fail to start."
        read -p "   Continue anyway? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
}

# Function to start services
start_services() {
    echo "📦 Building and starting services..."
    docker-compose up -d --build
    
    echo "⏳ Waiting for services to be ready..."
    sleep 10
    
    # Check service health
    echo "🔍 Checking service health..."
    docker-compose ps
}

# Function to show service URLs
show_urls() {
    echo ""
    echo "🌐 Service URLs:"
    echo "   Eureka Dashboard: http://localhost:8761"
    echo "   API Gateway:      http://localhost:8080"
    echo "   Cavgomain:        http://localhost:6060"
    echo "   Cavgotrips:       http://localhost:6080"
    echo "   Cavgobooks:       http://localhost:6070"
    echo ""
    echo "📊 View logs: docker-compose logs -f"
    echo "🛑 Stop services: docker-compose down"
}

# Main execution
main() {
    check_docker
    check_ports
    start_services
    show_urls
}

# Handle command line arguments
case "${1:-}" in
    "stop")
        echo "🛑 Stopping CAVGO services..."
        docker-compose down
        echo "✅ Services stopped."
        ;;
    "restart")
        echo "🔄 Restarting CAVGO services..."
        docker-compose down
        docker-compose up -d --build
        echo "✅ Services restarted."
        ;;
    "logs")
        echo "📋 Showing logs..."
        docker-compose logs -f
        ;;
    "status")
        echo "📊 Service status:"
        docker-compose ps
        ;;
    "clean")
        echo "🧹 Cleaning up..."
        docker-compose down -v
        docker system prune -f
        echo "✅ Cleanup completed."
        ;;
    *)
        main
        ;;
esac 