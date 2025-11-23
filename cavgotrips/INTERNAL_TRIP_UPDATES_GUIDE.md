# Internal Trip Updates Guide

This guide documents the internal inter-service communication endpoints for trips and the automatic trip update posting system.

## Overview

The system provides internal endpoints for querying trips by company, vehicle, or driver, with an optional automatic update posting mechanism that sends trip updates to external services for a configurable duration after a query.

## Table of Contents

1. [Internal Endpoints](#internal-endpoints)
2. [Automatic Update Posting](#automatic-update-posting)
3. [Configuration](#configuration)
4. [API Reference](#api-reference)
5. [Usage Examples](#usage-examples)
6. [Timer Behavior](#timer-behavior)

---

## Internal Endpoints

### Get Company Trips

**Endpoint:** `GET /internal/trips/company/{company_id}`

**Description:** Retrieves trips for a specific company. Only returns trips from the current month (based on `created_at`). Supports pagination and optional filters.

**Path Parameters:**
- `company_id` (required): The ID of the company

**Query Parameters:**
- `driver_id` (optional): Filter trips by driver ID
- `vehicle_id` (optional): Filter trips by vehicle ID
- `from_date` (optional): Filter trips created on or after this date (format: `YYYY-MM-DD`)
- `trip_id` (optional): Return only trips updated after the specified trip ID (within the same company)
- `limit` (optional): Number of trips per page (default: 20)
- `offset` (optional): Number of trips to skip (default: 0)

**Response:**
```json
{
  "trips": [
    {
      "id": 1,
      "route_id": 10,
      "vehicle_id": 5,
      "vehicle": {
        "id": 5,
        "company_id": 123,
        "company_name": "Example Company",
        "capacity": 30,
        "license_plate": "ABC123",
        "driver": {
          "id": 10,
          "name": "John Doe",
          "phone": "0781234567"
        }
      },
      "status": "IN_PROGRESS",
      "departure_time": 1699123456,
      "completion_time": null,
      "connection_mode": "ONLINE",
      "notes": null,
      "seats": 25,
      "price": 5000.0,
      "remaining_time_to_destination": 3600,
      "remaining_distance_to_destination": 5000.5,
      "is_reversed": false,
      "current_speed": 65.5,
      "current_latitude": -1.9441,
      "current_longitude": 30.0619,
      "has_custom_waypoints": false,
      "created_at": "2024-01-15T10:30:00Z",
      "updated_at": "2024-01-15T11:45:00Z",
      "route": {
        "id": 10,
        "origin": { ... },
        "destination": { ... }
      },
      "waypoints": [ ... ]
    }
  ],
  "total": 150,
  "limit": 20,
  "offset": 0
}
```

**Note on Optional Fields:**
The following fields are optional and may be `null` if not set:
- `completion_time`: Set when trip is completed
- `notes`: Optional trip notes
- `remaining_time_to_destination`: Current estimated time to destination (seconds)
- `remaining_distance_to_destination`: Current distance to destination (meters)
- `current_speed`: Current vehicle speed (km/h) - only set for IN_PROGRESS trips
- `current_latitude`: Current vehicle latitude - only set for IN_PROGRESS trips
- `current_longitude`: Current vehicle longitude - only set for IN_PROGRESS trips

These fields are always included in the response (as `null` if not set) and are updated via the `/trips/{id}/progress` endpoint.

**Notes:**
- Results are ordered by `updated_at DESC, created_at DESC` (most recently updated first)
- When `trip_id` is provided, only trips with `updated_at > reference_trip.updated_at` are returned
- The `trip_id` filter only applies if the reference trip belongs to the same company
- All trips must be from the current month (based on `created_at`)

**Example Requests:**
```bash
# Get all trips for company 123
GET /internal/trips/company/123

# Get trips for company 123, filtered by driver 456, paginated
GET /internal/trips/company/123?driver_id=456&limit=50&offset=0

# Get trips updated after trip 789 for company 123
GET /internal/trips/company/123?trip_id=789&limit=100

# Get trips for company 123, filtered by vehicle and date range
GET /internal/trips/company/123?vehicle_id=10&from_date=2024-01-01
```

---

## Automatic Update Posting

### Overview

When a query is made to the company trips endpoint, the system can automatically POST trip updates to an external service for 10 minutes. This feature is **only enabled** if `TRIP_UPDATE_BASE_URL` is set in the environment variables.

### How It Works

1. **Query Triggers Timer**: When `/internal/trips/company/{company_id}` is called, a 10-minute timer starts for that company
2. **Timer Extension**: If another query is made within 3 minutes of the timer creation, the timer is extended to 10 minutes from the new query time
3. **Update Posting**: During the active timer period:
   - **Immediate posts** for: trip creation, status changes (SCHEDULED→IN_PROGRESS, CANCELLED, COMPLETED)
   - **Batch posts** (every 1 minute) for: other updates (distance, location, speed, etc.)
4. **Timer Expiration**: After 10 minutes of inactivity, the timer expires and no more updates are posted

### Update Types

#### Immediate Updates

These trip events trigger immediate POST requests:
- Trip creation (`CreateTrip`)
- Trip start (`StartTrip` - status changes to IN_PROGRESS)
- Trip completion (`CompleteTrip` - status changes to COMPLETED)
- Trip cancellation (`DeleteTrip` - status changes to CANCELLED)
- Status changes in `UpdateTripProgress` (to IN_PROGRESS, CANCELLED, or COMPLETED)

#### Batched Updates

These updates are queued and sent every 1 minute:
- Distance updates (`remaining_distance_to_destination`)
- Time updates (`remaining_time_to_destination`)
- Location updates (`current_latitude`, `current_longitude`)
- Speed updates (`current_speed`)
- Waypoint progress updates
- Other non-status field updates

### POST Endpoint Format

**URL Pattern:** `{TRIP_UPDATE_BASE_URL}/{company_id}/trips`

**Method:** `POST`

**Content-Type:** `application/json`

**Request Body (Single Update):**
```json
{
  "id": 1,
  "route_id": 10,
  "vehicle_id": 5,
  "status": "IN_PROGRESS",
  "updated_at": "2024-01-15T11:45:00Z",
  ...
}
```

**Request Body (Batch Update):**
```json
[
  {
    "id": 1,
    "status": "IN_PROGRESS",
    "updated_at": "2024-01-15T11:45:00Z",
    ...
  },
  {
    "id": 2,
    "remaining_distance_to_destination": 5000.5,
    "updated_at": "2024-01-15T11:46:00Z",
    ...
  }
]
```

**Response:** The external service should return a 2xx status code. Non-2xx responses are logged but don't affect trip operations.

---

## Configuration

### Environment Variables

#### `TRIP_UPDATE_BASE_URL`

**Description:** Base URL for posting trip updates. If not set, the update posting feature is completely disabled.

**Format:** Full URL without trailing slash (e.g., `https://api.example.com/trip-updates`)

**Example:**
```bash
export TRIP_UPDATE_BASE_URL="https://api.example.com/trip-updates"
```

**Behavior:**
- If set: Timer and posting features are enabled
- If not set: Timer and posting features are disabled (scheduler and poster are `nil`)

**Default:** Empty string (feature disabled)

### Logging

The system logs the following events:
- Timer creation: `[TripUpdateScheduler] Started new timer for company {id}, expires at {time}`
- Timer extension: `[TripUpdateScheduler] Extended timer for company {id}, expires at {time}`
- Timer expiration: `[TripUpdateScheduler] Timer expired and removed for company {id}`
- Successful POST: `[TripUpdatePoster] Successfully posted trip {id} update to {url}`
- Failed POST: `[TripUpdatePoster] Failed to POST trip {id} to {url}: {error}`
- Feature status: `[TripUpdate] Trip update posting enabled/disabled`

---

## API Reference

### Repository Layer

#### `GetTripsByCompanyID`

```go
func (r *tripRepository) GetTripsByCompanyID(
    companyID int64,
    driverID *int64,
    vehicleID *int64,
    fromDate *time.Time,
    afterTripID *int64,
    limit, offset int
) ([]models.Trip, int64, error)
```

**Parameters:**
- `companyID`: Company ID to filter by
- `driverID`: Optional driver ID filter
- `vehicleID`: Optional vehicle ID filter
- `fromDate`: Optional minimum creation date filter
- `afterTripID`: Optional trip ID - returns trips updated after this trip
- `limit`: Maximum number of trips to return
- `offset`: Number of trips to skip

**Returns:**
- `[]models.Trip`: Array of trips
- `int64`: Total count of matching trips
- `error`: Error if query fails

### Service Layer

#### `GetTripsByCompanyID`

```go
func (s *TripService) GetTripsByCompanyID(
    companyID int64,
    driverID *int64,
    vehicleID *int64,
    fromDate *time.Time,
    afterTripID *int64,
    limit, offset int
) ([]models.Trip, int64, error)
```

Applies standard trip formatting (clears route waypoints, adjusts reversed routes).

#### `TripUpdateScheduler`

Manages active timers per company.

**Methods:**
- `StartOrExtendTimer(companyID, baseURL, tripIDs)`: Start or extend timer
- `StopTimer(companyID)`: Stop and remove timer
- `IsTimerActive(companyID)`: Check if timer exists and is active
- `GetTimer(companyID)`: Get timer entry if active
- `AddTripID(companyID, tripID)`: Add trip ID to active timer

#### `TripUpdatePoster`

Handles HTTP POST requests to external service.

**Methods:**
- `PostTripUpdate(companyID, trip)`: POST single trip update immediately
- `PostBatchUpdates(companyID, trips)`: POST multiple trips in batch

### Handler Layer

#### `GetTripsByCompanyID`

```go
func (h *TripHandler) GetTripsByCompanyID(
    w http.ResponseWriter,
    r *http.Request
)
```

Handles HTTP request, parses parameters, calls service, and triggers timer management.

---

## Usage Examples

### Example 1: Basic Company Query

```bash
curl -X GET "http://localhost:8080/internal/trips/company/123"
```

**Response:**
```json
{
  "trips": [...],
  "total": 45,
  "limit": 20,
  "offset": 0
}
```

### Example 2: Paginated Query with Filters

```bash
curl -X GET "http://localhost:8080/internal/trips/company/123?driver_id=456&limit=50&offset=0"
```

### Example 3: Get Updates After Specific Trip

```bash
curl -X GET "http://localhost:8080/internal/trips/company/123?trip_id=789&limit=100"
```

This returns all trips for company 123 that were updated after trip 789.

### Example 4: Date Range Filter

```bash
curl -X GET "http://localhost:8080/internal/trips/company/123?from_date=2024-01-01&limit=100"
```

### Example 5: Combined Filters

```bash
curl -X GET "http://localhost:8080/internal/trips/company/123?vehicle_id=10&driver_id=456&from_date=2024-01-15&limit=25&offset=0"
```

---

## Timer Behavior

### Timer Lifecycle

1. **Creation**: Timer is created when `/internal/trips/company/{company_id}` is first called
   - Duration: 10 minutes from query time
   - Stores: company ID, base URL, set of trip IDs

2. **Extension**: If another query is made within 3 minutes of timer creation:
   - Timer is extended to 10 minutes from the new query time
   - New trip IDs are added to the tracked set

3. **Expiration**: Timer expires after 10 minutes of inactivity
   - Background goroutine checks every 30 seconds
   - Expired timers are automatically removed

### Timer Rules

- **One timer per company**: Each company has at most one active timer
- **3-minute join window**: Queries within 3 minutes extend the existing timer
- **10-minute duration**: Timer lasts 10 minutes from the last query
- **Automatic cleanup**: Expired timers are removed automatically

### Example Timeline

```
Time 0:00 - Query for company 123 → Timer created, expires at 0:10
Time 0:02 - Query for company 123 → Timer extended, expires at 0:12
Time 0:05 - Query for company 123 → Timer extended, expires at 0:15
Time 0:20 - Timer expired, no more updates posted
```

---

## Error Handling

### Invalid Parameters

- **Invalid company_id**: Returns `400 Bad Request` with error message
- **Invalid driver_id**: Returns `400 Bad Request` with error message
- **Invalid vehicle_id**: Returns `400 Bad Request` with error message
- **Invalid trip_id**: Returns `400 Bad Request` with error message
- **Invalid from_date format**: Returns `400 Bad Request` with format hint
- **Invalid limit/offset**: Returns `400 Bad Request` with error message

### Service Errors

- **Database errors**: Returns `500 Internal Server Error` with error message
- **Trip not found** (for trip_id filter): Filter is silently ignored if trip doesn't exist or belongs to different company

### POST Request Errors

- **Network errors**: Logged but don't affect trip operations
- **Non-2xx responses**: Logged but don't affect trip operations
- **Timeout**: HTTP client timeout is 10 seconds

---

## Best Practices

1. **Set baseURL only when needed**: If you don't need automatic posting, don't set `TRIP_UPDATE_BASE_URL`
2. **Use pagination**: Always use `limit` and `offset` for large result sets
3. **Use trip_id for incremental updates**: Use `trip_id` parameter to get only new/updated trips
4. **Handle POST failures gracefully**: External service should handle POST requests gracefully
5. **Monitor timer activity**: Check logs to understand timer behavior

---

## Troubleshooting

### Updates Not Being Posted

1. Check if `TRIP_UPDATE_BASE_URL` is set in environment
2. Verify timer is active (check logs for timer creation)
3. Check if trip updates are happening (status changes trigger immediate posts)
4. Verify external service is accessible and returning 2xx responses

### Timer Not Extending

- Timer only extends if query is made within 3 minutes of creation
- Check logs for timer extension messages

### Too Many POST Requests

- Batch updates are sent every 1 minute
- Consider increasing batch interval if needed
- Status changes always trigger immediate posts (by design)

---

## Related Documentation

- [Trip Creation Guide](./TRIP_CREATION_GUIDE.md)
- [API Endpoints](./API_ENDPOINTS.md)
- [Pagination Guide](./PAGINATION_GUIDE.md)

