#!/bin/bash

# Deploy CavGo System Script - Simplified Version
# Uses regular DigitalOcean persistent folders instead of complex volume management
#
# Usage:
#   ./deploy-cavgo.sh              # Auto-detect and deploy only updated services
#   ./deploy-cavgo.sh -a           # Deploy all services
#   ./deploy-cavgo.sh -c           # Interactive service selection (shows numbered list)
#   ./deploy-cavgo.sh --clean      # Clean mode (remove all data)

set -e  # Exit on any error

# Parse command line arguments
DEPLOY_ALL=false
SELECT_SERVICES=false
CLEAN_MODE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -a|--all)
            DEPLOY_ALL=true
            shift
            ;;
        -c|--choose)
            SELECT_SERVICES=true
            shift
            ;;
        --clean)
            CLEAN_MODE=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [-a|--all] [-c|--choose] [--clean]"
            exit 1
            ;;
    esac
done

if $CLEAN_MODE; then
  echo -e "\033[0;31m[WARNING]\033[0m Clean mode enabled: All containers, volumes, and persistent data will be deleted!"
  read -p "Are you sure you want to continue? (y/N): " confirm
  if [[ ! $confirm =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
  fi
fi

# Define all available services
declare -a ALL_SERVICES=(
    "portainer"
    "rabbitmq"
    "postgres"
    "eurekacavgo"
    "cavgomain"
    "cavgogateway"
    "ridehail"
    "cavgotrips"
    "cavgobooking"
    "cavgomqt"
    "cavgoussd"
)

# Function to get service display name (compatible with bash 3.2+)
get_service_name() {
    local service=$1
    case "$service" in
        portainer) echo "Portainer (Container Management)" ;;
        rabbitmq) echo "RabbitMQ (Message Queue)" ;;
        postgres) echo "PostgreSQL (Database)" ;;
        eurekacavgo) echo "Eureka (Service Discovery)" ;;
        cavgomain) echo "CavGo Main (Java Service)" ;;
        cavgogateway) echo "CavGo Gateway (API Gateway)" ;;
        ridehail) echo "Ridehail (Java Service)" ;;
        cavgotrips) echo "CavGo Trips (Go Service)" ;;
        cavgobooking) echo "CavGo Booking (Go Service)" ;;
        cavgomqt) echo "CavGo MQTT (Java Service)" ;;
        cavgoussd) echo "CavGo USSD (Java Service)" ;;
        *) echo "$service" ;;
    esac
}

# Configuration
DATA_DIR="/opt/cavgo-data"
DROPLET_IP="api.gocavgo.com"
REMOTE_DIR="/opt/cavgo-system"
LOCAL_COMPOSE_FILE="docker-compose-hub.yml"

# Authentication method detection
USE_SSH_KEY=false
SSH_PASSWORD=""
SUDO_PASSWORD=""

# Prompt for credentials
echo -n "Enter SSH username: "
read REMOTE_USER

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to clear progress line
clear_progress() {
    printf "\r\033[K"
}

