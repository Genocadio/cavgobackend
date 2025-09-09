# RabbitMQ Message Structure Documentation

This document describes the exact structure of messages published to RabbitMQ by the Java MQTT-to-RabbitMQ bridge service.

## Queue Information

### Publisher Queue (for publishing from MQTT to RabbitMQ)
- **Queue Name**: `trips.publisher.queue`
- **Purpose**: Receives trip events from MQTT and publishes them to RabbitMQ
- **Exchange**: Direct exchange (default)
- **Message Format**: JSON with snake_case naming strategy
- **Content Type**: `application/json`

### Listener Queue (for consuming from RabbitMQ)
- **Queue Name**: `trips.queue`
- **Purpose**: Consumes trip events from RabbitMQ and forwards them to MQTT
- **Exchange**: Direct exchange (default)
- **Message Format**: JSON with snake_case naming strategy
- **Content Type**: `application/json`

## Queue Architecture

The system uses **separate queues** to prevent circular message flows and duplicates:

```
MQTT → trips.publisher.queue → [Your Go Services]
                ↓
        trips.queue → MQTT (for other services)
```

- **`trips.publisher.queue`**: Where the Java service publishes trip events from MQTT
- **`trips.queue`**: Where the Java service listens for trip events to forward to MQTT

This separation ensures:
- ✅ **No circular flows**: Publisher and listener use different queues
- ✅ **No duplicates**: Each message flows in one direction
- ✅ **Clean architecture**: Clear separation of concerns

## Message Structure

### Root Message: TripEventMessage

```json
{
  "event": "string",
  "data": {
    // Trip object (see below)
  }
}
```

### Trip Object Structure

```json
{
  "id": 123,
  "route_id": 456,
  "vehicle_id": 789,
  "vehicle": {
    // Vehicle object (see below)
  },
  "status": "IN_PROGRESS",
  "departure_time": 1640995200000,
  "completion_time": null,
  "connection_mode": "ONLINE",
  "notes": "string",
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
    // Route object (see below)
  },
  "waypoints": [
    // Array of TripWaypoint objects (see below)
  ]
}
```

### Vehicle Object Structure

```json
{
  "id": 789,
  "company_id": 1,
  "company_name": "Transport Company Ltd",
  "capacity": 50,
  "license_plate": "ABC-123",
  "driver": {
    // Driver object (see below)
  }
}
```

### Driver Object Structure

```json
{
  "name": "John Doe",
  "phone": "+1234567890"
}
```

### Route Object Structure

```json
{
  "id": 456,
  "name": "City Center to Airport",
  "distance_meters": 15000,
  "estimated_duration_seconds": 1800,
  "google_route_id": "route_123",
  "origin_id": "loc_1",
  "destination_id": "loc_2",
  "route_price": 25.50,
  "city_route": true,
  "created_at": "2023-01-01T00:00:00Z",
  "updated_at": "2023-01-01T00:00:00Z",
  "origin": {
    // Location object (see below)
  },
  "destination": {
    // Location object (see below)
  },
  "waypoints": []
}
```

### Location Object Structure

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

### TripWaypoint Object Structure

```json
{
  "id": 1,
  "trip_id": 123,
  "location_id": 2,
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

## Event Types

The `event` field can contain the following values:

- `TRIP_STARTED` - Trip has begun
- `TRIP_COMPLETED` - Trip has finished successfully
- `TRIP_CANCELLED` - Trip was cancelled
- `TRIP_UPDATED` - Trip information was updated (location, status, etc.)
- `TRIP_PROGRESS_UPDATE` - Trip progress update (location, speed, waypoints, etc.)

## Status Values

The `status` field can contain:

- `SCHEDULED` - Trip is scheduled but not started
- `IN_PROGRESS` - Trip is currently active
- `COMPLETED` - Trip finished successfully
- `NOT_COMPLETED` - Trip did not complete successfully

## Connection Mode Values

The `connection_mode` field can contain:

- `ONLINE` - Real-time connection
- `OFFLINE` - No real-time connection
- `HYBRID` - Mixed connection mode

## Go Struct Examples

### Main Message Structure

```go
type TripEventMessage struct {
    Event string `json:"event"`
    Data  Trip   `json:"data"`
}

type Trip struct {
    ID                           int            `json:"id"`
    RouteID                      int            `json:"route_id"`
    VehicleID                    int            `json:"vehicle_id"`
    Vehicle                      *Vehicle       `json:"vehicle"`
    Status                       string         `json:"status"`
    DepartureTime                *int64         `json:"departure_time"`
    CompletionTime               *int64         `json:"completion_time"`
    ConnectionMode               string         `json:"connection_mode"`
    Notes                        *string        `json:"notes"`
    Seats                        *int           `json:"seats"`
    RemainingTimeToDestination   *int64         `json:"remaining_time_to_destination"`
    RemainingDistanceToDestination *int64       `json:"remaining_distance_to_destination"`
    IsReversed                   *bool          `json:"is_reversed"`
    CurrentSpeed                 *float64       `json:"current_speed"`
    CurrentLatitude              *float64       `json:"current_latitude"`
    CurrentLongitude             *float64       `json:"current_longitude"`
    HasCustomWaypoints           *bool          `json:"has_custom_waypoints"`
    CreatedAt                    *string        `json:"created_at"`
    UpdatedAt                    *string        `json:"updated_at"`
    Route                        *Route         `json:"route"`
    Waypoints                    []TripWaypoint `json:"waypoints"`
}

