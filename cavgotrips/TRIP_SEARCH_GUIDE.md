# Trip Search Guide

## Overview
The trip search functionality supports searching by both location names and location codes, providing flexible and powerful filtering capabilities.

## Search Parameters

### Basic Parameters
- `origin`: Starting location (name or code)
- `destination`: Ending location (name or code)  
- `company`: Vehicle company name
- `status`: Trip status (SCHEDULED, IN_PROGRESS, COMPLETED, NOT_COMPLETED)
- `vehicle_id`: Specific vehicle ID
- `city_route`: Filter by city route (true/false)

### Pagination Parameters
- `limit`: Number of results per page (default: 20)
- `offset`: Number of results to skip
- `session_uuid`: SSE session UUID for real-time updates

## Search Types

### 1. Name-Based Search
Search using location names (case-insensitive substring matching):

```bash
# Search by custom names or Google Place names
GET /trips?origin=kigali&destination=musanze
GET /trips?origin=airport&destination=city center
```

### 2. Code-Based Search (NEW)
Search using location codes (prefix matching):

```bash
# Search by location codes
GET /trips?origin=110&destination=230
GET /trips?origin=11&destination=23
```

### 3. Mixed Search
Combine name and code searches:

```bash
# Mix name and code searches
GET /trips?origin=kigali&destination=230
GET /trips?origin=110&destination=musanze
```

## Location Code Format

Location codes follow the format: `PPDDLLL`
- `PP`: Province code (1-5)
- `DD`: District code (1-7, varies by province)
- `LLL`: Location number within district (001-999)

### Province Codes
- 1: Kigali
- 2: North
- 3: East
- 4: South
- 5: West

### Example Codes
- `110001`: Kigali, Gasabo, Location 001
- `230001`: North, Musanze, Location 001
- `310001`: East, Bugesera, Location 001

## Search Behavior

### Code Search Logic
- **Exact Prefix Match**: `110` matches `110001`, `110002`, etc.
- **Partial Province**: `1` matches all Kigali locations
- **Partial District**: `11` matches all Gasabo locations

### Name Search Logic
- **Case Insensitive**: `Kigali` matches `kigali`, `KIGALI`
- **Substring Match**: `kig` matches `Kigali`, `Kigali Airport`
- **Multiple Fields**: Searches both `custom_name` and `google_place_name`

### Waypoint Order Validation
When both origin and destination are found as waypoints, the system ensures:
- Origin waypoint comes before destination waypoint in trip sequence
- Only returns trips where the route makes logical sense

## API Endpoints

### 1. General Trip Search
```
GET /trips
```
**Query Parameters**: `origin`, `destination`, `company`, `status`, `vehicle_id`, `city_route`, `limit`, `offset`, `session_uuid`

### 2. Vehicle-Specific Trips (NEW)
```
GET /trips/vehicle/{vehicle_id}
```
**Path Parameters**: `vehicle_id` - ID of the vehicle
**Query Parameters**: `status`, `limit`, `offset`, `session_uuid`

**Features**:
- Get all trips for a specific vehicle
- Filter by trip status
- Pagination support
- Session management for real-time updates
- Optimized for vehicle-specific queries

**Example**:
```bash
# Get all trips for vehicle 123
GET /trips/vehicle/123

# Get SCHEDULED trips for vehicle 123 with pagination
GET /trips/vehicle/123?status=SCHEDULED&limit=10&offset=0

# Get IN_PROGRESS trips for vehicle 123 with session
GET /trips/vehicle/123?status=IN_PROGRESS&limit=5&session_uuid=abc123
```

## API Examples

### Basic Search
```bash
GET /trips?origin=kigali&destination=musanze&limit=10
```

### Code-Based Search
```bash
GET /trips?origin=110&destination=230&company=express
```

### Vehicle-Specific Search
```bash
GET /trips/vehicle/456
GET /trips/vehicle/456?status=SCHEDULED&limit=20
```

### Paginated Search with Session
```bash
GET /trips?origin=110&destination=230&limit=20&offset=40&session_uuid=abc123
```

### City Route Filter
```bash
GET /trips?origin=kigali&destination=airport&city_route=true
```

## Response Format

```json
{
  "trips": [
    {
      "id": 1,
      "route_id": 123,
      "vehicle_id": 456,
      "status": "SCHEDULED",
      "departure_time": 1640995200,
      "route": {
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
      "waypoints": [...]
    }
  ],
  "total": 50,
  "limit": 20,
  "offset": 0,
  "sse_uuid": "abc123" // Only included for new sessions
}
```

## Error Handling

- **Invalid Parameters**: Returns 400 Bad Request with validation errors
- **No Results**: Returns empty trips array with total=0
- **Server Errors**: Returns 500 Internal Server Error

## Performance Notes

- **Code-based searches** are generally faster than name-based searches
- **Vehicle-specific endpoint** (`/trips/vehicle/{id}`) is optimized for vehicle queries
- **Pagination** is recommended for large result sets
- **Use specific codes** when possible for optimal performance
- **Session management** reduces overhead for subsequent requests

## Comparison: General vs Vehicle-Specific Search

| Feature | General Search (`/trips`) | Vehicle Search (`/trips/vehicle/{id}`) |
|---------|---------------------------|----------------------------------------|
| **Use Case** | Complex filtering (origin, destination, company) | Simple vehicle-specific queries |
| **Performance** | Good for complex filters | Optimized for vehicle queries |
| **Flexibility** | High (multiple filter combinations) | Medium (vehicle + status + pagination) |
| **URL Structure** | Query parameters | Path parameter + query parameters |
| **Session Support** | ✅ Full session management | ✅ Full session management |
| **Pagination** | ✅ Full pagination support | ✅ Full pagination support | 