# Function to print colored output
print_status() {
    clear_progress
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    clear_progress
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    clear_progress
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    clear_progress
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to show progress
show_progress() {
    local current=$1
    local total=$2
    local desc=$3
    local percent=$((current * 100 / total))
    local filled=$((percent / 2))
    local empty=$((50 - filled))
    
    # Clear the line and show progress
    printf "\r\033[K${BLUE}[%d/%d]${NC} %s [${GREEN}%s${NC}${BLUE}%s${NC}] %d%%" \
        "$current" "$total" "$desc" \
        "$(printf "%*s" $filled | tr ' ' '=')" \
        "$(printf "%*s" $empty | tr ' ' '-')" \
        "$percent"
    
    # Flush output to ensure it's displayed
    printf ""
    
    if [ $current -eq $total ]; then
        echo
    fi
}

# Function to show spinner for long operations
show_spinner() {
    local pid=$1
    local desc=$2
    local delay=0.1
    local spinstr='|/-\'
    
    while [ "$(ps a | awk '{print $1}' | grep $pid)" ]; do
        local temp=${spinstr#?}
        printf "\r${BLUE}[WORKING]${NC} %s %c" "$desc" "$spinstr"
        local spinstr=$temp${spinstr%"$temp"}
        sleep $delay
    done
    printf "\r${GREEN}[DONE]${NC} %s    \n" "$desc"
}

# Function to detect authentication method
detect_auth_method() {
    print_status "🔍 Detecting SSH authentication method..."
    
    # Test SSH key authentication first
    if ssh -o StrictHostKeyChecking=no -o PasswordAuthentication=no -o PubkeyAuthentication=yes -o ConnectTimeout=10 "$REMOTE_USER@$DROPLET_IP" "echo 'SSH key authentication successful'" 2>/dev/null; then
        USE_SSH_KEY=true
        print_success "✅ SSH key authentication detected and working"
        return 0
    fi
    
    # If SSH key fails, prompt for password
    print_warning "⚠️  SSH key authentication failed, falling back to password authentication"
    echo -n "Enter SSH password for $REMOTE_USER: "
    read -s SSH_PASSWORD
    echo
    
    # Test password authentication
    if ssh -o StrictHostKeyChecking=no -o PasswordAuthentication=yes -o PubkeyAuthentication=no -o ConnectTimeout=10 "$REMOTE_USER@$DROPLET_IP" "echo 'SSH password authentication successful'" 2>/dev/null; then
        USE_SSH_KEY=false
        print_success "✅ SSH password authentication working"
        return 0
    else
        print_error "❌ Both SSH key and password authentication failed"
        return 1
    fi
}

# SSH command using detected authentication method
function ssh_cmd() {
    if $USE_SSH_KEY; then
        ssh -o StrictHostKeyChecking=no -o PasswordAuthentication=no -o PubkeyAuthentication=yes "$REMOTE_USER@$DROPLET_IP" "$1"
    else
        sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no -o PasswordAuthentication=yes -o PubkeyAuthentication=no "$REMOTE_USER@$DROPLET_IP" "$1"
    fi
}

# SSH with sudo, using detected authentication method
function ssh_sudo_cmd() {
    if $USE_SSH_KEY; then
        # Try passwordless sudo first
        if ssh -o StrictHostKeyChecking=no -o PasswordAuthentication=no -o PubkeyAuthentication=yes "$REMOTE_USER@$DROPLET_IP" "sudo -n bash -c '$1' 2>/dev/null"; then
            return 0
        fi
        
        # If passwordless sudo fails, prompt for sudo password
        if [ -z "$SUDO_PASSWORD" ]; then
            echo -n "Enter sudo password for $REMOTE_USER@$DROPLET_IP: "
            read -s SUDO_PASSWORD
            echo
        fi
        
        # Use sudo with password
        ssh -o StrictHostKeyChecking=no -o PasswordAuthentication=no -o PubkeyAuthentication=yes "$REMOTE_USER@$DROPLET_IP" "echo '$SUDO_PASSWORD' | sudo -S bash -c '$1'"
    else
        # Use sshpass with sudo -S and redirect stderr to suppress password prompts
        sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no -o PasswordAuthentication=yes -o PubkeyAuthentication=no "$REMOTE_USER@$DROPLET_IP" "echo '$SSH_PASSWORD' | sudo -S bash -c '$1'" 2>/dev/null
    fi
}

# SCP using detected authentication method
function scp_cmd() {
    if $USE_SSH_KEY; then
        scp -o StrictHostKeyChecking=no -o PasswordAuthentication=no -o PubkeyAuthentication=yes "$@"
    else
        sshpass -p "$SSH_PASSWORD" scp -o StrictHostKeyChecking=no -o PasswordAuthentication=yes -o PubkeyAuthentication=no "$@"
    fi
}

# Function to setup data directories
setup_data_directories() {
    print_status "📁 Setting up data directories..."

    # Batch all directory creation and permission commands into a single sudo session
    ssh_sudo_cmd "
        # Create main data directory and subdirectories
        mkdir -p $DATA_DIR/{postgres,rabbitmq,backups,portainer_logs}
        
        # Set ownership for main directory
        chown -R $REMOTE_USER:$REMOTE_USER $DATA_DIR
        
        # Set specific ownership for service directories
        chown -R 999:999 $DATA_DIR/postgres
        chown -R 999:999 $DATA_DIR/rabbitmq
        chown -R $REMOTE_USER:$REMOTE_USER $DATA_DIR/backups
        chown -R 65532:65532 $DATA_DIR/portainer_logs
        
        # Set permissions
        chmod -R 755 $DATA_DIR/postgres
        chmod -R 755 $DATA_DIR/rabbitmq
        chmod -R 755 $DATA_DIR/backups
        chmod -R 755 $DATA_DIR/portainer_logs
    "

    print_success "✅ Data directories setup complete"
}

# Function to setup automated backups
setup_automated_backups() {
    print_status "🔄 Setting up automated database backups..."

    BACKUP_SCRIPT="#!/bin/bash
# Automated backup script for CavGo PostgreSQL
BACKUP_DIR=\"$DATA_DIR/backups\"
TIMESTAMP=\$(date +%Y%m%d_%H%M%S)
BACKUP_FILE=\"\$BACKUP_DIR/cavgo_backup_\$TIMESTAMP.sql\"

# Create backup
docker exec cavgo-postgres pg_dumpall -U postgres > \"\$BACKUP_FILE\"

# Compress backup
gzip \"\$BACKUP_FILE\"

# Keep only the last 7 daily backups
find \"\$BACKUP_DIR\" -name \"cavgo_backup_*.sql.gz\" -mtime +7 -delete

echo \"\$(date): Backup completed: \${BACKUP_FILE}.gz\"
"

    ssh_cmd "cat > /tmp/backup-cavgo.sh << 'EOF'
$BACKUP_SCRIPT
EOF"

    # Batch backup script installation commands
    ssh_sudo_cmd "
        mv /tmp/backup-cavgo.sh /usr/local/bin/backup-cavgo.sh
        chmod +x /usr/local/bin/backup-cavgo.sh
        chown $REMOTE_USER:$REMOTE_USER /usr/local/bin/backup-cavgo.sh
    "

    # Add cron job
    CRON_JOB="0 2 * * * /usr/local/bin/backup-cavgo.sh >> /var/log/cavgo-backup.log 2>&1"
    ssh_cmd "echo '$CRON_JOB' | crontab -"

    print_success "✅ Automated daily backups configured"
}

# Function to detect and validate Firebase credentials file on host system
# Reloads environment to ensure we detect the variable even if set in profile files
detect_firebase_credentials() {
    local credentials_path=""
    local env_var=""
    
    # First, check current shell's environment variable
    if [ -n "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
        credentials_path="$GOOGLE_APPLICATION_CREDENTIALS"
    else
        # Try to reload environment from common profile files to detect the variable
        # This ensures we catch it even if it's set in .bashrc, .zshrc, .profile, etc.
        # Check multiple sources in order of preference
        if [ -f "$HOME/.bashrc" ]; then
            env_var=$(bash -c "source $HOME/.bashrc 2>/dev/null; echo \$GOOGLE_APPLICATION_CREDENTIALS" 2>/dev/null)
            if [ -n "$env_var" ]; then
                credentials_path="$env_var"
            fi
        fi
        
        if [ -z "$credentials_path" ] && [ -f "$HOME/.zshrc" ]; then
            env_var=$(zsh -c "source $HOME/.zshrc 2>/dev/null; echo \$GOOGLE_APPLICATION_CREDENTIALS" 2>/dev/null)
            if [ -n "$env_var" ]; then
                credentials_path="$env_var"
            fi
        fi
        
        if [ -z "$credentials_path" ] && [ -f "$HOME/.profile" ]; then
            env_var=$(bash -c "source $HOME/.profile 2>/dev/null; echo \$GOOGLE_APPLICATION_CREDENTIALS" 2>/dev/null)
            if [ -n "$env_var" ]; then
                credentials_path="$env_var"
            fi
        fi
        
        if [ -z "$credentials_path" ] && [ -f "$HOME/.bash_profile" ]; then
            env_var=$(bash -c "source $HOME/.bash_profile 2>/dev/null; echo \$GOOGLE_APPLICATION_CREDENTIALS" 2>/dev/null)
            if [ -n "$env_var" ]; then
                credentials_path="$env_var"
            fi
        fi
    fi
    
    # If still no path found, return error
    if [ -z "$credentials_path" ]; then
        return 1
    fi
    
    # Check if path points to a directory instead of a file
    if [ -d "$credentials_path" ]; then
        print_warning "⚠️  GOOGLE_APPLICATION_CREDENTIALS points to a directory, not a file: $credentials_path"
        return 1
    fi
    
    # Verify the file exists
    if [ ! -f "$credentials_path" ]; then
        print_warning "⚠️  GOOGLE_APPLICATION_CREDENTIALS is set but file does not exist: $credentials_path"
        return 1
    fi
    
    # Verify the file is readable
    if [ ! -r "$credentials_path" ]; then
        print_warning "⚠️  Firebase credentials file exists but is not readable: $credentials_path"
        return 1
    fi
    
    # Verify it's a valid JSON file (basic check)
    if command -v jq &> /dev/null; then
        if ! jq empty "$credentials_path" 2>/dev/null; then
            print_warning "⚠️  Firebase credentials file found but is not valid JSON: $credentials_path"
            return 1
        fi
    fi
    
    # File is valid
    echo "$credentials_path"
    return 0
}

# Function to check for Firebase credentials on target system
check_target_firebase_credentials() {
    local target_path="/opt/cavgo-data/firebase-credentials.json"
    
    if ssh_cmd "test -f $target_path"; then
        return 0
    else
        return 1
    fi
}

# Function to get image name from docker-compose for a service
get_service_image() {
    local service_name=$1
    local image_name=""
    
    # Extract image from docker-compose file
    image_name=$(grep -A 20 "^  ${service_name}:" "$LOCAL_COMPOSE_FILE" 2>/dev/null | grep -E "^\s+image:" | head -1 | sed 's/.*image:\s*//' | tr -d '"' | tr -d "'" || echo "")
    
    echo "$image_name"
}

# Function to check if image tag has been updated (compare remote vs deployed)
check_image_updated() {
    local service_name=$1
    local image_name=$(get_service_image "$service_name")
    
    if [ -z "$image_name" ]; then
        return 1  # No image found, skip
    fi
    
    # Skip services without genoyves/cavgo-system prefix (like portainer, rabbitmq, postgres)
    # These use fixed tags like :latest or :15-alpine, so always check them
    if [[ ! "$image_name" =~ ^genoyves/cavgo-system: ]]; then
        return 0  # Always check infrastructure services
    fi
    
    print_status "   Checking $service_name ($image_name)..."
    
    # Get currently deployed image digest on remote
    local deployed_digest=""
    local container_name=""
    
    # Map service name to container name
    case "$service_name" in
        eurekacavgo) container_name="cavgo-eureka" ;;
        cavgomain) container_name="cavgo-main" ;;
        cavgogateway) container_name="cavgo-gateway" ;;
        ridehail) container_name="cavgo-ridehail" ;;
        cavgotrips) container_name="cavgo-trips" ;;
        cavgobooking) container_name="cavgo-booking" ;;
        cavgomqt) container_name="cavgo-maqtt" ;;
        cavgoussd) container_name="cavgo-ussd" ;;
        *) container_name="" ;;
    esac
    
    # Get deployed image digest if container exists
    if [ -n "$container_name" ]; then
        deployed_digest=$(ssh_cmd "docker inspect $container_name --format '{{.Image}}' 2>/dev/null" | head -1 || echo "")
        if [ -n "$deployed_digest" ]; then
            deployed_digest=$(ssh_cmd "docker image inspect $deployed_digest --format '{{index .RepoDigests 0}}' 2>/dev/null" | head -1 || echo "")
        fi
    fi
    
    # Get remote image digest from registry
    local remote_digest=""
    # Try to pull image manifest (this will update the image if changed)
    if ssh_cmd "docker pull $image_name --quiet >/dev/null 2>&1"; then
        remote_digest=$(ssh_cmd "docker image inspect $image_name --format '{{index .RepoDigests 0}}' 2>/dev/null" | head -1 || echo "")
    fi
    
    # If we can't determine, assume updated (safer to deploy)
    if [ -z "$remote_digest" ]; then
        print_warning "   Could not determine remote digest, will deploy $service_name"
        return 0
    fi
    
    # Compare digests
    if [ "$deployed_digest" != "$remote_digest" ] || [ -z "$deployed_digest" ]; then
        print_success "   ✅ $service_name has updates"
        return 0
    else
        print_status "   ⏭️  $service_name is up to date"
        return 1
    fi
}

