# API Endpoints Reference

Complete list of all API endpoints supported by the CavGo Trips service.

## Health & Discovery

### GET `/health`
Health check endpoint for service discovery.
**Response:**
```json
{
  "status": "UP",
  "service": "cavgotrips"
}
```

### GET `/`
Root endpoint with service information.
**Response:**
```json
{
  "service": "cavgotrips",
  "version": "1.0.0",
  "endpoints": {
    "health": "/health",
    "locations": "/locations",
    "routes": "/routes",
    "trips": "/trips"
  }
}
```

---

## Location Endpoints

### POST `/locations`
Create a new location.
**Request Body:** Location object
**Response:** Created location (HTTP 201)

### GET `/locations`
Get all locations with optional search and pagination.
**Query Parameters:**
- `search` (optional) - Search term for location name
- `page` (optional, default: 1) - Page number
- `limit` (optional, default: 20, max: 100) - Items per page

### GET `/locations/{id}`
Get a specific location by ID.
**Response:** Location object (HTTP 200) or 404 if not found

### PUT `/locations/{id}`
Update a location by ID.
**Request Body:** Location object with updated fields
**Response:** Updated location (HTTP 200)

### DELETE `/locations/{id}`
Delete a location by ID.
**Response:** Success message (HTTP 200)

---

## Route Endpoints

### POST `/routes`
Create a new route.
**Request Body:** Route object
**Response:** Created route (HTTP 201)

### GET `/routes`
Get all routes with optional filters and pagination.
**Query Parameters:**
- `origin` (optional) - Filter by origin location name
- `destination` (optional) - Filter by destination location name
- `city_route` (optional) - Filter by city route (true/false)
- `origin_province` (optional) - Filter by origin province
- `destination_province` (optional) - Filter by destination province
- `page` (optional, default: 1) - Page number
- `limit` (optional, default: 20, max: 100) - Items per page

### GET `/routes/{id}`
Get a specific route by ID.
**Response:** Route object (HTTP 200) or 404 if not found

### PUT `/routes/{id}`
Update a route by ID.
**Request Body:** Route object with updated fields
**Response:** Updated route (HTTP 200)

### DELETE `/routes/{id}`
Delete a route by ID.
**Response:** Success message (HTTP 200)

### GET `/routes/price-range`
Get routes by price range.
**Query Parameters:**
- `min_price` (optional) - Minimum price
- `max_price` (optional) - Maximum price

### GET `/routes/distance-range`
Get routes by distance range.
**Query Parameters:**
- `min_distance` (optional) - Minimum distance in meters
- `max_distance` (optional) - Maximum distance in meters

### GET `/routes/statistics`
Get route statistics.
**Response:** Statistics object

---

## Trip Endpoints

### POST `/trips`
Create a new trip.
**Request Body:** CreateTripRequest object
**Response:** Created trip (HTTP 201)

### GET `/trips`
Get trips with optional filters and pagination.
**Query Parameters:**
- `status` (optional) - Filter by status
- `vehicle_id` (optional) - Filter by vehicle ID
- `origin` (optional) - Filter by origin
- `destination` (optional) - Filter by destination
- `company` (optional) - Filter by company
- `city_route` (optional) - Filter by city route
- `session_uuid` (optional) - SSE session UUID
- `limit` (optional, default: 20) - Items per page
- `offset` (optional, default: 0) - Offset for pagination

### GET `/trips/{id}`
Get a specific trip by ID.
**Response:** Trip object (HTTP 200) or 404 if not found

### DELETE `/trips/{id}`
Delete/cancel a trip by ID.
**Response:** Success message (HTTP 200)

### PUT `/trips/{id}/progress`
Update trip progress.
**Request Body:** TripProgressUpdate object
**Response:** Updated trip (HTTP 200)

### GET `/trips/{id}/progress`
Get trip progress.
**Response:** Trip object with progress (HTTP 200)

### GET `/trips/vehicle/{vehicle_id}`
Get all trips for a specific vehicle.
**Query Parameters:**
- `status` (optional) - Filter by status
- `limit` (optional, default: 20) - Items per page
- `offset` (optional, default: 0) - Offset for pagination
- `session_uuid` (optional) - SSE session UUID

### GET `/trips/driver/{driver_id}`
Get all trips for a specific driver.
**Query Parameters:**
- `status` (optional) - Filter by status
- `limit` (optional, default: 20) - Items per page
- `offset` (optional, default: 0) - Offset for pagination
- `session_uuid` (optional) - SSE session UUID
**Response:** DriverTripsResponse with trips and metrics

---

## Internal Endpoints (Inter-Service Communication)

These endpoints are designed for internal inter-service communication and may trigger automatic update posting.

### GET `/internal/trips/company/{company_id}` ⭐ NEW
Get trips for a specific company (internal endpoint).
**Path Parameter:**
- `company_id` (required) - Company ID

**Query Parameters:**
- `driver_id` (optional) - Filter by driver ID
- `vehicle_id` (optional) - Filter by vehicle ID
- `from_date` (optional) - Filter trips created on or after this date (format: `YYYY-MM-DD`)
- `trip_id` (optional) - Return only trips updated after the specified trip ID
- `limit` (optional, default: 20) - Items per page
- `offset` (optional, default: 0) - Offset for pagination

