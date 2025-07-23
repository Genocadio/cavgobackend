# CAVGO Microservices Docker Setup

This Docker Compose setup provides a complete development environment for all CAVGO microservices with PostgreSQL database and service discovery.

## Architecture

The setup includes:
- **PostgreSQL Database** - Shared database with separate schemas for each service
- **Eureka Service Discovery** - Service registry for microservices communication
- **Cavgomain** - Main Java service (port 6060)
- **Cavgogateway** - API Gateway (port 8080)
- **Cavgotrips** - Go service for trip management (port 6080)
- **Cavgobooks** - Go service for booking management (port 6070)

## Prerequisites

- Docker and Docker Compose installed
- At least 4GB of available RAM
- Ports 5432, 6060, 6070, 6080, 8080, 8761 available

## Quick Start

1. **Start all services:**
   ```bash
   docker-compose up -d
   ```

2. **View logs:**
   ```bash
   docker-compose logs -f
   ```

3. **Stop all services:**
   ```bash
   docker-compose down
   ```

## Service URLs

Once all services are running, you can access:

- **Eureka Dashboard**: http://localhost:8761
- **API Gateway**: http://localhost:8080
- **Cavgomain Service**: http://localhost:6060
- **Cavgotrips Service**: http://localhost:6080
- **Cavgobooks Service**: http://localhost:6070
- **PostgreSQL Database**: localhost:5432

## Database Configuration

The setup automatically creates the following databases:
- `cavgomain` - For the main Java service
- `cavgotrips` - For the trips Go service
- `cavgobooks` - For the books Go service

Database initialization is handled by the `init-multiple-dbs.sh` script.

## Environment Variables

### Java Services (Spring Boot)
- `SPRING_PROFILES_ACTIVE=docker` - Uses docker profile
- `SPRING_DATASOURCE_URL` - Database connection URL
- `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` - Eureka server URL

### Go Services
- `DATABASE_URL` - Database connection string
- `PORT` - Service port
- `EUREKA_SERVER_URL` - Eureka server URL
- `EUREKA_APP_NAME` - Service name for registration

## Development Workflow

1. **Start the infrastructure:**
   ```bash
   docker-compose up postgres eurekacavgo -d
   ```

2. **Start specific services:**
   ```bash
   docker-compose up cavgomain cavgogateway -d
   ```

3. **Rebuild a specific service:**
   ```bash
   docker-compose build cavgomain
   docker-compose up cavgomain -d
   ```

## Troubleshooting

### Check service health:
```bash
docker-compose ps
```

### View service logs:
```bash
docker-compose logs [service-name]
```

### Access database:
```bash
docker-compose exec postgres psql -U postgres -d [database-name]
```

### Reset everything:
```bash
docker-compose down -v
docker-compose up -d
```

## Production Considerations

For production deployment:

1. **Security:**
   - Change default passwords
   - Use environment-specific JWT secrets
   - Enable SSL/TLS for database connections

2. **Performance:**
   - Configure database connection pools
   - Set appropriate JVM heap sizes for Java services
   - Use production-grade PostgreSQL configuration

3. **Monitoring:**
   - Add logging aggregation (ELK stack)
   - Implement metrics collection (Prometheus/Grafana)
   - Set up alerting

## Service Dependencies

The startup order is managed by Docker Compose dependencies:
1. PostgreSQL (with health check)
2. Eureka Service Discovery
3. Cavgomain (Java service)
4. Cavgogateway (depends on Eureka and Cavgomain)
5. Cavgotrips and Cavgobooks (Go services)

## Network Configuration

All services are connected via the `cavgo-network` bridge network, allowing them to communicate using service names as hostnames. 