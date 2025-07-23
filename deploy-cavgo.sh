#!/bin/bash

# Deploy CavGo System Script - Simplified Version
# Uses regular DigitalOcean persistent folders instead of complex volume management

set -e  # Exit on any error

# Configuration
DATA_DIR="/opt/cavgo-data"
DROPLET_IP="159.203.85.152"
REMOTE_DIR="/opt/cavgo-system"
LOCAL_COMPOSE_FILE="docker-compose-hub.yml"

# Prompt for credentials
echo -n "Enter SSH username: "
read REMOTE_USER

echo -n "Enter SSH password for $REMOTE_USER: "
read -s SSH_PASSWORD
echo

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

# Helper function for sshpass SSH command
function ssh_cmd() {
  sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$DROPLET_IP" "$1"
}

# Helper function for sshpass SSH command with sudo
function ssh_sudo_cmd() {
  sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$DROPLET_IP" "echo '$SSH_PASSWORD' | sudo -S bash -c '$1'"
}

# Helper function for sshpass SCP command
function scp_cmd() {
  sshpass -p "$SSH_PASSWORD" scp -o StrictHostKeyChecking=no "$@"
}

# Function to setup data directories
setup_data_directories() {
    print_status "📁 Setting up data directories..."

    # Create main data directory
    ssh_sudo_cmd "mkdir -p $DATA_DIR"
    ssh_sudo_cmd "chown -R $REMOTE_USER:$REMOTE_USER $DATA_DIR"

    # Create subdirectories for each service
    ssh_sudo_cmd "mkdir -p $DATA_DIR/postgres"
    ssh_sudo_cmd "mkdir -p $DATA_DIR/rabbitmq"
    ssh_sudo_cmd "mkdir -p $DATA_DIR/backups"
    ssh_sudo_cmd "mkdir -p $DATA_DIR/portainer_logs"

    # Set proper ownership and permissions
    ssh_sudo_cmd "chown -R 999:999 $DATA_DIR/postgres"
    ssh_sudo_cmd "chown -R 999:999 $DATA_DIR/rabbitmq"
    ssh_sudo_cmd "chown -R $REMOTE_USER:$REMOTE_USER $DATA_DIR/backups"
    ssh_sudo_cmd "chown -R 65532:65532 $DATA_DIR/portainer_logs"
    ssh_sudo_cmd "chmod -R 755 $DATA_DIR/postgres"
    ssh_sudo_cmd "chmod -R 755 $DATA_DIR/rabbitmq"
    ssh_sudo_cmd "chmod -R 755 $DATA_DIR/backups"
    ssh_sudo_cmd "chmod -R 755 $DATA_DIR/portainer_logs"

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

    ssh_sudo_cmd "mv /tmp/backup-cavgo.sh /usr/local/bin/backup-cavgo.sh"
    ssh_sudo_cmd "chmod +x /usr/local/bin/backup-cavgo.sh"
    ssh_sudo_cmd "chown $REMOTE_USER:$REMOTE_USER /usr/local/bin/backup-cavgo.sh"

    # Add cron job
    CRON_JOB="0 2 * * * /usr/local/bin/backup-cavgo.sh >> /var/log/cavgo-backup.log 2>&1"
    ssh_cmd "echo '$CRON_JOB' | crontab -"

    print_success "✅ Automated daily backups configured"
}

# Main execution
print_status "🚀 Starting CavGo deployment with simplified storage..."
print_status "🔐 SSH User: $REMOTE_USER"
print_status "🏠 Target IP: $DROPLET_IP"
print_status "💾 Data Directory: $DATA_DIR"

# Test SSH connection
print_status "🔍 Testing SSH connection..."
if ! ssh_cmd "echo 'SSH connection successful'"; then
    print_error "❌ SSH connection failed"
    exit 1
fi

# Setup data directories
setup_data_directories

# Clean up existing deployment
print_status "🧹 Cleaning up existing deployment..."
if ssh_sudo_cmd "test -d $REMOTE_DIR"; then
    ssh_cmd "cd $REMOTE_DIR 2>/dev/null && (docker compose down || docker-compose down) 2>/dev/null || true"
    BACKUP_DIR="/opt/cavgo-system-backup-$(date +%Y%m%d-%H%M%S)"
    ssh_sudo_cmd "mv $REMOTE_DIR $BACKUP_DIR"
    print_success "✅ Existing deployment backed up to $BACKUP_DIR"
fi

# Create directories
ssh_sudo_cmd "mkdir -p $REMOTE_DIR && chown -R $REMOTE_USER:$REMOTE_USER $REMOTE_DIR"

# Copy files
print_status "⬆️  Copying deployment files..."
scp_cmd "$LOCAL_COMPOSE_FILE" "$REMOTE_USER@$DROPLET_IP:$REMOTE_DIR/docker-compose.yml"
scp_cmd "init-multiple-dbs.sh" "$REMOTE_USER@$DROPLET_IP:$REMOTE_DIR/init-multiple-dbs.sh"
print_success "✅ Files copied successfully"

# Check Docker
print_status "🐳 Checking Docker..."
if ssh_cmd "docker compose version >/dev/null 2>&1"; then
    COMPOSE_CMD="docker compose"
elif ssh_cmd "docker-compose --version >/dev/null 2>&1"; then
    COMPOSE_CMD="docker-compose"
else
    print_error "❌ Docker Compose not found"
    exit 1
fi

# Deploy
print_status "📥 Pulling images..."
ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD pull"

print_status "🚀 Starting services..."
ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD up -d"

# Setup backups
setup_automated_backups

# Fix .erlang.cookie permissions for RabbitMQ
ssh_sudo_cmd "if [ -f $DATA_DIR/rabbitmq/.erlang.cookie ]; then chown 999:999 $DATA_DIR/rabbitmq/.erlang.cookie && chmod 600 $DATA_DIR/rabbitmq/.erlang.cookie; fi"

print_status "⏳ Waiting for services..."
sleep 15

# Check status
print_status "🔍 Service Status:"
ssh_cmd "cd $REMOTE_DIR && $COMPOSE_CMD ps"

print_status "💾 Storage Status:"
ssh_cmd "df -h $DATA_DIR"

echo ""
print_success "🎉 CavGo System deployed successfully with persistent storage!"
echo ""
print_status "🔗 Service URLs:"
echo "  - Eureka: http://$DROPLET_IP:8761"
echo "  - Gateway: http://$DROPLET_IP:8080"
echo "  - Main Service: http://$DROPLET_IP:6060"
echo "  - Trips Service: http://$DROPLET_IP:6080"
echo "  - Booking Service: http://$DROPLET_IP:6030"
echo "  - RabbitMQ: http://$DROPLET_IP:15672 (admin/admin)"
echo ""
print_status "💾 Data Storage:"
echo "  - Data Directory: $DATA_DIR"
echo "  - Automated Backups: Daily at 2 AM"
echo ""
print_status "💡 Management Commands:"
echo "  Status: ssh $REMOTE_USER@$DROPLET_IP 'cd $REMOTE_DIR && $COMPOSE_CMD ps'"
echo "  Logs: ssh $REMOTE_USER@$DROPLET_IP 'cd $REMOTE_DIR && $COMPOSE_CMD logs -f'"
echo "  Backup: ssh $REMOTE_USER@$DROPLET_IP '/usr/local/bin/backup-cavgo.sh'"
