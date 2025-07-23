# Cavgo Booking Service

A Go-based microservice for handling booking operations in the Cavgo system.

## Features

- RESTful API for booking management
- PostgreSQL database integration
- Eureka service discovery integration
- Health check endpoints
- Docker support

## Environment Configuration

The service can be configured using environment variables:

### Database Configuration
- `DATABASE_URL`: PostgreSQL connection string (default: `postgres://postgres:postgres@localhost/cavgobooks?sslmode=disable`)

### Server Configuration
- `SERVER_ADDRESS`: Server address and port (default: `:6030`)
- `PORT`: Service port (default: `6030`)
- `ENVIRONMENT`: Environment name (default: `development`)

### Trip Service Configuration
- `TRIP_SERVICE_URL`: URL of the trip service (default: `http://localhost:6080`)

### Eureka Configuration
- `EUREKA_SERVER_URL`: Eureka server URL (default: `http://localhost:8761`)
- `EUREKA_APP_NAME`: Application name in Eureka (default: `CAVGOBOOKING`)
- `EUREKA_REGISTER`: Whether to register with Eureka (default: `true`)
- `EUREKA_PREFER_IP`: Whether to prefer IP over hostname (default: `true`)
- `HOST_IP`: Host IP address (auto-detected if not provided)

## Docker Compose Integration

The service is configured to work with the Cavgo microservices stack:

```yaml
# Booking Service (Go)
cavgobooking:
  build:
    context: ./cavgoBooking
    dockerfile: Dockerfile
  container_name: cavgo-booking
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
  ports:
    - "6030:6030"
  depends_on:
    postgres:
      condition: service_healthy
    cavgotrips:
      condition: service_healthy
  networks:
    - cavgo-network
  healthcheck:
    test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:6030/health"]
    interval: 30s
    timeout: 3s
    retries: 3
    start_period: 5s
```

## Local Development

1. Copy the example configuration:
   ```bash
   cp config.env.example .env
   ```

2. Update the `.env` file with your local settings

3. Run the service:
   ```bash
   go run ./main
   ```

## API Endpoints

- `GET /health` - Health check endpoint
- `POST /bookings` - Create a new booking
- `GET /bookings` - List all bookings
- `GET /bookings/{id}` - Get a specific booking
- `PUT /bookings/{id}` - Update a booking
- `DELETE /bookings/{id}` - Delete a booking

## Eureka Integration

The service automatically:

1. Registers with Eureka on startup
2. Sends heartbeats every 30 seconds
3. Deregisters on graceful shutdown

The service will log Eureka registration status and any connection issues. 