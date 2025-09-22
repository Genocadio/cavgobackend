# Build and Push Script for CavGo System
# Docker Hub Repository: genoyves/cavgo-system

param(
    [switch]$SkipCleanup
)

# Set error action preference
$ErrorActionPreference = "Stop"

# Configuration
$DOCKER_USERNAME = "genoyves"
$DOCKER_REPOSITORY = "cavgo-system"
$DOCKER_REGISTRY = "docker.io"

# Colors for output
$RED = "Red"
$GREEN = "Green"
$YELLOW = "Yellow"
$BLUE = "Cyan"

# Function to print colored output
function Write-Status {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor $BLUE
}

function Write-Success {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor $GREEN
}

function Write-Warning {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor $YELLOW
}

function Write-Error {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor $RED
}

# Function to build and push an image
function Build-AndPush {
    param(
        [string]$ServiceName,
        [string]$ContextPath
    )
    
    $ImageName = "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:${ServiceName}"
    
    Write-Status "Building ${ServiceName}..."
    
    # Check if context directory exists
    if (-not (Test-Path $ContextPath -PathType Container)) {
        Write-Error "Context directory $ContextPath does not exist for $ServiceName"
        return $false
    }
    
    # Build the image
    try {
        docker build -t $ImageName $ContextPath
        if ($LASTEXITCODE -eq 0) {
            Write-Success "Successfully built $ImageName"
        } else {
            Write-Error "Failed to build $ImageName"
            return $false
        }
    } catch {
        Write-Error "Failed to build $ImageName"
        return $false
    }
    
    # Push the image
    Write-Status "Pushing $ImageName..."
    try {
        docker push $ImageName
        if ($LASTEXITCODE -eq 0) {
            Write-Success "Successfully pushed $ImageName"
        } else {
            Write-Error "Failed to push $ImageName"
            return $false
        }
    } catch {
        Write-Error "Failed to push $ImageName"
        return $false
    }
    
    Write-Host ""
    return $true
}

