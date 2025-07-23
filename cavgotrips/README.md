# Trip Service API

A Go-based REST API for managing trips, routes, and locations with waypoint tracking.

## Features

- **Location Management**: Create and manage geographical locations
- **Route Management**: Create routes with multiple waypoints and pricing
- **Trip Management**: Create trips based on routes with real-time progress tracking
- **Waypoint Tracking**: Track trip progress through waypoints
- **Reverse Route Support**: Support for reversed route directions
- **Service Discovery**: Eureka client integration for microservices architecture

## Architecture

This project follows Clean Architecture principles with the following structure:

- **Models**: Data structures and entities
- **Repository**: Data access layer
- **Service**: Business logic layer
- **Handlers**: HTTP request/response handling
- **Router**: Route definitions and middleware
- **Config**: Configuration management
- **Database**: Database connection and migrations

## Prerequisites

- Go 1.21 or higher
- PostgreSQL database
- Git
- Eureka Server (optional, for service discovery)

## Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd trip-service
```

2. Install dependencies:
```bash
go mod download
```

3. Set up your environment variables:
```bash
cp .env.example .env
# Edit .env with your database credentials
```

4. Run the application:
```bash
go run cmd/server/main.go
```

## Database Setup

The application uses PostgreSQL with GORM for database operations. The database schema will be automatically migrated when the application starts.

### Environment Variables

Create a `.env` file in the root directory:

```env
DATABASE_URL=host=localhost user=postgres password=postgres dbname=trip_service port=5432 sslmode=disable
PORT=8080
```

### Eureka Service Discovery Configuration

The application includes Eureka client integration for service discovery. Configure the following environment variables:

```env
# Eureka Configuration
EUREKA_SERVER_URL=http://localhost:8761
EUREKA_APP_NAME=cavgotrips
EUREKA_INSTANCE_ID=
EUREKA_REGISTER=true
EUREKA_PREFER_IP=true
```

#### Eureka Configuration Options:

- `EUREKA_SERVER_URL`: URL of the Eureka server (default: http://localhost:8761)
- `EUREKA_APP_NAME`: Application name for registration (default: cavgotrips)
- `EUREKA_INSTANCE_ID`: Custom instance ID (optional, auto-generated if empty)
- `EUREKA_REGISTER`: Enable/disable registration with Eureka (default: true)
- `EUREKA_PREFER_IP`: Use IP address instead of hostname (default: true)

The Eureka client will:
- Automatically register the service on startup
- Send heartbeats every 30 seconds
- Deregister gracefully on shutdown
- Provide health check endpoint at `/health`

## API Endpoints

### Locations

- `POST /locations` - Create a new location
- `GET /locations` - Get all locations

### Routes

- `POST /routes` - Create a new route
- `GET /routes` - Get all routes
- `GET /routes/{id}` - Get a specific route

### Trips

- `POST /trips` - Create a new trip
- `GET /trips` - Get all trips
- `GET /trips/{id}` - Get a specific trip
- `PUT /trips/{id}/progress` - Update trip progress
- `GET /trips/{id}/progress` - Get trip progress

## API Usage Examples

### Create a Location

```json
POST /locations
{
  "latitude": 40.7128,
  "longitude": -74.0060,
  "google_place_name": "New York City",
  "custom_name": "NYC Office"
}
```

### Create a Route

```json
POST /routes
{
  "name": "NYC to Boston",
  "origin_id": 1,
  "destination_id": 2,
  "total_price": 45.50,
  "waypoints": [
    {
      "location_id": 3,
      "order": 1,
      "price": 15.00
    }
  ]
}
```

### Create a Trip

```json
POST /trips
{
  "route_id": 1,
  "car_plate": "ABC123",
  "car_company": "Express Transport",
  "status": "SCHEDULED",
  "departure_time": 1640995200,
  "connection_mode": "ONLINE",
  "seats": 4,
  "is_reversed": false
}
```

### Update Trip Progress

```json
PUT /trips/{"id"}/progress
{
  "status": "IN_PROGRESS",
  "remaining_time_to_destination": 3600,
  "remaining_distance_to_destination": 85.5,
  "current_speed": 65.0,
  "passed_waypoint_id": 1
}
```

## Data Models

### Location
- Geographical coordinates
- Google Places integration
- Custom naming support

### Route
- Origin and destination locations
- Multiple waypoints with pricing
- Distance and duration estimates

### Trip
- Route-based trip instances
- Real-time progress tracking
- Car and company information
- Status management (SCHEDULED, IN_PROGRESS, COMPLETED, NOT_COMPLETED)

## Development

### Running Tests

```bash
go test ./...
```

### Building

```bash
go build -o bin/server cmd/server/main.go
```

### Code Structure

```
trip-service/
├── cmd/server/          # Application entry point
├── internal/
│   ├── config/          # Configuration management
│   ├── database/        # Database setup
│   ├── handlers/        # HTTP handlers
│   ├── middleware/      # HTTP middleware
│   ├── models/          # Data models
│   ├── repository/      # Data access layer
│   ├── service/         # Business logic
│   └── router/          # Route definitions
└── pkg/utils/           # Utility functions
```

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.