# Function to detect updated services automatically
detect_updated_services() {
    local updated_services=()
    
    print_status "🔍 Checking for updated services..."
    
    for service in "${ALL_SERVICES[@]}"; do
        if check_image_updated "$service"; then
            updated_services+=("$service")
        fi
    done
    
    # Always include infrastructure services if any service is updated
    if [ ${#updated_services[@]} -gt 0 ]; then
        # Ensure postgres, rabbitmq, eurekacavgo are included if not already
        for infra in "postgres" "rabbitmq" "eurekacavgo"; do
            if [[ ! " ${updated_services[@]} " =~ " ${infra} " ]]; then
                updated_services+=("$infra")
            fi
        done
    fi
    
    printf '%s\n' "${updated_services[@]}"
}

# Function for interactive service selection
select_services_interactive() {
    local selected_services=()
    local selection=""
    
    echo ""
    print_status "📋 Available Services:"
    echo ""
    
    local idx=1
    for service in "${ALL_SERVICES[@]}"; do
        printf "  %2d. %s\n" "$idx" "$(get_service_name "$service")"
        idx=$((idx + 1))
    done
    
    echo ""
    echo -n "Enter service numbers (comma-separated, e.g., 2,3,4) or 'all' for all services: "
    read selection
    
    if [ "$selection" = "all" ]; then
        printf '%s\n' "${ALL_SERVICES[@]}"
        return 0
    fi
    
    # Parse comma-separated numbers
    IFS=',' read -ra numbers <<< "$selection"
    for num in "${numbers[@]}"; do
        num=$(echo "$num" | tr -d ' ')  # Remove spaces
        if [[ "$num" =~ ^[0-9]+$ ]] && [ "$num" -ge 1 ] && [ "$num" -le ${#ALL_SERVICES[@]} ]; then
            local service_idx=$((num - 1))
            selected_services+=("${ALL_SERVICES[$service_idx]}")
        else
            print_error "Invalid selection: $num"
            return 1
        fi
    done
    
    if [ ${#selected_services[@]} -eq 0 ]; then
        print_error "No valid services selected"
        return 1
    fi
    
    printf '%s\n' "${selected_services[@]}"
    return 0
}

# Function to handle Firebase credentials deployment
handle_firebase_credentials() {
    local host_credentials_path=""
    local target_credentials_path="/opt/cavgo-data/firebase-credentials.json"
    local target_has_credentials=false
    
    print_status "🔐 Checking Firebase credentials..."
    
    # Check host system first (priority)
    if host_credentials_path=$(detect_firebase_credentials); then
        print_success "✅ Firebase credentials found on host: $host_credentials_path"
        
        # Always copy from host to target (overwrite if exists)
        # This ensures target has the correct credentials matching docker-compose configuration
        print_status "📤 Copying Firebase credentials to target system..."
        if scp_cmd "$host_credentials_path" "$REMOTE_USER@$DROPLET_IP:/tmp/firebase-credentials.json"; then
            ssh_sudo_cmd "
                mkdir -p $DATA_DIR
                mv -f /tmp/firebase-credentials.json $target_credentials_path
                # Set permissions to be readable by everyone in the container (world-readable)
                chmod 444 $target_credentials_path
                # Set ownership to spring user (UID 1001) but permissions allow all to read
                chown 1001:1001 $target_credentials_path
            "
            print_success "✅ Firebase credentials copied to target system at $target_credentials_path"
            print_status "   File permissions set for container user (spring:spring, UID 1001)"
            print_status "   File will persist across container restarts"
            return 0
        else
            print_error "❌ Failed to copy Firebase credentials to target system"
            return 1
        fi
    fi
    
    # Host doesn't have credentials - check target
    print_warning "⚠️  Firebase credentials not found on host system"
    
    # Check if target has credentials
    if check_target_firebase_credentials; then
        print_success "✅ Firebase credentials found on target system at $target_credentials_path"
        print_status "   Using existing credentials (will persist across restarts)"
        return 0
    fi
    
    # Neither host nor target has credentials - this is an error
    print_error "❌ Firebase credentials not found on host or target system"
    print_error ""
    print_error "   To fix this, choose one of the following:"
    print_error "   1. Set GOOGLE_APPLICATION_CREDENTIALS environment variable on host:"
    print_error "      export GOOGLE_APPLICATION_CREDENTIALS=/path/to/firebase-credentials.json"
    print_error "      (You may need to add this to your ~/.bashrc, ~/.zshrc, or ~/.profile)"
    print_error ""
    print_error "   2. Manually place credentials file on target system at:"
    print_error "      $target_credentials_path"
    print_error ""
    print_error "   The file must be a valid Firebase service account JSON file."
    print_error "   The docker-compose configuration expects it at: $target_credentials_path"
    return 1
}

# Main execution
echo ""
echo "╔══════════════════════════════════════════════════════════════════════════════╗"
echo "║                          🚀 CavGo Deployment System 🚀                      ║"
echo "║                        Enhanced with Smart Authentication                   ║"
echo "╚══════════════════════════════════════════════════════════════════════════════╝"
echo ""

print_status "🔐 SSH User: $REMOTE_USER"
print_status "🏠 Target IP: $DROPLET_IP"
print_status "💾 Data Directory: $DATA_DIR"
echo ""

# Define deployment steps
TOTAL_STEPS=13
CURRENT_STEP=0

# Step 1: Detect and test authentication method
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Detecting authentication method"
if ! detect_auth_method; then
    print_error "❌ Authentication failed"
    exit 1
fi

# Check if sshpass is available for password authentication
if ! $USE_SSH_KEY && ! command -v sshpass &> /dev/null; then
    print_error "❌ sshpass is required for password authentication but not installed"
    print_status "💡 Install sshpass:"
    print_status "   - Ubuntu/Debian: sudo apt-get install sshpass"
    print_status "   - macOS: brew install sshpass"
    print_status "   - Or use SSH key authentication instead"
    exit 1
fi

# Step 2: Setup data directories
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Setting up data directories"
setup_data_directories

# Step 3: Clean up existing deployment
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Cleaning up existing deployment"
if ssh_sudo_cmd "test -d $REMOTE_DIR"; then
    ssh_cmd "cd $REMOTE_DIR 2>/dev/null && (docker compose down || docker-compose down) 2>/dev/null || true"
    BACKUP_DIR="/opt/cavgo-system-backup-$(date +%Y%m%d-%H%M%S)"
    ssh_sudo_cmd "mv $REMOTE_DIR $BACKUP_DIR"
    print_success "✅ Existing deployment backed up to $BACKUP_DIR"
fi

# Step 4: Clean mode operations
if $CLEAN_MODE; then
  CURRENT_STEP=$((CURRENT_STEP + 1))
  show_progress $CURRENT_STEP $TOTAL_STEPS "Removing Docker volumes and data (CLEAN MODE)"
  ssh_cmd "docker volume rm portainer_data cavgo-system_postgres_data cavgo-system_portainer_data 2>/dev/null || true"
  ssh_cmd "docker system prune -af --volumes || true"
  ssh_cmd "docker volume prune -f || true"
  ssh_sudo_cmd "rm -rf $DATA_DIR"
  print_success "✅ All Docker volumes and persistent data removed"
fi

# Step 5: Remove conflicting containers
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Removing conflicting containers"
ssh_cmd "docker rm -f cavgo-postgres cavgo-main cavgo-gateway cavgo-trips cavgo-booking cavgo-maqtt rabbitmq portainer 2>/dev/null || true"

# Step 6: Create deployment directories
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Creating deployment directories"
ssh_sudo_cmd "mkdir -p $REMOTE_DIR && chown -R $REMOTE_USER:$REMOTE_USER $REMOTE_DIR"

# Step 7: Handle Firebase credentials
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Handling Firebase credentials"
if ! handle_firebase_credentials; then
    print_error "❌ Firebase credentials deployment failed"
    exit 1
fi

# Step 8: Copy deployment files
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Copying deployment files"
if ! scp_cmd "$LOCAL_COMPOSE_FILE" "$REMOTE_USER@$DROPLET_IP:$REMOTE_DIR/docker-compose.yml"; then
    print_error "❌ Failed to copy docker-compose file"
    exit 1
fi
if ! scp_cmd "init-multiple-dbs.sh" "$REMOTE_USER@$DROPLET_IP:$REMOTE_DIR/init-multiple-dbs.sh"; then
    print_error "❌ Failed to copy init script"
    exit 1
fi

# Step 9: Check Docker
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Checking Docker installation"
if ssh_cmd "docker compose version >/dev/null 2>&1"; then
    COMPOSE_CMD="docker compose"
elif ssh_cmd "docker-compose --version >/dev/null 2>&1"; then
    COMPOSE_CMD="docker-compose"
else
    print_error "❌ Docker Compose not found"
    exit 1
fi

# Determine which services to deploy
SERVICES_TO_DEPLOY=()

if $DEPLOY_ALL; then
    print_status "📦 Deploying ALL services"
    SERVICES_TO_DEPLOY=("${ALL_SERVICES[@]}")
elif $SELECT_SERVICES; then
    print_status "📦 Interactive service selection"
    if ! SERVICES_TO_DEPLOY=($(select_services_interactive)); then
        print_error "❌ Service selection failed"
        exit 1
    fi
    print_success "✅ Selected ${#SERVICES_TO_DEPLOY[@]} service(s): ${SERVICES_TO_DEPLOY[*]}"
else
    print_status "📦 Auto-detecting updated services"
    if ! SERVICES_TO_DEPLOY=($(detect_updated_services)); then
        print_warning "⚠️  No updated services detected"
        SERVICES_TO_DEPLOY=()
    fi
    
    if [ ${#SERVICES_TO_DEPLOY[@]} -eq 0 ]; then
        print_status "✅ All services are up to date. Nothing to deploy."
        print_status "   Use -a to deploy all services or -s to select specific services"
        exit 0
    else
        print_success "✅ Found ${#SERVICES_TO_DEPLOY[@]} service(s) to update: ${SERVICES_TO_DEPLOY[*]}"
    fi
fi

# Step 10: Pull Docker images
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Pulling Docker images"
if [ ${#SERVICES_TO_DEPLOY[@]} -gt 0 ]; then
    # Pull only selected services
    for service in "${SERVICES_TO_DEPLOY[@]}"; do
        ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD pull $service" || print_warning "⚠️  Failed to pull $service"
    done
else
    ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD pull"
fi

# Step 11: Start services
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Starting services"
if [ ${#SERVICES_TO_DEPLOY[@]} -gt 0 ]; then
    # Start only selected services (and their dependencies)
    ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD up -d ${SERVICES_TO_DEPLOY[*]}"
else
    ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD up -d"
fi

# Step 12: Setup automated backups
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Setting up automated backups"
setup_automated_backups

# Step 13: Fix permissions and wait for services
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Finalizing deployment"
ssh_sudo_cmd "
    if [ -f $DATA_DIR/rabbitmq/.erlang.cookie ]; then 
        chown 999:999 $DATA_DIR/rabbitmq/.erlang.cookie && chmod 600 $DATA_DIR/rabbitmq/.erlang.cookie
    fi
    # Fix Firebase credentials permissions for container access (readable by everyone)
    if [ -f $DATA_DIR/firebase-credentials.json ]; then
        chmod 444 $DATA_DIR/firebase-credentials.json
        chown 1001:1001 $DATA_DIR/firebase-credentials.json
    fi
"

print_status "⏳ Waiting for services to initialize..."
sleep 15

# Final status check
print_status "🔍 Service Status:"
ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD ps"

print_status "💾 Storage Status:"
ssh_cmd "df -h $DATA_DIR"

echo ""
echo "╔══════════════════════════════════════════════════════════════════════════════╗"
echo "║                        🎉 DEPLOYMENT COMPLETED SUCCESSFULLY! 🎉             ║"
echo "╚══════════════════════════════════════════════════════════════════════════════╝"
echo ""

print_success "🚀 CavGo System deployed successfully with persistent storage!"
echo ""

print_status "🔗 Service URLs:"
echo "  📡 Eureka:     http://$DROPLET_IP:8761"
echo "  🌐 Gateway:    http://$DROPLET_IP:8080"
echo "  🏢 Main:       http://$DROPLET_IP:6060"
echo "  🚗 Trips:      http://$DROPLET_IP:6080"
echo "  📅 Booking:    http://$DROPLET_IP:6030"
echo "  🐰 RabbitMQ:   http://$DROPLET_IP:15672 (admin/admin)"
echo ""

print_status "💾 Data Storage:"
echo "  📁 Data Directory: $DATA_DIR"
echo "  🔄 Automated Backups: Daily at 2 AM"
echo ""

print_status "💡 Management Commands:"
if $USE_SSH_KEY; then
    echo "  📊 Status: ssh $REMOTE_USER@$DROPLET_IP 'cd $REMOTE_DIR && $COMPOSE_CMD ps'"
    echo "  📋 Logs:   ssh $REMOTE_USER@$DROPLET_IP 'cd $REMOTE_DIR && $COMPOSE_CMD logs -f'"
    echo "  💾 Backup: ssh $REMOTE_USER@$DROPLET_IP '/usr/local/bin/backup-cavgo.sh'"
else
    echo "  📊 Status: sshpass -p 'PASSWORD' ssh $REMOTE_USER@$DROPLET_IP 'cd $REMOTE_DIR && $COMPOSE_CMD ps'"
    echo "  📋 Logs:   sshpass -p 'PASSWORD' ssh $REMOTE_USER@$DROPLET_IP 'cd $REMOTE_DIR && $COMPOSE_CMD logs -f'"
    echo "  💾 Backup: sshpass -p 'PASSWORD' ssh $REMOTE_USER@$DROPLET_IP '/usr/local/bin/backup-cavgo.sh'"
fi

echo ""
echo "╔══════════════════════════════════════════════════════════════════════════════╗"
echo "║                    🎯 Deployment completed in $(date +%H:%M:%S) 🎯                    ║"
echo "╚══════════════════════════════════════════════════════════════════════════════╝"
echo ""
