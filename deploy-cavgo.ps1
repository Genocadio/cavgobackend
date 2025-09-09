# Deploy CavGo System Script - PowerShell Version
# Uses regular DigitalOcean persistent folders instead of complex volume management

param(
    [switch]$CleanMode
)

# Set error action preference
$ErrorActionPreference = "Stop"

# Configuration
$DATA_DIR = "/opt/cavgo-data"
$DROPLET_IP = "143.198.110.227"
$REMOTE_DIR = "/opt/cavgo-system"
$LOCAL_COMPOSE_FILE = "docker-compose-hub.yml"

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

# Function to execute SSH command
function Invoke-SSHCommand {
    param([string]$Command)
    
    try {
        $result = ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$DROPLET_IP" $Command
        return $result
    } catch {
        Write-Error "SSH command failed: $Command"
        throw
    }
}

# Function to execute SSH command with sudo
function Invoke-SSHCommandSudo {
    param([string]$Command)
    
    try {
        $result = ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$DROPLET_IP" "echo '$SSH_PASSWORD' | sudo -S bash -c '$Command'"
        return $result
    } catch {
        Write-Error "SSH sudo command failed: $Command"
        throw
    }
}

# Function to copy files via SCP
function Copy-FileViaSCP {
    param(
        [string]$Source,
        [string]$Destination
    )
    
    try {
        scp -o StrictHostKeyChecking=no $Source $Destination
        if ($LASTEXITCODE -eq 0) {
            return $true
        } else {
            throw "SCP command failed"
        }
    } catch {
        Write-Error "SCP copy failed: $Source -> $Destination"
        throw
    }
}

# Function to setup data directories
function Setup-DataDirectories {
    Write-Status "📁 Setting up data directories..."

    # Create main data directory
    Invoke-SSHCommandSudo "mkdir -p $DATA_DIR"
    Invoke-SSHCommandSudo "chown -R $REMOTE_USER`:$REMOTE_USER $DATA_DIR"

    # Create subdirectories for each service
    Invoke-SSHCommandSudo "mkdir -p $DATA_DIR/postgres"
    Invoke-SSHCommandSudo "mkdir -p $DATA_DIR/rabbitmq"
    Invoke-SSHCommandSudo "mkdir -p $DATA_DIR/backups"
    Invoke-SSHCommandSudo "mkdir -p $DATA_DIR/portainer_logs"

    # Set proper ownership and permissions
    Invoke-SSHCommandSudo "chown -R 999:999 $DATA_DIR/postgres"
    Invoke-SSHCommandSudo "chown -R 999:999 $DATA_DIR/rabbitmq"
    Invoke-SSHCommandSudo "chown -R $REMOTE_USER`:$REMOTE_USER $DATA_DIR/backups"
    Invoke-SSHCommandSudo "chown -R 65532:65532 $DATA_DIR/portainer_logs"
    Invoke-SSHCommandSudo "chmod -R 755 $DATA_DIR/postgres"
    Invoke-SSHCommandSudo "chmod -R 755 $DATA_DIR/rabbitmq"
    Invoke-SSHCommandSudo "chmod -R 755 $DATA_DIR/backups"
    Invoke-SSHCommandSudo "chmod -R 755 $DATA_DIR/portainer_logs"

    Write-Success "✅ Data directories setup complete"
}

# Function to setup automated backups
function Setup-AutomatedBackups {
    Write-Status "🔄 Setting up automated database backups..."

    $BackupScript = @"
#!/bin/bash
# Automated backup script for CavGo PostgreSQL
BACKUP_DIR="$DATA_DIR/backups"
TIMESTAMP=`$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="`$BACKUP_DIR/cavgo_backup_`$TIMESTAMP.sql"

# Create backup
docker exec cavgo-postgres pg_dumpall -U postgres > "`$BACKUP_FILE"

# Compress backup
gzip "`$BACKUP_FILE"

# Keep only the last 7 daily backups
find "`$BACKUP_DIR" -name "cavgo_backup_*.sql.gz" -mtime +7 -delete

echo "`$(date): Backup completed: `${BACKUP_FILE}.gz"
"@

    # Create backup script on remote server
    $BackupScript | Invoke-SSHCommand "cat > /tmp/backup-cavgo.sh"

    Invoke-SSHCommandSudo "mv /tmp/backup-cavgo.sh /usr/local/bin/backup-cavgo.sh"
    Invoke-SSHCommandSudo "chmod +x /usr/local/bin/backup-cavgo.sh"
    Invoke-SSHCommandSudo "chown $REMOTE_USER`:$REMOTE_USER /usr/local/bin/backup-cavgo.sh"

    # Add cron job
    $CronJob = "0 2 * * * /usr/local/bin/backup-cavgo.sh >> /var/log/cavgo-backup.log 2>&1"
    Invoke-SSHCommand "echo '$CronJob' | crontab -"

    Write-Success "✅ Automated daily backups configured"
}

# Main execution
if ($CleanMode) {
    Write-Warning "Clean mode enabled: All containers, volumes, and persistent data will be deleted!"
    $Confirm = Read-Host "Are you sure you want to continue? (y/N)"
    if ($Confirm -notmatch "^[Yy]$") {
        Write-Host "Aborted."
        exit 1
    }
}

# Prompt for credentials
$REMOTE_USER = Read-Host "Enter SSH username"
$SSH_PASSWORD = Read-Host "Enter SSH password for $REMOTE_USER" -AsSecureString
$SSH_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($SSH_PASSWORD))

Write-Status "🚀 Starting CavGo deployment with simplified storage..."
Write-Status "🔐 SSH User: $REMOTE_USER"
Write-Status "🏠 Target IP: $DROPLET_IP"
Write-Status "💾 Data Directory: $DATA_DIR"

