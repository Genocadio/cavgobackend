#!/bin/bash

# CavGo Intelligent Update Script - Droplet Version
# Checks for Docker image updates and only restarts services that have newer versions available
# Preserves data and avoids unnecessary service restarts
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

# Function to get image ID for a service
get_local_image_id() {
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
    
    # Get local image ID if it exists
    docker images -q "$image_name" 2>/dev/null || echo ""
}

# Function to get remote image digest
get_remote_image_digest() {
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
    
    # Get remote manifest digest
    docker manifest inspect "$image_name" --verbose 2>/dev/null | \
    grep -E '"digest":|"mediaType": "application/vnd.docker.distribution.manifest' | \
    head -1 | \
    sed 's/.*"digest": "\([^"]*\)".*/\1/' || echo ""
}

# Function to check if service needs update
check_service_needs_update() {
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
    
    print_status "Checking if $service_name needs update..."
    
    # Get local image ID and creation date
    local local_image_info
    local_image_info=$(docker images --format "table {{.ID}}\t{{.CreatedAt}}" "$image_name" 2>/dev/null | tail -n +2)
    
    if [ -z "$local_image_info" ]; then
        print_status "$service_name: No local image found, needs download"
        return 0  # Needs update (download)
    fi
    
    local local_image_id
    local_image_id=$(echo "$local_image_info" | awk '{print $1}')
    
    # Check if remote image manifest exists
    print_status "Checking remote version for $service_name..."
    if ! docker manifest inspect "$image_name" >/dev/null 2>&1; then
        print_warning "$service_name: Cannot access remote image, assuming no update needed"
        return 1  # Skip update - can't check remote
    fi
    
    # Try to pull only if we suspect there might be updates
    # This approach pulls the image but compares IDs to see if anything changed
    print_status "Pulling latest image to check for updates..."
    
    # Capture the pull output to see if anything was actually downloaded
    local pull_output
    pull_output=$(docker pull "$image_name" 2>&1)
    
    # Get the new image ID after pull
    local new_image_id
    new_image_id=$(docker images -q "$image_name" 2>/dev/null)
    
    if [ -z "$new_image_id" ]; then
        print_error "$service_name: Failed to get image after pull"
        return 1  # Skip update - something went wrong
    fi
    
    # Compare image IDs
    if [ "$local_image_id" != "$new_image_id" ]; then
        print_success "$service_name: Update available (image ID changed: $local_image_id -> $new_image_id)"
        return 0  # Needs update
    elif echo "$pull_output" | grep -q "Image is up to date\|Already exists"; then
        print_status "$service_name: Already up to date"
        return 1  # No update needed
    else
        # If we can't determine, assume update is needed to be safe
        print_status "$service_name: Update status unclear, proceeding with update to be safe"
        return 0  # Needs update
    fi
}



# Function to update a specific service (only if update is needed)
update_service() {
    local service_name=$1
    
    # First check if service needs update
    if ! check_service_needs_update "$service_name"; then
        print_success "$service_name is already up to date, skipping restart"
        return 0
    fi
    
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
    
    print_status "Updating $service_name with new image..."
    
    # Stop the service
    print_status "Stopping $service_name..."
    $COMPOSE_CMD stop "$service_name" || true
    
    # Remove the container (but keep volumes)
    print_status "Removing old container for $service_name..."
    $COMPOSE_CMD rm -f "$service_name" || true
    
    # Start the service with new image
    print_status "Starting $service_name with new image..."
    if $COMPOSE_CMD up -d "$service_name"; then
        print_success "Successfully updated and started $service_name"
        return 0
    else
        print_error "Failed to start $service_name"
        return 1
    fi
}