**Response:** Paginated response with trips
```json
{
  "trips": [...],
  "total": 150,
  "limit": 20,
  "offset": 0
}
```

**Notes:**
- Only returns trips from the current month (based on `created_at`)
- Results ordered by `updated_at DESC, created_at DESC`
- When `trip_id` is provided, returns trips with `updated_at > reference_trip.updated_at`
- Triggers 10-minute update posting timer if `TRIP_UPDATE_BASE_URL` is configured
- See [Internal Trip Updates Guide](./INTERNAL_TRIP_UPDATES_GUIDE.md) for detailed documentation

**Example:**
```bash
GET /internal/trips/company/123?driver_id=456&limit=50&offset=0
GET /internal/trips/company/123?trip_id=789&limit=100
```

---

## SSE (Server-Sent Events) Endpoints

### GET `/events/{uuid}`
Subscribe to SSE events for a session.
**Path Parameter:** `uuid` - Session UUID
**Response:** SSE stream

### POST `/events/{uuid}`
Update SSE session.
**Path Parameter:** `uuid` - Session UUID
**Request Body:** Session update data

### PUT `/events/session/subscription`
Update SSE session subscription.

### GET `/events/status`
Get SSE service status.
**Response:** Status information

### GET `/events/debug/{uuid}`
Get debug information for a session.
**Path Parameter:** `uuid` - Session UUID

---

## Change Tracking / Sync Endpoints (NEW)

### GET `/main-hash`
Get the latest main hash.
**Response:**
```json
{
  "id": 1,
  "hash": "d97fae1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
  "location_ids": [1, 2, 3],
  "route_ids": [10, 15, 20],
  "included_batches": [1, 2, 3],
  "created_at": "2025-01-15T10:30:00Z",
  "type": "auto"
}
```

### POST `/merge`
Manually trigger merge operation.
**Response:**
```json
{
  "message": "Merge completed successfully"
}
```

### GET `/changes/unmerged`
Get unmerged change batches (debug endpoint).
**Response:** List of unmerged batches
**Note:** Currently returns "not yet implemented"

### GET `/routes/hash` ⭐ NEW
Hash-based route sync with pagination.
**Query Parameters:**
- `hash` (optional) - Your current hash. If not provided, returns all routes (first-time sync)
- `page` (optional, default: 1) - Page number
- `limit` (optional, default: 20, max: 100) - Items per page

**Behavior:**
- **Without hash**: Returns all routes with latest hash (for new devices/first-time sync)
- **With valid hash**: Returns only changed routes since that hash
- **With invalid hash**: Returns empty data with message (HTTP 200)

**Response when no hash (first-time sync):**
```json
{
  "hash": "latest_hash_here",
  "changed": true,
  "routes": [...all routes...],
  "deleted_ids": [],
  "page": 1,
  "limit": 20,
  "total": 100
}
```

**Response when hash matches (no changes):**
```json
{
  "hash": "your_hash",
  "changed": false,
  "routes": [],
  "deleted_ids": []
}
```

**Response when hash doesn't match (has changes):**
```json
{
  "hash": "new_hash_after_merge",
  "changed": true,
  "routes": [...changed routes only...],
  "deleted_ids": [15, 20],
  "page": 1,
  "limit": 20,
  "total": 5
}
```

**Response when invalid hash:**
```json
{
  "hash": "current_latest_hash",
  "changed": false,
  "routes": [],
  "deleted_ids": [],
  "message": "Invalid hash: hash not found in database. Please sync without hash parameter to get all data."
}
```

### GET `/locations/hash` ⭐ NEW
Hash-based location sync with pagination.
**Query Parameters:**
- `hash` (optional) - Your current hash. If not provided, returns all locations (first-time sync)
- `page` (optional, default: 1) - Page number
- `limit` (optional, default: 20, max: 100) - Items per page

**Behavior:** Same as `/routes/hash` but for locations.

**Response Format:** Same structure as routes, but with `locations` array instead of `routes`.

---

## Summary

### Existing Endpoints (Before Change Tracking)
- Location CRUD: `/locations`, `/locations/{id}`
- Route CRUD: `/routes`, `/routes/{id}`
- Route filters: `/routes/price-range`, `/routes/distance-range`, `/routes/statistics`
- Trip CRUD: `/trips`, `/trips/{id}`
- Trip operations: `/trips/{id}/progress`, `/trips/vehicle/{vehicle_id}`, `/trips/driver/{driver_id}`
- SSE: `/events/{uuid}`, `/events/status`, `/events/debug/{uuid}`

### New Endpoints (Change Tracking System)
- `GET /main-hash` - Get latest main hash
- `POST /merge` - Manually trigger merge
- `GET /changes/unmerged` - Get unmerged batches (debug)
- `GET /routes/hash` - Hash-based route sync with pagination
- `GET /locations/hash` - Hash-based location sync with pagination

### New Endpoints (Internal Inter-Service Communication)
- `GET /internal/trips/company/{company_id}` - Get company trips with automatic update posting

---

## Route Priority Note

Routes are registered in a specific order to avoid conflicts:
1. Specific routes (e.g., `/routes/price-range`) are registered first
2. Generic routes with path variables (e.g., `/routes/{id}`) are registered last

This ensures `/routes/hash` is matched correctly and not interpreted as `/routes/{id}` with id="hash".

