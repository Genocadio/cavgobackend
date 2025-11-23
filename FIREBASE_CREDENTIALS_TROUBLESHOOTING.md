# Firebase Credentials Troubleshooting Guide

## Understanding the Error

### Error: `FileNotFoundException: /opt/cavgo-data/firebase-credentials.json (Permission denied)`

**What it means:**
- The file exists at `/opt/cavgo-data/firebase-credentials.json` on the host system
- The Docker volume mount is working correctly
- **BUT** the container process cannot read the file due to permission issues

**Root Cause:**
The `cavgomqt` container runs as user `spring` (UID 1001, GID 1001) for security reasons. If the credentials file is owned by a different user or has restrictive permissions, the container cannot read it.

## How to Check if Container Has the File

### 1. Check if file exists in container
```bash
# SSH into the target server
ssh user@api.gocavgo.com

# Check if file exists in the container
docker exec cavgo-maqtt ls -la /opt/cavgo-data/firebase-credentials.json
```

### 2. Check file permissions from host
```bash
# On target server, check file permissions
ls -la /opt/cavgo-data/firebase-credentials.json
```

Expected output should show:
```
-rw-r--r-- 1 1001 1001 [size] [date] /opt/cavgo-data/firebase-credentials.json
```

### 3. Check if container user can read it
```bash
# Try to read the file as the container user
docker exec cavgo-maqtt cat /opt/cavgo-data/firebase-credentials.json
```

If this fails with "Permission denied", the permissions are incorrect.

### 4. Check container user details
```bash
# Check what user the container is running as
docker exec cavgo-maqtt id
```

Should show: `uid=1001(spring) gid=1001(spring)`

### 5. Check environment variable in container
```bash
# Verify the environment variable is set correctly
docker exec cavgo-maqtt env | grep GOOGLE_APPLICATION_CREDENTIALS
```

Should show: `GOOGLE_APPLICATION_CREDENTIALS=/opt/cavgo-data/firebase-credentials.json`

## How to Fix Permissions

### Option 1: Fix via Deployment Script (Recommended)
The deployment script now automatically sets correct permissions. Re-run deployment:
```bash
./deploy-cavgo.sh
```

### Option 2: Fix Manually on Target Server
```bash
# SSH into target server
ssh user@api.gocavgo.com

# Fix permissions
sudo chmod 644 /opt/cavgo-data/firebase-credentials.json
sudo chown 1001:1001 /opt/cavgo-data/firebase-credentials.json

# Verify
ls -la /opt/cavgo-data/firebase-credentials.json

# Restart the container
cd /opt/cavgo-system
docker compose restart cavgomqt
```

### Option 3: Quick Fix Script
```bash
#!/bin/bash
# Run on target server
sudo chmod 644 /opt/cavgo-data/firebase-credentials.json
sudo chown 1001:1001 /opt/cavgo-data/firebase-credentials.json
docker restart cavgo-maqtt
```

## Verification Checklist

After fixing permissions, verify everything works:

1. ✅ File exists: `ls -la /opt/cavgo-data/firebase-credentials.json`
2. ✅ Correct permissions: `-rw-r--r--` (644)
3. ✅ Correct ownership: `1001:1001` (spring:spring)
4. ✅ Container can read: `docker exec cavgo-maqtt cat /opt/cavgo-data/firebase-credentials.json`
5. ✅ Environment variable set: `docker exec cavgo-maqtt env | grep GOOGLE_APPLICATION_CREDENTIALS`
6. ✅ Application starts without errors: `docker logs cavgo-maqtt | grep -i firebase`

## Container User Details

- **Container**: `cavgo-maqtt` (or `cavgo-mqtt` in docker-compose.yml)
- **User**: `spring`
- **UID**: `1001`
- **GID**: `1001`
- **File Path**: `/opt/cavgo-data/firebase-credentials.json`
- **Required Permissions**: `644` (readable by owner and group)
- **Required Ownership**: `1001:1001` (spring:spring)

## Common Issues

### Issue: File owned by root
**Solution**: `sudo chown 1001:1001 /opt/cavgo-data/firebase-credentials.json`

### Issue: File permissions too restrictive (600)
**Solution**: `sudo chmod 644 /opt/cavgo-data/firebase-credentials.json`

### Issue: File doesn't exist
**Solution**: Ensure `GOOGLE_APPLICATION_CREDENTIALS` is set on host and re-run deployment

### Issue: Volume mount not working
**Solution**: Check docker-compose.yml has:
```yaml
volumes:
  - /opt/cavgo-data/firebase-credentials.json:/opt/cavgo-data/firebase-credentials.json:ro
```

## Prevention

The deployment script (`deploy-cavgo.sh`) now automatically:
1. Sets correct permissions (644)
2. Sets correct ownership (1001:1001)
3. Verifies file exists before deployment
4. Fixes permissions in finalization step

If you encounter this error after deployment, the finalization step should have fixed it. If not, use the manual fix commands above.



