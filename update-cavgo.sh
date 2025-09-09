#!/bin/bash

# Update CavGo System Script - Droplet Version
# Updates Docker images to latest versions without deleting data
# Execute this script directly on the droplet

set -e  # Exit on any error

# Configuration
DOCKER_USERNAME="genoyves"
DOCKER_REPOSITORY="cavgo-system"
COMPOSE_FILE="docker-compose.yml"
BACKUP_COMPOSE_FILE="docker-compose.backup.yml"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if Docker Compose is available
check_docker_compose() {
    if docker compose version >/dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
        print_success "Using Docker Compose V2"
    elif docker-compose --version >/dev/null 2>&1; then
        COMPOSE_CMD="docker-compose"
        print_success "Using Docker Compose V1"
    else
        print_error "Docker Compose not found"
        exit 1
    fi
}

# Function to backup current compose file
backup_compose_file() {
    if [ -f "$COMPOSE_FILE" ]; then
        cp "$COMPOSE_FILE" "$BACKUP_COMPOSE_FILE"
        print_success "Backed up compose file to $BACKUP_COMPOSE_FILE"
    else
        print_error "Compose file $COMPOSE_FILE not found"
        exit 1
    fi
}



# Function to update a specific service
update_service() {
    local service_name=$1
    
    # Map service names to correct image tags
    case $service_name in
        "eurekacavgo")
            image_tag="eureka"
            ;;
        "cavgomain")
            image_tag="main"
            ;;
        "cavgogateway")
            image_tag="gateway"
            ;;
        "cavgotrips")
            image_tag="trips"
            ;;
        "cavgobooking")
            image_tag="booking"
            ;;
        "cavgomqt")
            image_tag="cavgomqt"
            ;;
        "cavgoussd")
            image_tag="ussd"
            ;;
        *)
            image_tag="$service_name"
            ;;
    esac
    
    local image_name="${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:${image_tag}"
    
    print_status "Updating $service_name..."
    
    # Pull latest image
    print_status "Pulling latest image for $service_name..."
    if docker pull "$image_name"; then
        print_success "Successfully pulled $image_name"
    else
        print_error "Failed to pull $image_name"
        return 1
    fi
    
    # Stop the service
    print_status "Stopping $service_name..."
    $COMPOSE_CMD stop "$service_name" || true
    
    # Remove the container (but keep volumes)
    print_status "Removing old container for $service_name..."
    $COMPOSE_CMD rm -f "$service_name" || true
    
    # Start the service with new image
    print_status "Starting $service_name with new image..."
    if $COMPOSE_CMD up -d "$service_name"; then
        print_success "Successfully started $service_name"
        return 0
    else
        print_error "Failed to start $service_name"
        return 1
    fi
}

# Function to rollback to previous version
rollback_service() {
    local service_name=$1
    
    print_warning "Rolling back $service_name..."
    
    # Stop current service
    $COMPOSE_CMD stop "$service_name" || true
    $COMPOSE_CMD rm -f "$service_name" || true
    
    # Restore backup compose file
    if [ -f "$BACKUP_COMPOSE_FILE" ]; then
        cp "$BACKUP_COMPOSE_FILE" "$COMPOSE_FILE"
        print_success "Restored backup compose file"
    fi
    
    # Start with previous image
    $COMPOSE_CMD up -d "$service_name"
    print_success "$service_name rollback completed"
    return 0
}

# Function to update all services
update_all_services() {
    local services=("eurekacavgo" "cavgomain" "cavgogateway" "cavgotrips" "cavgobooking" "cavgomqt" "cavgoussd")
    local failed_services=()
    
    print_status "Starting update process for all services..."
    
    for service in "${services[@]}"; do
        if update_service "$service"; then
            print_success "$service updated successfully"
        else
            print_error "$service update failed"
            failed_services+=("$service")
        fi
        echo ""
    done
    
    # Report results
    if [ ${#failed_services[@]} -eq 0 ]; then
        print_success "All services updated successfully!"
        return 0
    else
        print_error "The following services failed to update: ${failed_services[*]}"
        return 1
    fi
}

# Function to show current status
show_status() {
    print_status "Current service status:"
    $COMPOSE_CMD ps
    
    echo ""
    print_status "Docker images:"
    docker images | grep "$DOCKER_USERNAME/$DOCKER_REPOSITORY"
    
    echo ""
    print_status "Disk usage:"
    df -h
}

# Function to clean up old images
cleanup_old_images() {
    print_status "Cleaning up old Docker images..."
    
    # Remove dangling images
    docker image prune -f
    
    # Remove old versions of our images (keep only latest)
    docker images "$DOCKER_USERNAME/$DOCKER_REPOSITORY" --format "table {{.Repository}}:{{.Tag}}\t{{.ID}}" | \
    grep -v "latest" | \
    awk '{print $2}' | \
    xargs -r docker rmi || true
    
    print_success "Old images cleaned up"
}

# Main execution
print_status "🚀 Starting CavGo System Update"
print_status "Docker Hub Repository: $DOCKER_USERNAME/$DOCKER_REPOSITORY"
echo ""

# Check if we're in the right directory
if [ ! -f "$COMPOSE_FILE" ]; then
    print_error "Compose file $COMPOSE_FILE not found in current directory"
    print_status "Please run this script from the directory containing your docker-compose.yml file"
    exit 1
fi

# Check Docker Compose
check_docker_compose

# Show current status
print_status "Current system status:"
show_status
echo ""

# Ask for confirmation
print_warning "This will update all CavGo services to the latest images."
print_warning "Data and volumes will be preserved, but services will be restarted."
echo ""
read -p "Do you want to continue? (y/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    print_status "Update cancelled by user"
    exit 0
fi

# Backup compose file
backup_compose_file

# Update all services
if update_all_services; then
    print_success "🎉 All services updated successfully!"
    
    # Clean up old images
    cleanup_old_images
    
    # Show final status
    echo ""
    print_status "Final system status:"
    show_status
    
    # Remove backup file on success
    rm -f "$BACKUP_COMPOSE_FILE"
    print_success "Backup file removed"
    
else
    print_error "❌ Some services failed to update"
    echo ""
    print_warning "You can try to rollback individual services or restore from backup"
    print_status "Backup compose file saved as: $BACKUP_COMPOSE_FILE"
    exit 1
fi

echo ""
print_success "Update process completed!"
print_status "All services are now running with the latest images"
