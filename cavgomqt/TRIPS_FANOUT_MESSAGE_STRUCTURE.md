# Trips Fanout Exchange Message Structure

This document describes the complete message structure for trip updates published to the `trips.fanout` fanout exchange.

## Exchange Information

- **Exchange Name**: `trips.fanout`
- **Exchange Type**: Fanout (routing key is ignored)
- **Message Format**: JSON with `snake_case` field naming
- **Content Type**: `application/json`

## Message Sources

Messages are published to this fanout exchange from two sources:
1. **`trips.publisher.queue`** - When trip data comes from MQTT
2. **`trips.queue`** - When trip data comes from other backend services

All services can bind their queues to this fanout exchange to receive all trip updates.

---

## Complete JSON Structure

### Full Example Message

```json
{
  "event": "TRIP_UPDATED",
  "data": {
    "id": 123,
    "route_id": 456,
    "vehicle_id": 789,
    "vehicle": {
      "id": 789,
      "company_id": 1,
      "company_name": "City Transport Ltd",
      "capacity": 50,
      "license_plate": "CT-123",
      "driver": {
        "name": "John Smith",
        "phone": "+1234567890"
      }
    },
    "status": "IN_PROGRESS",
    "departure_time": 1640995200000,
    "completion_time": null,
    "connection_mode": "ONLINE",
    "notes": "Regular service",
    "seats": 50,
    "remaining_time_to_destination": 1800,
    "remaining_distance_to_destination": 5000,
    "is_reversed": false,
    "current_speed": 45.5,
    "current_latitude": 40.7128,
    "current_longitude": -74.0060,
    "has_custom_waypoints": false,
    "created_at": "2023-01-01T00:00:00Z",
    "updated_at": "2023-01-01T12:00:00Z",
    "route": {
      "id": 456,
      "name": "Downtown to Airport",
      "distance_meters": 15000,
      "estimated_duration_seconds": 1800,
      "google_route_id": "route_456",
      "origin_id": "loc_1",
      "destination_id": "loc_2",
      "route_price": 25.50,
      "city_route": true,
      "created_at": "2023-01-01T00:00:00Z",
      "updated_at": "2023-01-01T00:00:00Z",
      "origin": {
        "id": 1,
        "latitude": 40.7589,
        "longitude": -73.9851,
        "price": 0.0,
        "code": "NYC001",
        "google_place_name": "Times Square, New York, NY, USA",
        "custom_name": "Times Square",
        "place_id": "ChIJmQJIxlVYwokRLgeuocVOGVU",
        "created_at": "2023-01-01T00:00:00Z",
        "updated_at": "2023-01-01T00:00:00Z"
      },
      "destination": {
        "id": 2,
        "latitude": 40.6413,
        "longitude": -73.7781,
        "price": 0.0,
        "code": "JFK001",
        "google_place_name": "John F. Kennedy International Airport, Queens, NY, USA",
        "custom_name": "JFK Airport",
        "place_id": "ChIJJ3SpfQsxlw0R80bDlfUtGqE",
        "created_at": "2023-01-01T00:00:00Z",
        "updated_at": "2023-01-01T00:00:00Z"
      },
      "waypoints": []
    },
    "waypoints": [
      {
        "id": 1,
        "trip_id": 123,
        "location_id": 3,
        "order": 1,
        "price": 5.0,
        "is_passed": false,
        "is_next": true,
        "passed_timestamp": null,
        "remaining_time": 300,
        "remaining_distance": 2000,
        "is_custom": false,
        "created_at": "2023-01-01T00:00:00Z",
        "updated_at": "2023-01-01T00:00:00Z",
        "location": {
          "id": 3,
          "latitude": 40.7505,
          "longitude": -73.9934,
          "price": 5.0,
          "code": "MID001",
          "google_place_name": "Madison Square Garden, New York, NY, USA",
          "custom_name": "MSG",
          "place_id": "ChIJ4zGFAZpYwokRGUGph3Mf37k",
          "created_at": "2023-01-01T00:00:00Z",
          "updated_at": "2023-01-01T00:00:00Z"
        }
      }
    ]
  }
}
```

---

## Field Reference

### Root Level

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `event` | String | ✅ | Event type (see Event Types below) |
| `data` | Object | ✅ | Trip data object |

