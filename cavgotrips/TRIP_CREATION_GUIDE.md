# Trip API Guide (Create + Vehicle Trips)

## Overview
This document reflects the current trip API behavior for:
- Creating trips via `POST /trips`
- Getting trips for a vehicle via `GET /trips/vehicle/{vehicle_id}`

The API supports route-based trips, custom waypoints, reversed trips, and `no_waypoints` mode.

## Create Trip

### Endpoint
```http
POST /trips
Content-Type: application/json
```

### Request Body
```json
{
  "route_id": 123,
  "vehicle_id": 456,
  "departure_time": 1767225600,
  "connection_mode": "ONLINE",
  "auto_return": false,
  "price": 2500,
  "notes": "Optional trip notes",
  "is_reversed": false,
  "custom_waypoints": [
    {
      "location_id": 789,
      "order": 1,
      "price": 1000,
      "remaining_time": 1800,
      "remaining_distance": 5000
    }
  ],
  "no_waypoints": false
}
```

### Fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `route_id` | `int64` | Yes | Must be `> 0` and route must exist |
| `vehicle_id` | `int64` | Yes | Must be `> 0`; vehicle must exist and be `AVAILABLE` |
| `departure_time` | `int64` | Yes | Unix timestamp, must be `> 0` |
| `connection_mode` | `string` | Yes | One of `ONLINE`, `OFFLINE`, `HYBRID` |
| `auto_return` | `bool` | No | Optional flag. When `true`, this trip can auto-create a reversed next trip on completion |
| `price` | `*float64` | No | If omitted, route `route_price` is used; if provided must be `> 0` |
| `notes` | `*string` | No | Optional free text |
| `is_reversed` | `bool` | No | Reverses route direction in trip response |
| `custom_waypoints` | `[]CreateCustomWaypoint` | No | Optional custom waypoint list |
| `no_waypoints` | `bool` | No | Keep only passthrough route waypoints |

### `custom_waypoints` item fields

| Field | Type | Required | Validation |
|---|---|---|---|
| `location_id` | `int64` | Yes | Must be `> 0` and location must exist |
| `order` | `int` | Yes | Must be `>= 0` |
| `price` | `*float64` | No | If provided, must be `> 0` |
| `remaining_time` | `*int64` | No | If provided, must be `>= 0` |
| `remaining_distance` | `*float64` | No | If provided, must be `>= 0` |

Passthrough price recommendation:
- For passthrough waypoints, send `price: null` (or omit `price`).
- Do not send `price: 0`.
- In API responses, passthrough waypoint price is expected to be `null`.

## Creation Logic

### Common behavior
1. Request is validated first.
2. Route is loaded from DB.
3. Vehicle snapshot is fetched from vehicle service.
4. Vehicle existence is required, but vehicle `status` is not used to block creation.
5. Multiple trips per vehicle are allowed, including incomplete history.
6. If new trip request sets `auto_return = true`, creation is blocked only when the vehicle already has another active auto-return trip (`SCHEDULED` or `IN_PROGRESS`).
7. Trip is created with:
   - `status = SCHEDULED`
   - `seats = vehicle.capacity`
   - `remaining_seats = vehicle.capacity`
   - `price = request.price` or route price fallback

### Waypoint behavior
1. If `no_waypoints = true`:
   - Only route waypoints marked `is_pass_through = true` are copied.
  - Their waypoint `price` is set to `null` (recommended behavior vs `0`).
2. Else if `custom_waypoints` provided:
   - Custom waypoints are added with `is_custom = true`.
   - Route passthrough waypoints are automatically included if missing from custom list.
   - Final trip waypoints are sorted by `order` and renumbered sequentially starting at `1`.
3. Else (default route copy):
   - Route waypoints are copied.
   - For reversed trips, waypoints are inserted in reverse order.
   - Reversed non-passthrough prices are recalculated from total trip price.

### Reversed trip response behavior
When `is_reversed = true`, response route origin/destination are swapped for client-facing consistency.

### Auto-return behavior
If `auto_return = true` on a trip and that trip reaches `COMPLETED`, the service automatically creates a new trip:
- Same `route_id`, `vehicle_id`, vehicle snapshot, connection mode, notes, and trip price.
- `is_reversed` is toggled (`true -> false` or `false -> true`).
- Waypoints are copied in exact reverse order from the completed trip.
- Reversed waypoint prices are recalculated from total trip price:
  - passthrough waypoint price stays `null`
  - non-passthrough waypoint price is recalculated as `trip_price - previous_waypoint_price`
  - minimum fallback is `0.01` when the computed value is non-positive
- Route-level origin/destination in DB are unchanged; reversal is represented by trip `is_reversed` and reversed trip waypoints.

## Create Trip Response

