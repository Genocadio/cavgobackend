# Trip Creation Guide

## Overview
This guide explains how to create trips using the `/trips` POST endpoint. The system supports both route-based trips (using existing routes) and custom trips (with custom waypoints).

## API Endpoint
```
POST /trips
Content-Type: application/json
```

## Complete JSON Body Structure

### Basic Trip Creation (Route-based)
```json
{
  "route_id": 123,
  "vehicle_id": 456,
  "departure_time": 1640995200,
  "connection_mode": "ONLINE",
  "notes": "Optional trip notes",
  "is_reversed": false,
  "custom_waypoints": []
}
```

### Advanced Trip Creation (Custom Waypoints)
```json
{
  "route_id": 123,
  "vehicle_id": 456,
  "departure_time": 1640995200,
  "connection_mode": "HYBRID",
  "notes": "Custom route with additional stops",
  "is_reversed": false,
  "custom_waypoints": [
    {
      "location_id": 789,
      "order": 1,
      "price": 25.50,
      "remaining_time": 1800,
      "remaining_distance": 5000.0
    },
    {
      "location_id": 790,
      "order": 2,
      "price": 30.00,
      "remaining_time": 3600,
      "remaining_distance": 10000.0
    }
  ]
}
```

## Field Details

### Required Fields

| Field | Type | Description | Validation Rules |
|-------|------|-------------|------------------|
| `route_id` | `int64` | ID of the existing route to use | Must be > 0, route must exist |
| `vehicle_id` | `int64` | ID of the vehicle for this trip | Must be > 0, vehicle must exist and be available |
| `departure_time` | `int64` | Unix timestamp for departure | Must be > 0 (future timestamp) |
| `connection_mode` | `string` | How the trip will be tracked | Must be one of: "ONLINE", "OFFLINE", "HYBRID" |

### Optional Fields

| Field | Type | Description | Default Value | Validation Rules |
|-------|------|-------------|---------------|------------------|
| `notes` | `*string` | Additional trip information | `null` | No validation |
| `is_reversed` | `bool` | Whether to reverse route waypoints | `false` | No validation |
| `custom_waypoints` | `[]CreateCustomWaypoint` | Custom waypoints for the trip | `[]` | See waypoint validation below |
| `no_waypoints` | `bool` | If true, no waypoints are copied (only origin/destination) | `false` | No validation |

## Custom Waypoint Fields

### Required Fields
| Field | Type | Description | Validation Rules |
|-------|------|-------------|------------------|
| `location_id` | `int64` | ID of the location | Must be > 0, location must exist |
| `order` | `int` | Sequence order in the trip | Must be >= 0 |

### Optional Fields
| Field | Type | Description | Validation Rules |
|-------|------|-------------|------------------|
| `price` | `*float64` | Price for this waypoint | If provided, must be > 0 |
| `remaining_time` | `*int64` | Initial remaining time in seconds | If provided, must be >= 0 |
| `remaining_distance` | `*float64` | Initial remaining distance in meters | If provided, must be >= 0 |

## Connection Mode Options

| Mode | Description | Use Case |
|------|-------------|----------|
| `ONLINE` | Real-time GPS tracking | Live passenger tracking, real-time updates |
| `OFFLINE` | No GPS tracking | Scheduled trips, offline operations |
| `HYBRID` | Mixed online/offline | Partial tracking, intermittent connectivity |

## Trip Creation Logic

### Route-based Trips (No Custom Waypoints)
1. **Uses existing route waypoints** with their predefined prices
2. **Seats are automatically set** from vehicle capacity
3. **Status is set to "SCHEDULED"** automatically
4. **Vehicle availability is checked** before creation
5. **Route waypoints are copied** to trip waypoints

### Custom Waypoint Trips
1. **Overrides route waypoints** with custom waypoints
2. **Custom waypoints are marked** with `is_custom: true`
3. **Order validation** ensures logical sequence
4. **Location existence** is verified for each waypoint
5. **Price validation** ensures positive values

### Reversed Routes
When `is_reversed: true`:
1. **Route waypoints are reversed** in order
2. **Order numbers are recalculated** (e.g., 0,1,2 becomes 2,1,0)
3. **Prices remain the same** as original route
4. **Custom waypoints** maintain their specified order

### No Waypoints Trips (NEW)
When `no_waypoints: true`:
1. **No waypoints are created** - trip only has origin and destination from route
2. **Simple point-to-point** trips without intermediate stops
3. **No pricing information** for intermediate locations
4. **Faster trip creation** - no waypoint processing
5. **Use case**: Direct routes, express services, or when intermediate stops are not needed