### Trip Object (`data`)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | Integer | ✅ | Trip ID |
| `route_id` | Integer | ❌ | Route ID |
| `vehicle_id` | Integer | ❌ | Vehicle ID |
| `vehicle` | Object | ❌ | Vehicle object (see Vehicle Structure) |
| `status` | String | ❌ | Trip status (see Status Values) |
| `departure_time` | Long | ❌ | Unix timestamp in milliseconds |
| `completion_time` | Long | ❌ | Unix timestamp in milliseconds |
| `connection_mode` | String | ❌ | Connection mode (see Connection Mode Values) |
| `notes` | String | ❌ | Trip notes |
| `seats` | Integer | ❌ | Number of seats |
| `remaining_time_to_destination` | Long | ❌ | Remaining time in seconds |
| `remaining_distance_to_destination` | Long | ❌ | Remaining distance in meters |
| `is_reversed` | Boolean | ❌ | Whether trip is reversed |
| `current_speed` | Double | ❌ | Current speed in km/h |
| `current_latitude` | Double | ❌ | Current GPS latitude |
| `current_longitude` | Double | ❌ | Current GPS longitude |
| `has_custom_waypoints` | Boolean | ❌ | Whether trip has custom waypoints |
| `created_at` | String | ❌ | ISO 8601 timestamp |
| `updated_at` | String | ❌ | ISO 8601 timestamp |
| `route` | Object | ❌ | Route object (see Route Structure) |
| `waypoints` | Array | ❌ | Array of TripWaypoint objects |

### Vehicle Object