### Success (`201 Created`)
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
      "id": 90,
      "name": "John Doe",
      "phone": "+250123456789"
    }
  },
  "status": "SCHEDULED",
  "departure_time": 1767225600,
  "connection_mode": "ONLINE",
  "price": 2500,
  "notes": "Optional trip notes",
  "seats": 25,
  "remaining_seats": 25,
  "is_reversed": false,
  "has_custom_waypoints": true,
  "auto_return": false,
  "created_at": "2026-04-15T10:00:00Z",
  "updated_at": "2026-04-15T10:00:00Z",
  "route": {
    "id": 123,
    "origin_id": 1,
    "destination_id": 2,
    "origin": {
      "id": 1,
      "code": "110001",
      "custom_name": "Kigali Airport",
      "google_place_name": "Kigali International Airport"
    },
    "destination": {
      "id": 2,
      "code": "230001",
      "custom_name": "drift",
      "google_place_name": "Musanze, Rwanda"
    }
  },
  "waypoints": [
    {
      "id": 1,
      "trip_id": 789,
      "location_id": 789,
      "order": 1,
      "price": 1000,
      "is_pass_through": false,
      "is_passed": false,
      "is_next": false,
      "passed_timestamp": null,
      "remaining_time": 1800,
      "remaining_distance": 5000,
      "is_custom": true
    }
  ]
}
```

### Error shape
All errors use this shape:
```json
{
  "error": "error message"
}
```

Common status codes:
- `400 Bad Request`: validation/invalid input/active auto-return trip exists
- `500 Internal Server Error`: unexpected server error

## Toggle Auto-Return On Latest Vehicle Trip

### Endpoint
```http
PUT /trips/vehicle/{vehicle_id}/auto-return
Content-Type: application/json
```

### Request body
```json
{
  "auto_return": true
}
```

Behavior:
- Finds the latest trip for the vehicle (`created_at DESC`).
- Updates that trip's `auto_return` value.
- Returns the updated trip object.

## Get Trips By Vehicle

### Endpoint
```http
GET /trips/vehicle/{vehicle_id}
```

### Path parameter
- `vehicle_id` (required, int64)

### Query parameters
- `status` (optional): filter vehicle trips by exact status
- `limit` (optional, default `20`): pagination size, must be `>= 0`
- `offset` (optional, default `0`): pagination offset, must be `>= 0`
- `session_uuid` (optional): existing SSE session UUID to update

### Response (`200 OK`)
```json
{
  "trips": [
    {
      "id": 789,
      "vehicle_id": 456,
      "status": "SCHEDULED",
      "auto_return": false,
      "route": {
        "id": 123,
        "origin": { "id": 1, "custom_name": "Kigali Airport" },
        "destination": { "id": 2, "custom_name": "drift" }
      },
      "waypoints": []
    }
  ],
  "total": 1,
  "limit": 20,
  "offset": 0,
  "page": 1,
  "total_pages": 1,
  "sse_uuid": "7f5d8ef0-7cc6-4f98-9d9f-0a9e0f88f233"
}
```

Notes:
- `total` is the count before pagination.
- `sse_uuid` is included only when a new session is created.
- If `session_uuid` is passed and valid, response usually omits `sse_uuid`.
- Trips are ordered newest first (`created_at DESC`).

## Examples

### 1) Basic route trip
```bash
curl -X POST http://localhost:8080/trips \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 123,
    "vehicle_id": 456,
    "departure_time": 1767225600,
    "connection_mode": "ONLINE"
  }'
```

### 2) Custom waypoint trip
```bash
curl -X POST http://localhost:8080/trips \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 123,
    "vehicle_id": 456,
    "departure_time": 1767225600,
    "connection_mode": "HYBRID",
    "price": 3200,
    "custom_waypoints": [
      {
        "location_id": 789,
        "order": 1,
        "price": 1000
      },
      {
        "location_id": 790,
        "order": 2,
        "price": 2000
      }
    ]
  }'
```

### 3) Reversed route trip
```bash
curl -X POST http://localhost:8080/trips \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 123,
    "vehicle_id": 456,
    "departure_time": 1767225600,
    "connection_mode": "OFFLINE",
    "is_reversed": true,
    "notes": "Return trip"
  }'
```

### 4) `no_waypoints` trip (passthrough-only waypoints)
```bash
curl -X POST http://localhost:8080/trips \
  -H "Content-Type: application/json" \
  -d '{
    "route_id": 123,
    "vehicle_id": 456,
    "departure_time": 1767225600,
    "connection_mode": "ONLINE",
    "no_waypoints": true
  }'
```

### 5) Get trips for a vehicle
```bash
curl "http://localhost:8080/trips/vehicle/456?status=SCHEDULED&limit=20&offset=0"
```

### 6) Turn auto-return on for vehicle latest trip
```bash
curl -X PUT http://localhost:8080/trips/vehicle/456/auto-return \
  -H "Content-Type: application/json" \
  -d '{
    "auto_return": true
  }'
```

## Quick Reference
- Timestamps are Unix seconds.
- `distance` values are meters.
- `remaining_time` values are seconds.
- Seats are taken from vehicle capacity at creation time.
- Route waypoints are not returned inside `trip.route.waypoints` (trip uses `trip.waypoints` in responses).
- For passthrough waypoints, prefer `price = null` (not `0`).