# Test SSH connection
Write-Status "🔍 Testing SSH connection..."
try {
    $TestResult = Invoke-SSHCommand "echo 'SSH connection successful'"
    if ($LASTEXITCODE -eq 0) {
        Write-Success "✅ SSH connection successful"
    } else {
        throw "SSH connection failed"
    }
} catch {
    Write-Error "❌ SSH connection failed"
    exit 1
}

# Setup data directories
Setup-DataDirectories

# Clean up existing deployment
Write-Status "🧹 Cleaning up existing deployment..."
try {
    $ExistingDir = Invoke-SSHCommand "test -d $REMOTE_DIR"
    if ($LASTEXITCODE -eq 0) {
        Invoke-SSHCommand "cd $REMOTE_DIR 2>/dev/null && (docker compose down || docker-compose down) 2>/dev/null || true"
        $BackupDir = "/opt/cavgo-system-backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        Invoke-SSHCommandSudo "mv $REMOTE_DIR $BackupDir"
        Write-Success "✅ Existing deployment backed up to $BackupDir"
    }
} catch {
    Write-Warning "Could not check for existing deployment directory"
}

# If clean mode, remove all related volumes, prune, and delete data dirs
if ($CleanMode) {
    Write-Status "🧹 Removing Docker volumes and persistent data (CLEAN MODE)..."
    Invoke-SSHCommand "docker volume rm portainer_data cavgo-system_postgres_data cavgo-system_portainer_data 2>/dev/null || true"
    Invoke-SSHCommand "docker system prune -af --volumes || true"
    Invoke-SSHCommand "docker volume prune -f || true"
    Invoke-SSHCommandSudo "rm -rf $DATA_DIR"
    Write-Success "✅ All Docker volumes and persistent data removed"
}

# Force remove any existing containers with conflicting names
Write-Status "🧹 Removing any existing containers with conflicting names..."
Invoke-SSHCommand "docker rm -f cavgo-postgres cavgo-main cavgo-gateway cavgo-trips cavgo-booking cavgo-maqtt rabbitmq portainer 2>/dev/null || true"
Write-Success "✅ Conflicting containers removed"

# Create directories
Invoke-SSHCommandSudo "mkdir -p $REMOTE_DIR && chown -R $REMOTE_USER`:$REMOTE_USER $REMOTE_DIR"

# Copy files
Write-Status "⬆️  Copying deployment files..."
Copy-FileViaSCP $LOCAL_COMPOSE_FILE "$REMOTE_USER@$DROPLET_IP`:$REMOTE_DIR/docker-compose.yml"
Copy-FileViaSCP "init-multiple-dbs.sh" "$REMOTE_USER@$DROPLET_IP`:$REMOTE_DIR/init-multiple-dbs.sh"
Write-Success "✅ Files copied successfully"

# Check Docker
Write-Status "🐳 Checking Docker..."
$DockerComposeCheck = Invoke-SSHCommand "docker compose version >/dev/null 2>&1"
if ($LASTEXITCODE -eq 0) {
    $COMPOSE_CMD = "docker compose"
} else {
    $DockerComposeCheck = Invoke-SSHCommand "docker-compose --version >/dev/null 2>&1"
    if ($LASTEXITCODE -eq 0) {
        $COMPOSE_CMD = "docker-compose"
    } else {
        Write-Error "❌ Docker Compose not found"
        exit 1
    }
}

# Deploy
Write-Status "📥 Pulling images..."
Invoke-SSHCommand "cd $REMOTE_DIR && $COMPOSE_CMD pull"

Write-Status "🚀 Starting services..."
Invoke-SSHCommand "cd $REMOTE_DIR && $COMPOSE_CMD up -d"

# Setup backups
Setup-AutomatedBackups

# Fix .erlang.cookie permissions for RabbitMQ
Invoke-SSHCommandSudo "if [ -f $DATA_DIR/rabbitmq/.erlang.cookie ]; then chown 999:999 $DATA_DIR/rabbitmq/.erlang.cookie && chmod 600 $DATA_DIR/rabbitmq/.erlang.cookie; fi"

Write-Status "⏳ Waiting for services..."
Start-Sleep -Seconds 15

# Check status
Write-Status "🔍 Service Status:"
Invoke-SSHCommand "cd $REMOTE_DIR && $COMPOSE_CMD ps"

Write-Status "💾 Storage Status:"
Invoke-SSHCommand "df -h $DATA_DIR"

Write-Host ""
Write-Success "🎉 CavGo System deployed successfully with persistent storage!"
Write-Host ""
Write-Status "🔗 Service URLs:"
Write-Host "  - Eureka: http://$DROPLET_IP`:8761"
Write-Host "  - Gateway: http://$DROPLET_IP`:8080"
Write-Host "  - Main Service: http://$DROPLET_IP`:6060"
Write-Host "  - Trips Service: http://$DROPLET_IP`:6080"
Write-Host "  - Booking Service: http://$DROPLET_IP`:6030"
Write-Host "  - RabbitMQ: http://$DROPLET_IP`:15672 (admin/admin)"
Write-Host ""
Write-Status "💾 Data Storage:"
Write-Host "  - Data Directory: $DATA_DIR"
Write-Host "  - Automated Backups: Daily at 2 AM"
Write-Host ""
Write-Status "💡 Management Commands:"
Write-Host "  Status: ssh $REMOTE_USER@$DROPLET_IP 'cd $REMOTE_DIR && $COMPOSE_CMD ps'"
Write-Host "  Logs: ssh $REMOTE_USER@$DROPLET_IP 'cd $REMOTE_DIR && $COMPOSE_CMD logs -f'"
Write-Host "  Backup: ssh $REMOTE_USER@$DROPLET_IP '/usr/local/bin/backup-cavgo.sh'"