```json
{
  "id": 789,
  "company_id": 1,
  "company_name": "Transport Company Ltd",
  "capacity": 50,
  "license_plate": "ABC-123",
  "driver": {
    "name": "John Doe",
    "phone": "+1234567890"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | Integer | ❌ | Vehicle ID |
| `company_id` | Integer | ❌ | Company ID |
| `company_name` | String | ❌ | Company name |
| `capacity` | Integer | ❌ | Vehicle capacity |
| `license_plate` | String | ❌ | License plate number |
| `driver` | Object | ❌ | Driver object (see Driver Structure) |

### Driver Object

```json
{
  "name": "John Doe",
  "phone": "+1234567890"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | String | ❌ | Driver name |
| `phone` | String | ❌ | Driver phone number |

### Route Object

```json
{
  "id": 456,
  "name": "Downtown to Airport",
  "distance_meters": 15000,
  "estimated_duration_seconds": 1800,
  "google_route_id": "route_456",
  "origin_id": "loc_1",
  "destination_id": "loc_2",
  "route_price": 25.50,
  "city_route": true,
  "created_at": "2023-01-01T00:00:00Z",
  "updated_at": "2023-01-01T00:00:00Z",
  "origin": {
    // Location object
  },
  "destination": {
    // Location object
  },
  "waypoints": []
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | Integer | ❌ | Route ID |
| `name` | String | ❌ | Route name |
| `distance_meters` | Long | ❌ | Distance in meters |
| `estimated_duration_seconds` | Long | ❌ | Estimated duration in seconds |
| `google_route_id` | String | ❌ | Google route ID |
| `origin_id` | String | ❌ | Origin location ID |
| `destination_id` | String | ❌ | Destination location ID |
| `route_price` | Double | ❌ | Route price |
| `city_route` | Boolean | ❌ | Whether it's a city route |
| `created_at` | String | ❌ | ISO 8601 timestamp |
| `updated_at` | String | ❌ | ISO 8601 timestamp |
| `origin` | Object | ❌ | Location object (see Location Structure) |
| `destination` | Object | ❌ | Location object (see Location Structure) |
| `waypoints` | Array | ❌ | Array of waypoint objects |

### Location Object

```json
{
  "id": 1,
  "latitude": 40.7128,
  "longitude": -74.0060,
  "price": 0.0,
  "code": "NYC001",
  "google_place_name": "Times Square, New York, NY, USA",
  "custom_name": "Times Square",
  "place_id": "ChIJmQJIxlVYwokRLgeuocVOGVU",
  "created_at": "2023-01-01T00:00:00Z",
  "updated_at": "2023-01-01T00:00:00Z"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | Integer | ❌ | Location ID |
| `latitude` | Double | ❌ | GPS latitude |
| `longitude` | Double | ❌ | GPS longitude |
| `price` | Double | ❌ | Location price |
| `code` | String | ❌ | Location code |
| `google_place_name` | String | ❌ | Google place name |
| `custom_name` | String | ❌ | Custom location name |
| `place_id` | String | ❌ | Google place ID |
| `created_at` | String | ❌ | ISO 8601 timestamp |
| `updated_at` | String | ❌ | ISO 8601 timestamp |

### TripWaypoint Object

```json
{
  "id": 1,
  "trip_id": 123,
  "location_id": 3,
  "order": 1,
  "price": 5.0,
  "is_passed": false,
  "is_next": true,
  "passed_timestamp": null,
  "remaining_time": 300,
  "remaining_distance": 2000,
  "is_custom": false,
  "created_at": "2023-01-01T00:00:00Z",
  "updated_at": "2023-01-01T00:00:00Z",
  "location": {
    // Location object (see above)
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | Integer | ❌ | Waypoint ID |
| `trip_id` | Integer | ❌ | Trip ID |
| `location_id` | Integer | ❌ | Location ID |
| `order` | Integer | ❌ | Waypoint order |
| `price` | Double | ❌ | Waypoint price |
| `is_passed` | Boolean | ❌ | Whether waypoint has been passed |
| `is_next` | Boolean | ❌ | Whether this is the next waypoint |
| `passed_timestamp` | Long | ❌ | Unix timestamp when passed (milliseconds) |
| `remaining_time` | Long | ❌ | Remaining time to waypoint (seconds) |
| `remaining_distance` | Long | ❌ | Remaining distance to waypoint (meters) |
| `is_custom` | Boolean | ❌ | Whether this is a custom waypoint |
| `created_at` | String | ❌ | ISO 8601 timestamp |
| `updated_at` | String | ❌ | ISO 8601 timestamp |
| `location` | Object | ❌ | Location object (see Location Structure) |

---

## Event Types

The `event` field can contain the following values:

- **`TRIP_STARTED`** - Trip has begun
- **`TRIP_COMPLETED`** - Trip has finished successfully
- **`TRIP_CANCELLED`** - Trip was cancelled
- **`TRIP_UPDATED`** - Trip information was updated (location, status, etc.)
- **`TRIP_PROGRESS_UPDATE`** - Trip progress update (location, speed, waypoints, etc.)
- **`progress_update`** - Alternative name for progress update

---

## Status Values

The `status` field in the trip data can contain:

- **`SCHEDULED`** - Trip is scheduled but not started
- **`IN_PROGRESS`** - Trip is currently active
- **`COMPLETED`** - Trip finished successfully
- **`NOT_COMPLETED`** - Trip did not complete successfully
- **`CANCELLED`** - Trip was cancelled

---

## Connection Mode Values

The `connection_mode` field can contain:

- **`ONLINE`** - Real-time connection
- **`OFFLINE`** - No real-time connection
- **`HYBRID`** - Mixed connection mode

---

## Important Notes

1. **Field Naming**: All fields use `snake_case` naming convention (e.g., `route_id`, `vehicle_id`, `current_latitude`)

2. **Nullable Fields**: Many fields can be `null` - always check for null values before using them

3. **Timestamps**: 
   - Time fields (`departure_time`, `completion_time`, `passed_timestamp`) use Unix timestamps in milliseconds
   - Date strings (`created_at`, `updated_at`) use ISO 8601 format

4. **Coordinates**: Latitude and longitude are `Double` values

5. **Enums**: Status and connection mode are string values, not numeric enums

6. **Nested Objects**: `vehicle`, `route`, and `waypoints` can be `null` or empty arrays

7. **Arrays**: `waypoints` is an array that can be empty `[]`

---

## Example Consumer Setup

### RabbitMQ Queue Binding

Bind your queue to the fanout exchange:

```bash
# Exchange: trips.fanout
# Routing Key: "" (ignored for fanout exchanges)
# Queue: your-service-trips-queue
```

### Go Example

```go
type TripEventMessage struct {
    Event string `json:"event"`
    Data  Trip   `json:"data"`
}

type Trip struct {
    ID                           int            `json:"id"`
    RouteID                      *int           `json:"route_id"`
    VehicleID                    *int           `json:"vehicle_id"`
    Vehicle                      *Vehicle       `json:"vehicle"`
    Status                       *string        `json:"status"`
    DepartureTime                *int64         `json:"departure_time"`
    CompletionTime               *int64         `json:"completion_time"`
    ConnectionMode               *string        `json:"connection_mode"`
    Notes                        *string        `json:"notes"`
    Seats                        *int           `json:"seats"`
    RemainingTimeToDestination   *int64         `json:"remaining_time_to_destination"`
    RemainingDistanceToDestination *int64       `json:"remaining_distance_to_destination"`
    IsReversed                   *bool         `json:"is_reversed"`
    CurrentSpeed                 *float64       `json:"current_speed"`
    CurrentLatitude              *float64       `json:"current_latitude"`
    CurrentLongitude             *float64       `json:"current_longitude"`
    HasCustomWaypoints           *bool          `json:"has_custom_waypoints"`
    CreatedAt                    *string        `json:"created_at"`
    UpdatedAt                    *string        `json:"updated_at"`
    Route                        *Route         `json:"route"`
    Waypoints                    []TripWaypoint `json:"waypoints"`
}
```

---

## Minimal Example (Status Update Only)

```json
{
  "event": "TRIP_PROGRESS_UPDATE",
  "data": {
    "id": 123,
    "current_latitude": 40.7128,
    "current_longitude": -74.0060,
    "current_speed": 45.5,
    "status": "IN_PROGRESS"
  }
}
```

---

## Summary

- **Exchange**: `trips.fanout` (FanoutExchange)
- **Message Format**: JSON with `snake_case` fields
- **Root Structure**: `{ "event": string, "data": Trip }`
- **All Fields**: Can be null except `id` in trip data
- **Event Types**: TRIP_STARTED, TRIP_COMPLETED, TRIP_CANCELLED, TRIP_UPDATED, TRIP_PROGRESS_UPDATE
- **Status Values**: SCHEDULED, IN_PROGRESS, COMPLETED, NOT_COMPLETED, CANCELLED

