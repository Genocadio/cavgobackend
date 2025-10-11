#!/bin/bash

# Deploy CavGo System Script - Simplified Version
# Uses regular DigitalOcean persistent folders instead of complex volume management

set -e  # Exit on any error

# Parse -c flag for clean mode
CLEAN_MODE=false
while getopts "c" opt; do
  case $opt in
    c)
      CLEAN_MODE=true
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

# Configuration
DATA_DIR="/opt/cavgo-data"
DROPLET_IP="api.gocavgo.com"
REMOTE_DIR="/opt/cavgo-system"
LOCAL_COMPOSE_FILE="docker-compose-hub.yml"

# Authentication method detection
USE_SSH_KEY=false
SSH_PASSWORD=""

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
        ssh -o StrictHostKeyChecking=no -o PasswordAuthentication=no -o PubkeyAuthentication=yes "$REMOTE_USER@$DROPLET_IP" "sudo -n bash -c '$1' 2>/dev/null || sudo bash -c '$1'"
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
TOTAL_STEPS=12
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

# Step 7: Copy deployment files
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

# Step 8: Check Docker
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

# Step 9: Pull Docker images
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Pulling Docker images"
ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD pull"

# Step 10: Start services
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Starting services"
ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD up -d"

# Step 11: Setup automated backups
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Setting up automated backups"
setup_automated_backups

# Step 12: Fix permissions and wait for services
CURRENT_STEP=$((CURRENT_STEP + 1))
show_progress $CURRENT_STEP $TOTAL_STEPS "Finalizing deployment"
ssh_sudo_cmd "if [ -f $DATA_DIR/rabbitmq/.erlang.cookie ]; then chown 999:999 $DATA_DIR/rabbitmq/.erlang.cookie && chmod 600 $DATA_DIR/rabbitmq/.erlang.cookie; fi"

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
