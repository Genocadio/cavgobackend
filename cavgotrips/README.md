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
- `GET /locations` - Get all locations (paginated)
- `GET /locations?search={term}` - Search locations by custom name, Google place name, or location code (paginated)
- `GET /locations?page={page}&limit={limit}` - Get paginated locations
- `GET /locations?search={term}&page={page}&limit={limit}` - Search with pagination
- `GET /locations/{id}` - Get a specific location by ID
- `PUT /locations/{id}` - Update a location
- `DELETE /locations/{id}` - Delete a location

### Routes

- `POST /routes` - Create a new route
- `GET /routes` - Get all routes (with search and filtering)
- `GET /routes?origin={term}&destination={term}` - Search routes by origin/destination
- `GET /routes?city_route={true|false}` - Filter routes by city route status
- `GET /routes?origin_province={province}&destination_province={province}` - Filter by provinces
- `GET /routes?page={page}&limit={limit}` - Get paginated routes
- `GET /routes/{id}` - Get a specific route
- `PUT /routes/{id}` - Update a route
- `DELETE /routes/{id}` - Delete a route
- `GET /routes/price-range?min_price={price}&max_price={price}` - Get routes by price range
- `GET /routes/distance-range?min_distance={meters}&max_distance={meters}` - Get routes by distance range
- `GET /routes/statistics` - Get route statistics

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

### Search Locations

```bash
# Search by custom name or Google place name
GET /locations?search=NYC

# Search by location code (numeric)
GET /locations?search=11001

# Pagination examples
GET /locations?page=1&limit=20
GET /locations?search=NYC&page=2&limit=10

# Get a specific location
GET /locations/1

# Update a location
PUT /locations/1
{
  "latitude": 40.7128,
  "longitude": -74.0060,
  "google_place_name": "Updated New York City",
  "custom_name": "Updated NYC Office",
  "province": "kigali",
  "district": "gasabo"
}

# Update location with province/district change (generates new code)
PUT /locations/1
{
  "latitude": 40.7128,
  "longitude": -74.0060,
  "google_place_name": "Updated New York City",
  "custom_name": "Updated NYC Office",
  "province": "north",
  "district": "musanze"
}

# Delete a location
DELETE /locations/1
```

### Search and Filter Routes

```bash
# Search by origin and destination
GET /routes?origin=kigali&destination=musanze

# Filter by city route status
GET /routes?city_route=true

# Filter by provinces
GET /routes?origin_province=kigali&destination_province=north

# Combine search and filters
GET /routes?origin=kigali&city_route=true&origin_province=kigali

# Pagination with search
GET /routes?origin=kigali&page=1&limit=20

# Basic pagination (all routes)
GET /routes?page=1&limit=10

# Complex search with pagination
GET /routes?origin=kigali&city_route=true&page=2&limit=15

# Get routes by price range
GET /routes/price-range?min_price=10&max_price=50

# Get routes by distance range
GET /routes/distance-range?min_distance=10000&max_distance=100000

# Get route statistics
GET /routes/statistics
```

### Create a Route

```json
POST /routes
{
  "name": "NYC to Boston",
  "origin_id": 1,
  "destination_id": 2,
  "route_price": 45.50,
  "distance_meters": 85000,
  "estimated_duration_seconds": 7200,
  "city_route": false,
  "waypoints": [
    {
      "location_id": 3,
      "order": 1,
      "price": 15.00
    }
  ]
}
```

### Update a Route

```json
PUT /routes/1
{
  "name": "Updated Route Name",
  "route_price": 50.00,
  "distance_meters": 90000,
  "estimated_duration_seconds": 7800
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

## Pagination

The API supports pagination for location and route endpoints to handle large datasets efficiently. All paginated responses include pagination metadata.

For detailed information about route search and filtering, see [ROUTE_SEARCH_GUIDE.md](ROUTE_SEARCH_GUIDE.md).

### Pagination Parameters

- `page` (optional): Page number (default: 1)
- `limit` (optional): Number of items per page (default: 20, max: 100)
- `search` (optional): Search term for filtering

### Paginated Response Format

```json
{
  "data": [
    {
      "id": 1,
      "latitude": 40.7128,
      "longitude": -74.0060,
      "code": "11001",
      "google_place_name": "New York City",
      "custom_name": "NYC Office",
      "province": "New York",
      "district": "Manhattan",
      "place_id": "ChIJOwg_06VPwokRYv534QaPC8g",
      "created_at": "2024-01-01T00:00:00Z",
      "updated_at": "2024-01-01T00:00:00Z"
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 20,
    "total": 150,
    "total_pages": 8,
    "has_next": true,
    "has_prev": false
  }
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