# Docker Setup for CavGoTrips

This document explains how to run the CavGoTrips application using Docker.

## Quick Start

### Using Docker Compose (Recommended)

1. **Build and run with docker-compose:**
   ```bash
   docker-compose up --build
   ```

2. **Run in background:**
   ```bash
   docker-compose up -d --build
   ```

3. **Stop the services:**
   ```bash
   docker-compose down
   ```

### Using Docker directly

1. **Build the image:**
   ```bash
   docker build -t cavgotrips .
   ```

2. **Run the container:**
   ```bash
   docker run -p 8080:8080 \
     -e DATABASE_URL="postgres://username:password@host:5432/dbname?sslmode=disable" \
     -e PORT=   \
     -e EUREKA_URL="http://eureka-server:8761/eureka" \
     -e APP_NAME=cavgotrips \
     -e APP_INSTANCE_ID=cavgotrips-1 \
     -e LOG_LEVEL=info \
     -e ENVIRONMENT=production \
     cavgotrips
   ```

## Environment Variables

The application supports the following environment variables:

### Required
- `DATABASE_URL`: PostgreSQL connection string
- `PORT`: Server port (default: 8080)

### Optional
- `EUREKA_URL`: Eureka service discovery URL
- `APP_NAME`: Application name for service discovery
- `APP_INSTANCE_ID`: Instance ID for service discovery
- `LOG_LEVEL`: Logging level (default: info)
- `ENVIRONMENT`: Environment name (development/production)

## Example .env file

Create a `.env` file in the project root:

```env
# Database Configuration
DATABASE_URL=postgres://postgres:password@localhost:5432/cavgotrips?sslmode=disable

# Server Configuration
PORT=8080

# Eureka Service Discovery (optional)
EUREKA_URL=http://localhost:8761/eureka
APP_NAME=cavgotrips
APP_INSTANCE_ID=cavgotrips-1

# Application Configuration
LOG_LEVEL=info
ENVIRONMENT=development
```

## Docker Features

### Multi-stage Build
- Uses multi-stage build to reduce final image size
- Dependencies are cached in separate layers for faster rebuilds

### Security
- Runs as non-root user (appuser)
- Minimal Alpine Linux base image
- Includes ca-certificates for HTTPS requests

### Health Checks
- Built-in health check endpoint
- Docker health check configured

### Layer Caching
- Go modules are downloaded only when `go.mod` or `go.sum` changes
- Source code changes don't trigger dependency re-download

## Development

For development, you can mount the source code:

```bash
docker run -p 8080:8080 \
  -v $(pwd):/app \
  -e DATABASE_URL="your-db-url" \
  cavgotrips
```

## Production

For production deployment:

1. Use environment-specific `.env` files
2. Set appropriate `ENVIRONMENT=production`
3. Configure proper `DATABASE_URL` for your production database
4. Use Docker secrets for sensitive information
5. Consider using Docker Swarm or Kubernetes for orchestration 