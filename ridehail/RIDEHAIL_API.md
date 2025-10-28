# Ridehail REST API Documentation

## Overview
A Spring Boot REST API for ride-hailing service with PostgreSQL + PostGIS for spatial operations, JWT authentication, and Eureka service discovery.

## Tech Stack
- **Backend**: Spring Boot 3.3.4, Java 21
- **Database**: PostgreSQL 15+ with PostGIS extension
- **Security**: Spring Security with JWT tokens
- **Spatial**: Hibernate Spatial for PostGIS integration
- **Discovery**: Spring Cloud Netflix Eureka Client
- **Build**: Gradle with Spring Cloud BOM

## Data Model

### Core Tables
- `users`: id, phone(unique), password_hash, first_name, last_name, role[DRIVER|PASSENGER], created_at
- `drivers`: user_id PK/FK, plate_number, is_available, current_location geography(Point,4326), updated_at
- `passengers`: user_id PK/FK, current_location geography(Point,4326), updated_at
- `trips`: id, passenger_id, driver_id, status, origin geography(Point,4326), destination geography(Point,4326), driver_to_pickup_meters, origin_to_destination_meters, driver_to_pickup_eta_seconds, origin_to_destination_eta_seconds, created_at, updated_at, started_at, completed_at

### Trip Status Flow
```
REQUESTED → DRIVER_ASSIGNED → EN_ROUTE → AT_PICKUP → IN_PROGRESS → COMPLETED
                                                      ↓
                                                   CANCELED
```

## Authentication

### Register Driver
```bash
POST /auth/register
Content-Type: application/json

{
  "role": "DRIVER",
  "phone": "+1234567890",
  "password": "password123",
  "firstName": "John",
  "lastName": "Doe",
  "plateNumber": "ABC123"
}
```

### Register Passenger
```bash
POST /auth/register
Content-Type: application/json

{
  "role": "PASSENGER",
  "phone": "+1234567890",
  "password": "password123",
  "firstName": "Jane",
  "lastName": "Smith"
}
```

### Login
```bash
POST /auth/login
Content-Type: application/json

{
  "phone": "+1234567890",
  "password": "password123"
}
```

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 123,
  "role": "PASSENGER",
  "firstName": "Jane",
  "lastName": "Smith",
  "phone": "+1234567890"
}
```

### Get Current User
```bash
GET /auth/me
Authorization: Bearer <JWT>
```

## Passenger Workflow

### 1. Share Location
```bash
POST /passengers/location
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "lat": 40.7128,
  "lon": -74.0060
}
```

### 2. Request Ride
```bash
POST /rides/request
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "originLat": 40.7128,
  "originLon": -74.0060,
  "destLat": 40.7589,
  "destLon": -73.9851,
  "radiusMeters": 3000
}
```

**Success Response:**
```json
{
  "id": 1,
  "passengerId": 123,
  "driverId": 456,
  "status": "DRIVER_ASSIGNED",
  "origin": {"lat": 40.7128, "lon": -74.0060},
  "destination": {"lat": 40.7589, "lon": -73.9851},
  "driverToPickupMeters": 1200.5,
  "originToDestinationMeters": 5500.2,
  "driverToPickupEtaSeconds": 144,
  "originToDestinationEtaSeconds": 495,
  "createdAt": "2025-10-20T15:45:00Z"
}
```

**No Driver Found:**
```json
"No available drivers nearby"
```

### 3. Check Active/Pending Requests
```bash
GET /trips/active
Authorization: Bearer <JWT>
```

**Has Active Trip:**
```json
{
  "id": 1,
  "status": "DRIVER_ASSIGNED",
  "driverId": 456,
  "origin": {"lat": 40.7128, "lon": -74.0060},
  "destination": {"lat": 40.7589, "lon": -73.9851},
  "driverToPickupEtaSeconds": 144
}
```

**No Active Trip:**
```
HTTP 204 No Content
```

### 4. Cancel Trip (if needed)
```bash
POST /trips/{id}/cancel
Authorization: Bearer <JWT>
```

## Driver Workflow

### 1. Share Location (Go Online)
```bash
POST /drivers/location
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "lat": 40.7200,
  "lon": -74.0100
}
```

### 2. Check for Assigned Trips
```bash
GET /trips/active
Authorization: Bearer <JWT>
```

### 3. Accept Trip
```bash
POST /trips/{id}/accept
Authorization: Bearer <JWT>
```

### 4. Arrive at Pickup
```bash
POST /trips/{id}/arrive-pickup
Authorization: Bearer <JWT>
```

### 5. Start Trip
```bash
POST /trips/{id}/start
Authorization: Bearer <JWT>
```

### 6. Complete Trip
```bash
POST /trips/{id}/complete
Authorization: Bearer <JWT>
```

## Trip Management

### Get Trip Details
```bash
GET /trips/{id}
Authorization: Bearer <JWT>
```

### Cancel Trip (Driver or Passenger)
```bash
POST /trips/{id}/cancel
Authorization: Bearer <JWT>
```

## Utility Endpoints

### Find Nearby Drivers (Testing)
```bash
GET /drivers/nearby?lat=40.7128&lon=-74.0060&radius=3000
Authorization: Bearer <JWT>
```

## Configuration

### Database Setup
1. Start PostgreSQL with PostGIS:
```bash
docker run --name ridehail-postgis -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=ridehail -p 5432:5432 -d postgis/postgis:15-3
docker exec -it ridehail-postgis psql -U postgres -d ridehail -c "CREATE EXTENSION IF NOT EXISTS postgis;"
```

2. Update `application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ridehail
    username: postgres
    password: postgres
