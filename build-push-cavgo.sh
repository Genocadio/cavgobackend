#!/bin/bash

# Build and Push Script for CavGo System
# Docker Hub Repository: genoyves/cavgo-system

set -e  # Exit on any error

DOCKER_USERNAME="genoyves"
DOCKER_REPOSITORY="cavgo-system"
DOCKER_REGISTRY="docker.io"

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

# Function to build and push an image
build_and_push() {
    local service_name=$1
    local context_path=$2
    local image_name="${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:${service_name}"

    print_status "Building ${service_name}..."

    # Check if context directory exists
    if [ ! -d "$context_path" ]; then
        print_error "Context directory $context_path does not exist for $service_name"
        return 1
    fi

    # Build the image
    if docker build -t "${image_name}" "$context_path"; then
        print_success "Successfully built ${image_name}"
    else
        print_error "Failed to build ${image_name}"
        return 1
    fi

    # Push the image
    print_status "Pushing ${image_name}..."
    if docker push "${image_name}"; then
        print_success "Successfully pushed ${image_name}"
    else
        print_error "Failed to push ${image_name}"
        return 1
    fi

    echo ""
}

# Function to build and push PostgreSQL with init script
build_and_push_postgres() {
    local service_name="postgres"
    local image_name="${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:${service_name}"
    local temp_dir="./temp-postgres"

    print_status "Building ${service_name} with init script..."

    # Create temporary directory for PostgreSQL build
    mkdir -p "$temp_dir"

    # Create Dockerfile for PostgreSQL with init script
    cat > "$temp_dir/Dockerfile" << 'EOF'
FROM postgres:15-alpine
COPY init-multiple-dbs.sh /docker-entrypoint-initdb.d/
RUN chmod +x /docker-entrypoint-initdb.d/init-multiple-dbs.sh
EOF

    # Copy init script if it exists
    if [ -f "./init-multiple-dbs.sh" ]; then
        cp "./init-multiple-dbs.sh" "$temp_dir/"
    else
        print_warning "init-multiple-dbs.sh not found, creating a basic one..."
        cat > "$temp_dir/init-multiple-dbs.sh" << 'EOF'
#!/bin/bash
set -e
set -u

function create_user_and_database() {
    local database=$1
    echo "Creating user and database '$database'"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
        CREATE USER $database;
        CREATE DATABASE $database;
        GRANT ALL PRIVILEGES ON DATABASE $database TO $database;
EOSQL
}

if [ -n "$POSTGRES_MULTIPLE_DATABASES" ]; then
    echo "Multiple database creation requested: $POSTGRES_MULTIPLE_DATABASES"
    for db in $(echo $POSTGRES_MULTIPLE_DATABASES | tr ',' ' '); do
        create_user_and_database $db
    done
    echo "Multiple databases created"
fi
EOF
    fi

    # Build the image
    if docker build -t "${image_name}" "$temp_dir"; then
        print_success "Successfully built ${image_name}"
    else
        print_error "Failed to build ${image_name}"
        rm -rf "$temp_dir"
        return 1
    fi

    # Push the image
    print_status "Pushing ${image_name}..."
    if docker push "${image_name}"; then
        print_success "Successfully pushed ${image_name}"
    else
        print_error "Failed to push ${image_name}"
        rm -rf "$temp_dir"
        return 1
    fi

    # Clean up temporary directory
    rm -rf "$temp_dir"
    echo ""
}

# Main execution
print_status "Starting build and push process for CavGo System"
print_status "Docker Hub Repository: $DOCKER_USERNAME/$DOCKER_REPOSITORY"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    print_error "Docker is not running. Please start Docker and try again."
    exit 1
fi

# Check if logged in to Docker Hub
if ! docker info | grep -q "Username: $DOCKER_USERNAME" 2>/dev/null; then
    print_warning "You may not be logged in to Docker Hub as $DOCKER_USERNAME"
    print_status "Attempting to login..."
    docker login
fi

# Build and push PostgreSQL with init script
print_status "Building and pushing PostgreSQL with init script..."
# build_and_push_postgres

# Build and push application services
print_status "Building and pushing application services..."

#Build and push each service
# build_and_push "eureka" "./Eurekacavgo"
build_and_push "main" "./cavgomain"
#build_and_push "gateway" "./Cavgogateway"
build_and_push "trips" "./cavgotrips"
# build_and_push "booking" "./cavgoBooking"
# build_and_push "cavgomqt" "./cavgomqt"
# build_and_push "ussd" "./ussdService"

print_success "All images have been built and pushed successfully!"
print_status "You can now use the docker-compose-hub.yml file to deploy using the pushed images."

# List all pushed images
print_status "The following images have been pushed to Docker Hub:"
echo "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:postgres"
echo "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:eureka"
echo "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:main"
echo "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:gateway"
echo "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:trips"
echo "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:booking"
echo "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:ussd"

# Optional: Clean up local images to save space
echo ""
read -p "Do you want to remove local images to save space? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    print_status "Cleaning up local images..."
    docker rmi "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:postgres" 2>/dev/null || true
    docker rmi "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:eureka" 2>/dev/null || true
    docker rmi "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:main" 2>/dev/null || true
    docker rmi "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:gateway" 2>/dev/null || true
    docker rmi "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:trips" 2>/dev/null || true
    docker rmi "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:booking" 2>/dev/null || true
    docker rmi "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:ussd" 2>/dev/null || true
    print_success "Local images cleaned up!"
fi

echo ""
print_success "Build and push process completed successfully!"
print_status "Repository URL: https://hub.docker.com/r/$DOCKER_USERNAME/$DOCKER_REPOSITORY"