# Function to build and push PostgreSQL with init script
function Build-AndPush-Postgres {
    $ServiceName = "postgres"
    $ImageName = "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:${ServiceName}"
    $TempDir = "./temp-postgres"
    
    Write-Status "Building ${ServiceName} with init script..."
    
    # Create temporary directory for PostgreSQL build
    if (Test-Path $TempDir) {
        Remove-Item $TempDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $TempDir | Out-Null
    
    # Create Dockerfile for PostgreSQL with init script
    $DockerfileContent = @"
FROM postgres:15-alpine
COPY init-multiple-dbs.sh /docker-entrypoint-initdb.d/
RUN chmod +x /docker-entrypoint-initdb.d/init-multiple-dbs.sh
"@
    
    $DockerfileContent | Out-File -FilePath "$TempDir/Dockerfile" -Encoding UTF8
    
    # Copy init script if it exists
    if (Test-Path "./init-multiple-dbs.sh") {
        Copy-Item "./init-multiple-dbs.sh" "$TempDir/"
    } else {
        Write-Warning "init-multiple-dbs.sh not found, creating a basic one..."
        $InitScriptContent = @"
#!/bin/bash
set -e
set -u

function create_user_and_database() {
    local database=`$1
    echo "Creating user and database '`$database'"
    psql -v ON_ERROR_STOP=1 --username "`$POSTGRES_USER" <<-EOSQL
        CREATE USER `$database;
        CREATE DATABASE `$database;
        GRANT ALL PRIVILEGES ON DATABASE `$database TO `$database;
EOSQL
}

if [ -n "`$POSTGRES_MULTIPLE_DATABASES" ]; then
    echo "Multiple database creation requested: `$POSTGRES_MULTIPLE_DATABASES"
    for db in `$(echo `$POSTGRES_MULTIPLE_DATABASES | tr ',' ' '); do
        create_user_and_database `$db
    done
    echo "Multiple databases created"
fi
"@
        $InitScriptContent | Out-File -FilePath "$TempDir/init-multiple-dbs.sh" -Encoding UTF8
    }
    
    # Build the image
    try {
        docker build -t $ImageName $TempDir
        if ($LASTEXITCODE -eq 0) {
            Write-Success "Successfully built $ImageName"
        } else {
            Write-Error "Failed to build $ImageName"
            Remove-Item $TempDir -Recurse -Force
            return $false
        }
    } catch {
        Write-Error "Failed to build $ImageName"
        Remove-Item $TempDir -Recurse -Force
        return $false
    }
    
    # Push the image
    Write-Status "Pushing $ImageName..."
    try {
        docker push $ImageName
        if ($LASTEXITCODE -eq 0) {
            Write-Success "Successfully pushed $ImageName"
        } else {
            Write-Error "Failed to push $ImageName"
            Remove-Item $TempDir -Recurse -Force
            return $false
        }
    } catch {
        Write-Error "Failed to push $ImageName"
        Remove-Item $TempDir -Recurse -Force
        return $false
    }
    
    # Clean up temporary directory
    Remove-Item $TempDir -Recurse -Force
    Write-Host ""
    return $true
}

# Main execution
Write-Status "Starting build and push process for CavGo System"
Write-Status "Docker Hub Repository: $DOCKER_USERNAME/$DOCKER_REPOSITORY"
Write-Host ""

# Check if Docker is running
try {
    docker info | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed"
    }
} catch {
    Write-Error "Docker is not running. Please start Docker and try again."
    exit 1
}

# Check if logged in to Docker Hub
try {
    $DockerInfo = docker info 2>$null
    if ($DockerInfo -notmatch "Username: $DOCKER_USERNAME") {
        Write-Warning "You may not be logged in to Docker Hub as $DOCKER_USERNAME"
        Write-Status "Attempting to login..."
        docker login
    }
} catch {
    Write-Warning "Could not verify Docker Hub login status"
    Write-Status "Attempting to login..."
    docker login
}

# Build and push PostgreSQL with init script
Write-Status "Building and pushing PostgreSQL with init script..."
# Build-AndPush-Postgres

# Build and push application services
Write-Status "Building and pushing application services..."

# Build and push each service
# Build-AndPush "eureka" "./Eurekacavgo"
# Build-AndPush "main" "./cavgomain"
# Build-AndPush "gateway" "./Cavgogateway"
# Build-AndPush "trips" "./cavgotrips"
Build-AndPush "booking" "./cavgoBooking"
# Build-AndPush "cavgomqt" "./cavgomqt"
# Build-AndPush "ussd" "./ussdService"

Write-Success "All images have been built and pushed successfully!"
Write-Status "You can now use the docker-compose-hub.yml file to deploy using the pushed images."

# List all pushed images
Write-Status "The following images have been pushed to Docker Hub:"
Write-Host "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:postgres"
Write-Host "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:eureka"
Write-Host "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:main"
Write-Host "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:gateway"
Write-Host "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:trips"
Write-Host "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:booking"
Write-Host "  - ${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:ussd"

# Optional: Clean up local images to save space
if (-not $SkipCleanup) {
    Write-Host ""
    $Response = Read-Host "Do you want to remove local images to save space? (y/N)"
    if ($Response -match "^[Yy]$") {
        Write-Status "Cleaning up local images..."
        
        $ImagesToRemove = @(
            "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:postgres",
            "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:eureka",
            "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:main",
            "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:gateway",
            "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:trips",
            "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:booking",
            "${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:ussd"
        )
        
        foreach ($Image in $ImagesToRemove) {
            try {
                docker rmi $Image 2>$null
            } catch {
                # Ignore errors if image doesn't exist
            }
        }
        
        Write-Success "Local images cleaned up!"
    }
}

Write-Host ""
Write-Success "Build and push process completed successfully!"
Write-Status "Repository URL: https://hub.docker.com/r/$DOCKER_USERNAME/$DOCKER_REPOSITORY"