```

### Eureka Server
Start Eureka server on port 8761:
```bash
# Example with Spring Boot Eureka Server
java -jar eureka-server.jar --server.port=8761
```

## Spatial Features

### Distance Calculation
- Uses Haversine formula for straight-line distance
- Driver to pickup distance and ETA
- Origin to destination distance and ETA
- Default speeds: 30 km/h (pickup), 40 km/h (trip)

### Nearest Driver Matching
- PostGIS spatial queries with GIST indexes
- Configurable search radius (default: 3000m)
- Atomic driver assignment with row locking

## Security

### JWT Configuration
- Secret key configurable in `application.yml`
- Default TTL: 24 hours
- Roles: `ROLE_DRIVER`, `ROLE_PASSENGER`

### Authorization
- Trip participants can view/modify their trips
- Drivers can only accept trips assigned to them
- Passengers can only request rides for themselves

## Error Handling

### Common Responses
- `400 Bad Request`: Validation errors
- `401 Unauthorized`: Invalid credentials
- `403 Forbidden`: Insufficient permissions
- `404 Not Found`: Resource not found
- `500 Internal Server Error`: Unexpected errors

### Validation
- Phone number uniqueness
- Required fields validation
- Plate number required for drivers
- Geographic coordinate validation

## Monitoring

### Health Checks
- Spring Boot Actuator endpoints enabled
- Database connectivity monitoring
- Eureka registration status

### Logging
- Structured logging with timestamps
- SQL query logging (configurable)
- Security event logging

## Development

### Running the Application
```bash
./gradlew bootRun
```

### Testing
```bash
./gradlew test
```

### Database Schema
Hibernate auto-creates tables with `ddl-auto: update`. Tables include:
- Spatial indexes on location columns
- Foreign key constraints
- Check constraints on enums

## API Examples

### Complete Passenger Flow
1. Register: `POST /auth/register` (role: PASSENGER)
2. Login: `POST /auth/login` → get JWT
3. Share location: `POST /passengers/location`
4. Request ride: `POST /rides/request`
5. Poll status: `GET /trips/active` (repeat until COMPLETED)

### Complete Driver Flow
1. Register: `POST /auth/register` (role: DRIVER, plateNumber)
2. Login: `POST /auth/login` → get JWT
3. Go online: `POST /drivers/location`
4. Check assignments: `GET /trips/active`
5. Accept: `POST /trips/{id}/accept`
6. Progress: `arrive-pickup` → `start` → `complete`

## Notes
- All location coordinates use WGS84 (EPSG:4326)
- Distances calculated in meters
- ETAs calculated in seconds
- Driver availability managed automatically
- Trip status transitions are validated
- Spatial queries optimized with PostGIS indexes