type Vehicle struct {
    ID           int     `json:"id"`
    CompanyID    int     `json:"company_id"`
    CompanyName  string  `json:"company_name"`
    Capacity     int     `json:"capacity"`
    LicensePlate string  `json:"license_plate"`
    Driver       *Driver `json:"driver"`
}

type Driver struct {
    Name  string `json:"name"`
    Phone string `json:"phone"`
}

type Route struct {
    ID                      int        `json:"id"`
    Name                    *string    `json:"name"`
    DistanceMeters          *int64     `json:"distance_meters"`
    EstimatedDurationSeconds *int64    `json:"estimated_duration_seconds"`
    GoogleRouteID           *string    `json:"google_route_id"`
    OriginID                *string    `json:"origin_id"`
    DestinationID           *string    `json:"destination_id"`
    RoutePrice              *float64   `json:"route_price"`
    CityRoute               *bool      `json:"city_route"`
    CreatedAt               *string    `json:"created_at"`
    UpdatedAt               *string    `json:"updated_at"`
    Origin                  *Location  `json:"origin"`
    Destination             *Location  `json:"destination"`
    Waypoints               []interface{} `json:"waypoints"`
}

type Location struct {
    ID               int     `json:"id"`
    Latitude         float64 `json:"latitude"`
    Longitude        float64 `json:"longitude"`
    Price            *float64 `json:"price"`
    Code             *string `json:"code"`
    GooglePlaceName  *string `json:"google_place_name"`
    CustomName       *string `json:"custom_name"`
    PlaceID          *string `json:"place_id"`
    CreatedAt        *string `json:"created_at"`
    UpdatedAt        *string `json:"updated_at"`
}

type TripWaypoint struct {
    ID                int       `json:"id"`
    TripID            int       `json:"trip_id"`
    LocationID        int       `json:"location_id"`
    Order             int       `json:"order"`
    Price             *float64  `json:"price"`
    IsPassed          *bool     `json:"is_passed"`
    IsNext            *bool     `json:"is_next"`
    PassedTimestamp   *int64    `json:"passed_timestamp"`
    RemainingTime     *int64    `json:"remaining_time"`
    RemainingDistance *int64    `json:"remaining_distance"`
    IsCustom          *bool     `json:"is_custom"`
    CreatedAt         *string   `json:"created_at"`
    UpdatedAt         *string   `json:"updated_at"`
    Location          *Location `json:"location"`
}
```

## Example Complete Message

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

## Notes for Go Developers

1. **Field Naming**: All fields use snake_case naming convention
2. **Nullable Fields**: Many fields can be null, so use pointers in Go structs
3. **Timestamps**: Unix timestamps in milliseconds for time fields
4. **Coordinates**: Latitude/longitude as float64 values
5. **Enums**: Status and connection mode are string values, not numeric enums
6. **Nested Objects**: Vehicle, Route, and Waypoints are nested objects that can be null
7. **Arrays**: Waypoints is an array that can be empty

## RabbitMQ Consumer Example (Go)

```go
package main

import (
    "encoding/json"
    "log"
    "github.com/streadway/amqp"
)

func main() {
    conn, err := amqp.Dial("amqp://guest:guest@localhost:5672/")
    if err != nil {
        log.Fatal(err)
    }
    defer conn.Close()

    ch, err := conn.Channel()
    if err != nil {
        log.Fatal(err)
    }
    defer ch.Close()

    msgs, err := ch.Consume(
        "trips.publisher.queue", // queue - use publisher queue to consume messages
        "",                      // consumer
        true,                    // auto-ack
        false,                   // exclusive
        false,                   // no-local
        false,                   // no-wait
        nil,                     // args
    )
    if err != nil {
        log.Fatal(err)
    }

    for msg := range msgs {
        var tripEvent TripEventMessage
        if err := json.Unmarshal(msg.Body, &tripEvent); err != nil {
            log.Printf("Error unmarshaling message: %v", err)
            continue
        }

        log.Printf("Received trip event: %s for trip ID: %d", 
                   tripEvent.Event, tripEvent.Data.ID)
        
        // Process the trip event here
        processTripEvent(tripEvent)
    }
}

func processTripEvent(event TripEventMessage) {
    switch event.Event {
    case "TRIP_STARTED":
        log.Printf("Trip started: %d", event.Data.ID)
    case "TRIP_COMPLETED":
        log.Printf("Trip completed: %d", event.Data.ID)
    case "TRIP_CANCELLED":
        log.Printf("Trip cancelled: %d", event.Data.ID)
    case "TRIP_UPDATED":
        log.Printf("Trip updated: %d", event.Data.ID)
    case "TRIP_PROGRESS_UPDATE":
        log.Printf("Trip progress update: %d", event.Data.ID)
    default:
        log.Printf("Unknown event: %s", event.Event)
    }
}
```