# Function to force update a specific service (without checking)
force_update_service() {
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
    
    print_status "Force updating $service_name..."
    
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
        print_success "Successfully force updated $service_name"
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
    local updated_services=()
    local skipped_services=()
    
    print_status "Starting intelligent update process for all services..."
    print_status "Only services with available updates will be restarted..."
    echo ""
    
    for service in "${services[@]}"; do
        print_status "Processing $service..."
        
        # Check if service needs update first
        if check_service_needs_update "$service"; then
            # Service needs update, proceed with update
            if update_service "$service"; then
                print_success "$service updated successfully"
                updated_services+=("$service")
            else
                print_error "$service update failed"
                failed_services+=("$service")
            fi
        else
            print_success "$service is already up to date, skipping"
            skipped_services+=("$service")
        fi
        echo ""
    done
    
    # Report results
    echo ""
    print_status "📊 Update Summary:"
    
    if [ ${#updated_services[@]} -gt 0 ]; then
        print_success "Services updated: ${updated_services[*]}"
    fi
    
    if [ ${#skipped_services[@]} -gt 0 ]; then
        print_status "Services already up to date (skipped): ${skipped_services[*]}"
    fi
    
    if [ ${#failed_services[@]} -gt 0 ]; then
        print_error "Services failed to update: ${failed_services[*]}"
        return 1
    fi
    
    if [ ${#updated_services[@]} -eq 0 ] && [ ${#skipped_services[@]} -gt 0 ]; then
        print_success "All services are already up to date! No restarts needed."
    else
        print_success "Update process completed successfully!"
    fi
    
    return 0
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

# Function to show usage
show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -f, --force       Force update all services (skip intelligent checking)"
    echo "  -s, --service     Update specific service only"
    echo "  -h, --help        Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                    # Intelligent update (default)"
    echo "  $0 --force            # Force update all services"
    echo "  $0 --service trips    # Update only cavgotrips service"
    echo ""
}

# Parse command line arguments
FORCE_UPDATE=false
SPECIFIC_SERVICE=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -f|--force)
            FORCE_UPDATE=true
            shift
            ;;
        -s|--service)
            SPECIFIC_SERVICE="$2"
            shift 2
            ;;
        -h|--help)
            show_usage
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
    esac
done

# Main execution
if [ "$FORCE_UPDATE" = true ]; then
    print_status "🚀 Starting CavGo System FORCE Update"
else
    print_status "🚀 Starting CavGo Intelligent System Update"
fi
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

# Ask for confirmation based on mode
if [ "$FORCE_UPDATE" = true ]; then
    print_warning "FORCE MODE: This will update and restart ALL services regardless of whether updates are available."
    print_warning "Data and volumes will be preserved, but ALL services WILL be restarted."
elif [ -n "$SPECIFIC_SERVICE" ]; then
    print_warning "SPECIFIC SERVICE MODE: This will update only the '$SPECIFIC_SERVICE' service."
    print_warning "Data and volumes will be preserved."
else
    print_warning "INTELLIGENT MODE: This will check for updates and only restart services that have newer versions available."
    print_warning "Data and volumes will be preserved. Services already up to date will NOT be restarted."
fi
echo ""
read -p "Do you want to continue? (y/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    print_status "Update cancelled by user"
    exit 0
fi

# Backup compose file
backup_compose_file

# Execute update based on mode
if [ -n "$SPECIFIC_SERVICE" ]; then
    # Validate service name
    valid_services=("eurekacavgo" "cavgomain" "cavgogateway" "cavgotrips" "cavgobooking" "cavgomqt" "cavgoussd")
    valid_service=false
    
    for service in "${valid_services[@]}"; do
        if [ "$SPECIFIC_SERVICE" = "$service" ]; then
            valid_service=true
            break
        fi
    done
    
    if [ "$valid_service" = false ]; then
        print_error "Invalid service name: $SPECIFIC_SERVICE"
        print_status "Valid services: ${valid_services[*]}"
        exit 1
    fi
    
    # Update specific service
    print_status "Updating specific service: $SPECIFIC_SERVICE"
    if [ "$FORCE_UPDATE" = true ]; then
        UPDATE_SUCCESS=$(force_update_service "$SPECIFIC_SERVICE" && echo "true" || echo "false")
    else
        UPDATE_SUCCESS=$(update_service "$SPECIFIC_SERVICE" && echo "true" || echo "false")
    fi
    
    if [ "$UPDATE_SUCCESS" = "true" ]; then
        print_success "🎉 Service $SPECIFIC_SERVICE updated successfully!"
    else
        print_error "❌ Service $SPECIFIC_SERVICE failed to update"
        exit 1
    fi
elif [ "$FORCE_UPDATE" = true ]; then
    # Force update all services
    print_status "Force updating all services..."
    local services=("eurekacavgo" "cavgomain" "cavgogateway" "cavgotrips" "cavgobooking" "cavgomqt" "cavgoussd")
    local failed_services=()
    
    for service in "${services[@]}"; do
        if force_update_service "$service"; then
            print_success "$service force updated successfully"
        else
            print_error "$service force update failed"
            failed_services+=("$service")
        fi
        echo ""
    done
    
    if [ ${#failed_services[@]} -eq 0 ]; then
        print_success "🎉 All services force updated successfully!"
    else
        print_error "❌ The following services failed to update: ${failed_services[*]}"
        exit 1
    fi
else
    # Intelligent update (default)
    if update_all_services; then
        print_success "🎉 Intelligent update process completed successfully!"
    else
        print_error "❌ Some services failed to update"
        echo ""
        print_warning "You can try to rollback individual services or restore from backup"
        print_status "Backup compose file saved as: $BACKUP_COMPOSE_FILE"
        exit 1
    fi
fi

# Clean up old images for all successful modes
cleanup_old_images

# Show final status
echo ""
print_status "Final system status:"
show_status

# Remove backup file on success
rm -f "$BACKUP_COMPOSE_FILE"
print_success "Backup file removed"

echo ""
if [ "$FORCE_UPDATE" = true ]; then
    print_success "Force update process completed!"
elif [ -n "$SPECIFIC_SERVICE" ]; then
    print_success "Specific service update completed!"
else
    print_success "Intelligent update process completed!"
fi
print_status "Services are running with the latest available images"
