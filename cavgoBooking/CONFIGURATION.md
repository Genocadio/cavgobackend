# Configuration Guide

This document explains how the booking service is configured to work in both Docker and local development environments.

## Environment Variables

The service uses the following environment variables:

### Database Configuration
- `DATABASE_URL`: PostgreSQL connection string
- `SERVER_ADDRESS`: Server address and port (e.g., `:6030`)
- `PORT`: Service port (e.g., `6030`)
- `ENVIRONMENT`: Environment name (`development` or `docker`)

### Service Discovery
- `TRIP_SERVICE_URL`: URL of the trip service
- `EUREKA_SERVER_URL`: Eureka server URL
- `EUREKA_APP_NAME`: Application name for Eureka registration
- `EUREKA_REGISTER`: Whether to register with Eureka (`true`/`false`)
- `EUREKA_PREFER_IP`: Whether to prefer IP over hostname (`true`/`false`)

## Local Development

For local development, create a `.env` file in the project root:

```env
# Database Configuration
DATABASE_URL=postgres://postgres:postgres@localhost/cavgobooks?sslmode=disable

# Server Configuration
SERVER_ADDRESS=:8030
PORT=8030
ENVIRONMENT=development

# Trip Service Configuration
TRIP_SERVICE_URL=http://localhost:6080

# Eureka Configuration
EUREKA_SERVER_URL=http://localhost:8761
EUREKA_APP_NAME=CAVGOBOOKING
EUREKA_REGISTER=true
EUREKA_PREFER_IP=true
```

## Docker Environment

When running in Docker, the service uses environment variables set in `docker-compose.yml`:

```yaml
environment:
  DATABASE_URL: postgres://postgres:postgres@postgres:5432/cavgobooks?sslmode=disable
  SERVER_ADDRESS: :6030
  PORT: 6030
  ENVIRONMENT: docker
  TRIP_SERVICE_URL: http://cavgotrips:6080
  EUREKA_SERVER_URL: http://eurekacavgo:8761
  EUREKA_APP_NAME: CAVGOBOOKING
  EUREKA_REGISTER: true
  EUREKA_PREFER_IP: true
```

## Key Differences

### Local Development
- Uses `localhost` for service URLs
- Uses `.env` file for configuration
- Eureka server at `http://localhost:8761`
- Trip service at `http://localhost:6080`

### Docker Environment
- Uses Docker service names for URLs
- Uses environment variables (no `.env` file)
- Eureka server at `http://eurekacavgo:8761`
- Trip service at `http://cavgotrips:6080`
- Database at `postgres:5432`

## Testing Configuration

Run the test script to verify your configuration:

```bash
./test-config.sh
```

## Troubleshooting

### Eureka Registration Issues
1. Check if Eureka server is running
2. Verify the `EUREKA_SERVER_URL` is correct
3. Check network connectivity between services
4. Look for Docker network issues

### Database Connection Issues
1. Verify PostgreSQL is running
2. Check the `DATABASE_URL` format
3. Ensure database exists and is accessible

### Service Discovery Issues
1. Verify all services are registered with Eureka
2. Check service URLs are correct for the environment
3. Ensure proper Docker networking 