## Response Format

### Success Response (201 Created)
```json
{
  "id": 789,
  "route_id": 123,
  "vehicle_id": 456,
  "vehicle": {
    "id": 456,
    "company_id": 1,
    "company_name": "Express Transport",
    "capacity": 25,
    "license_plate": "RAD123A",
    "driver": {
      "name": "John Doe",
      "phone": "+250123456789"
    }
  },
  "status": "SCHEDULED",
  "departure_time": 1640995200,
  "connection_mode": "ONLINE",
  "notes": "Optional trip notes",
  "seats": 25,
  "is_reversed": false,
  "has_custom_waypoints": true,
  "created_at": "2022-01-01T00:00:00Z",
  "updated_at": "2022-01-01T00:00:00Z",
  "route": {
    "id": 123,
    "origin": {
      "id": 1,
      "code": "110001",
      "custom_name": "Kigali Airport",
      "google_place_name": "Kigali International Airport"
    },
    "destination": {
      "id": 2,
      "code": "230001",
      "custom_name": "Musanze Center",
      "google_place_name": "Musanze, Rwanda"
    }
  },
  "waypoints": [
    {
      "id": 1,
      "trip_id": 789,
      "location_id": 789,
      "order": 1,
      "price": 25.50,
      "is_passed": false,
      "is_next": false,
      "is_custom": true,
      "remaining_time": 1800,
      "remaining_distance": 5000.0
    }
  ]
}
```

### Error Response (400 Bad Request)
```json
{
  "error": "validation error",
  "message": "vehicle_id is required and must be greater than 0"
}
```

### Error Response (409 Conflict)
```json
{
  "error": "conflict error",
  "message": "vehicle already has an active trip"
}
```

## Validation Rules Summary

### Route ID
- ✅ Must be provided
- ✅ Must be > 0
- ✅ Route must exist in database

### Vehicle ID
- ✅ Must be provided
- ✅ Must be > 0
- ✅ Vehicle must exist in vehicle service
- ✅ Vehicle must have status "AVAILABLE"
- ✅ Vehicle must not have active trips

### Departure Time
- ✅ Must be provided
- ✅ Must be > 0 (Unix timestamp)
- ✅ Should be in the future

### Connection Mode
- ✅ Must be provided
- ✅ Must be one of: "ONLINE", "OFFLINE", "HYBRID"

### Custom Waypoints (if provided)
- ✅ Each waypoint must have valid `location_id` (> 0)
- ✅ Each waypoint must have valid `order` (>= 0)
- ✅ If `price` is provided, must be > 0
- ✅ If `remaining_time` is provided, must be >= 0
- ✅ If `remaining_distance` is provided, must be >= 0

## Example Use Cases

### 1. Simple Route Trip
```bash
curl -X POST http://localhost:8080/api/v1/trips \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 123,
    "vehicle_id": 456,
    "departure_time": 1640995200,
    "connection_mode": "ONLINE"
  }'
```

### 2. Custom Waypoint Trip
```bash
curl -X POST http://localhost:8080/api/v1/trips \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 123,
    "vehicle_id": 456,
    "departure_time": 1640995200,
    "connection_mode": "HYBRID",
    "notes": "Additional stops for package delivery",
    "custom_waypoints": [
      {
        "location_id": 789,
        "order": 1,
        "price": 15.00
      },
      {
        "location_id": 790,
        "order": 2,
        "price": 20.00
      }
    ]
  }'
```

### 3. Reversed Route Trip
```bash
curl -X POST http://localhost:8080/api/v1/trips \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 123,
    "vehicle_id": 456,
    "departure_time": 1640995200,
    "connection_mode": "OFFLINE",
    "is_reversed": true,
    "notes": "Return trip from destination to origin"
  }'
```

### 4. No Waypoints Trip (NEW)
```bash
curl -X POST http://localhost:8080/api/v1/trips \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 123,
    "vehicle_id": 456,
    "departure_time": 1640995200,
    "connection_mode": "ONLINE",
    "notes": "Direct point-to-point trip",
    "no_waypoints": true
  }'
```

## Notes

- **Seats are automatically set** from vehicle capacity
- **Status is automatically set** to "SCHEDULED"
- **Timestamps are in Unix format** (seconds since epoch)
- **Prices are in the system's currency** (typically local currency)
- **Distances are in meters**
- **Times are in seconds**
- **Vehicle availability is checked** before trip creation
- **Route waypoints are automatically copied** unless custom waypoints are provided or `no_waypoints: true`
