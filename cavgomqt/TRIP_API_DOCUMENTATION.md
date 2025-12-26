# Navigation API Documentation

## Overview

The Navigation API allows you to create multi-waypoint trips, track vehicle progress in real-time, and receive navigation updates. This document explains how to create trips, send GPS updates, and understand the trip lifecycle.

---

## Table of Contents

1. [Creating a Trip](#creating-a-trip)
2. [What Happens When Creating a Trip](#what-happens-when-creating-a-trip)
3. [Sending GPS Updates](#sending-gps-updates)
4. [Trip Lifecycle and States](#trip-lifecycle-and-states)
5. [Waypoint Progress Tracking](#waypoint-progress-tracking)
6. [Trip Completion](#trip-completion)
7. [API Endpoints Reference](#api-endpoints-reference)

---

## Creating a Trip

### Endpoint

```
POST /api/trips
Content-Type: application/json
```

### Request Body

```json
{
  "id": 123,                    // Optional: Trip ID (auto-generated if not provided)
  "carId": "vehicle-001",       // Required: Unique identifier for the vehicle
  "waypoints": [                // Required: List of waypoints (minimum 2)
    {
      "id": "wp-001",           // Optional: Waypoint ID
      "name": "Home",            // Optional: Waypoint name
      "latitude": 49.390674,     // Required: Latitude
      "longitude": 9.082976      // Required: Longitude
    },
    {
      "id": "wp-002",
      "name": "Office",
      "latitude": 49.37816,
      "longitude": 9.088095
    },
    {
      "latitude": 49.368903,     // id and name are optional
      "longitude": 9.108073
    }
  ],
  "includeInstructions": false,  // Optional: Include turn-by-turn instructions (default: false)
  "includeOrigin": false,       // Optional: Track origin as a waypoint (default: false)
  "isCityTrip": false           // Optional: City trip mode (affects off-route detection, default: false)
}
```

### Request Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | Long | No | Trip ID. If not provided, will be auto-generated |
| `carId` | String | Yes | Unique identifier for the vehicle |
| `waypoints` | Array | Yes | List of waypoints (minimum 2). Each waypoint must have `latitude` and `longitude`. `id` and `name` are optional |
| `includeInstructions` | Boolean | No | Include turn-by-turn navigation instructions in response (default: `false`) |
| `includeOrigin` | Boolean | No | If `false`, the first waypoint (origin) is not tracked for progress. If `true`, all waypoints including origin are tracked (default: `false`) |
| `isCityTrip` | Boolean | No | City trip mode affects off-route detection thresholds (default: `false`) |

### Response (201 Created)

```json
{
  "trip": {
    "id": 123,
    "carId": "vehicle-001",
    "status": "CREATED",
    "waypoints": [
      {
        "id": "wp-001",
        "name": "Home",
        "latitude": 49.390674,
        "longitude": 9.082976
      },
      {
        "id": "wp-002",
        "name": "Office",
        "latitude": 49.37816,
        "longitude": 9.088095
      },
      {
        "id": null,
        "name": null,
        "latitude": 49.368903,
        "longitude": 9.108073
      }
    ],
    "waypointProgresses": [
      {
        "waypointIndex": 0,
        "waypointId": "wp-002",
        "waypointName": "Office",
        "latitude": 49.37816,
        "longitude": 9.088095,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 1500.5,
        "remainingTime": 120.3
      },
      {
        "waypointIndex": 1,
        "waypointId": null,
        "waypointName": null,
        "latitude": 49.368903,
        "longitude": 9.108073,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 3500.2,
        "remainingTime": 280.1
      }
    ],
    "includeOrigin": false,
    "isCityTrip": false,
    "createdAt": "2025-12-19T10:00:00Z",
    "completedAt": null,
    "route": null,              // Only included if render=true
    "instructions": null        // Only included if render=true and includeInstructions=true
  },
  "currentLocation": null,      // Will be populated after first GPS update
  "instructions": null          // Only included if includeInstructions=true
}
```

### Important Notes

- **Minimum Waypoints**: You must provide at least 2 waypoints
- **includeOrigin Behavior**: 
  - If `includeOrigin=false` (default): The first waypoint is the origin where the vehicle starts. It is **not tracked** for progress. Only waypoints 2, 3, 4... are tracked.
  - If `includeOrigin=true`: All waypoints including the origin are tracked for progress
- **Auto-Cancellation**: If a new trip is created for the same `carId` while another trip is `ACTIVE` or `CREATED`, the old trip will be automatically cancelled

---

## What Happens When Creating a Trip

When you create a trip, the following happens internally:

1. **Route Calculation**: 
   - The system calls OSRM (Open Source Routing Machine) to calculate the optimal route through all waypoints
   - The route includes a polyline (list of coordinates), distances, durations, and leg information

2. **Trip Entity Creation**:
   - A new `Trip` entity is created with status `CREATED`
   - The route and waypoints are stored as JSON in the database
   - If an `id` is provided, it's used; otherwise, an ID is auto-generated

3. **Navigation State Initialization**:
   - Navigation state is initialized in Redis for the vehicle
   - The state includes:
     - `lastSnappedIndex`: Starting position on the route (0)
     - `distanceTravelled`: 0 meters
     - `currentLegIndex`: 0 (first waypoint to reach)
     - `waypointStatesJson`: All waypoints start in `APPROACHING` state
     - `tripId`: Links the state to this specific trip

4. **Auto-Cancellation**:
   - Any existing `ACTIVE` or `CREATED` trips for the same `carId` are cancelled
   - Their navigation state in Redis is cleared

5. **Response Generation**:
   - Waypoint progress is calculated based on initial route
   - Remaining distances and times are computed
   - If `includeInstructions=true`, turn-by-turn instructions are fetched and included

---

## Sending GPS Updates

### Single GPS Update

#### Endpoint

```
POST /api/gps
Content-Type: application/json
```

#### Request Body
The endpoint **always** expects a JSON Array `[]`, even for a single update.

```json
[
  {
    "carId": "vehicle-001",
    "latitude": 49.390674,
    "longitude": 9.082976,
    "speed": 16.5,              // m/s (required)
    "heading": 45.0,            // degrees (optional, nullable)
    "accuracy": 10.0,           // meters (optional, nullable)
    "timestamp": "2025-12-19T10:00:00Z"  // ISO 8601 format (required)
  }
]
```

#### Request Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `carId` | String | Yes | Vehicle identifier (must match an active trip) |
| `latitude` | Double | Yes | GPS latitude |
| `longitude` | Double | Yes | GPS longitude |
| `speed` | Double | Yes | Speed in meters per second (m/s) |
| `heading` | Double | No | Heading/bearing in degrees (0-360) |
| `accuracy` | Double | No | GPS accuracy in meters |
| `timestamp` | Instant | Yes | ISO 8601 timestamp. Must be recent (within 30 seconds by default, configurable) |

### Batch GPS Updates

You can send multiple GPS updates in a single request for better performance:

#### Endpoint

```
POST /api/gps
Content-Type: application/json
```

#### Request Body (Array)

```json
[
  {
    "carId": "vehicle-001",
    "latitude": 49.390674,
    "longitude": 9.082976,
    "speed": 16.5,
    "heading": 45.0,
    "timestamp": "2025-12-19T10:00:00Z"
  },
  {
    "carId": "vehicle-001",
    "latitude": 49.390700,
    "longitude": 9.083000,
    "speed": 17.2,
    "heading": 46.0,
    "timestamp": "2025-12-19T10:00:01Z"
  },
  {
    "carId": "vehicle-001",
    "latitude": 49.390750,
    "longitude": 9.083050,
    "speed": 18.0,
    "heading": 47.0,
    "timestamp": "2025-12-19T10:00:02Z"
  }
]
```

**Important**: 
- All updates in a batch must have the same `carId`
- Updates are processed in chronological order (sorted by timestamp)
- Each update must have a valid, recent timestamp

### GPS Update Response (200 OK)

```json
{
  "trip": {
    "id": 123,
    "carId": "vehicle-001",
    "status": "ACTIVE",
    "waypoints": [...],
    "waypointProgresses": [
      {
        "waypointIndex": 0,
        "waypointId": "wp-002",
        "waypointName": "Office",
        "latitude": 49.37816,
        "longitude": 9.088095,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 1450.2,
        "remainingTime": 115.8
      },
      {
        "waypointIndex": 1,
        "waypointId": null,
        "waypointName": null,
        "latitude": 49.368903,
        "longitude": 9.108073,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 3420.5,
        "remainingTime": 275.3
      }
    ],
    "includeOrigin": false,
    "isCityTrip": false,
    "createdAt": "2025-12-19T10:00:00Z",
    "completedAt": null
  },
  "currentLocation": {
    "carId": "vehicle-001",
    "latitude": 49.390750,        // Map-matched (snapped) latitude
    "longitude": 9.083050,        // Map-matched (snapped) longitude
    "speed": 18.0,                // m/s
    "heading": 47.0,              // degrees
    "timestamp": "2025-12-19T10:00:02Z"
  },
  "instructions": null
}
```

### What Happens When Sending GPS Updates

1. **Trip Activation**:
   - If the trip status is `CREATED`, it's automatically changed to `ACTIVE`

2. **GPS Validation**:
   - Timestamp is checked (must be recent, within 30 seconds by default)
   - Speed is validated (must be >= 0.5 m/s)
   - The update must be for an active trip

3. **Map Matching (Snapping)**:
   - Raw GPS coordinates are "snapped" to the nearest point on the route
   - This ensures the vehicle position is always on the route, even if GPS is slightly off
   - The `currentLocation` in the response contains the **snapped coordinates**, not the raw GPS

4. **Distance Calculation**:
   - Distance travelled along the route is calculated
   - Remaining distance to each waypoint is updated

5. **Waypoint Progress Update**:
   - System checks if vehicle has arrived at or passed waypoints
   - Waypoint states are updated: `APPROACHING` → `ARRIVED` → `DONE`
   - Progress is **monotonic**: once a waypoint is `DONE`, it stays `DONE`

6. **Off-Route Detection**:
   - If the vehicle deviates significantly from the route, off-route detection triggers
   - After consecutive off-route updates (2-3 depending on city/non-city mode), rerouting occurs

7. **Rerouting (if needed)**:
   - A new route is calculated from current position to remaining waypoints
   - Waypoint progress continues to track against **original waypoints**, ensuring consistency

8. **State Persistence**:
   - Navigation state is saved to Redis (real-time)
   - Snapshots are saved to database periodically (every 10 seconds) and on state changes
   - Current location is persisted in Redis and database

---

## Trip Lifecycle and States

### Trip Status Flow

```
CREATED → ACTIVE → COMPLETED
   ↓
CANCELLED
```

#### Trip Statuses

| Status | Description |
|--------|-------------|
| `CREATED` | Trip has been created but no GPS updates received yet |
| `ACTIVE` | Trip is active and receiving GPS updates |
| `COMPLETED` | All waypoints have been reached (final waypoint is ARRIVED or DONE) |
| `CANCELLED` | Trip was cancelled (e.g., new trip created for same car) |

### Status Transitions

- **CREATED → ACTIVE**: Automatically when first GPS update is received
- **ACTIVE → COMPLETED**: When final waypoint reaches `ARRIVED` or `DONE` state
- **Any → CANCELLED**: When a new trip is created for the same `carId`

---

## Waypoint Progress Tracking

### Waypoint States

Each waypoint progresses through these states:

1. **APPROACHING**: Vehicle is approaching the waypoint (default initial state)
2. **ARRIVED**: Vehicle has arrived at the waypoint (within arrival radius, typically 20 meters)
3. **DONE**: Vehicle has passed the waypoint (beyond pass threshold, typically 10 meters past arrival)

### State Transitions

```
APPROACHING → ARRIVED → DONE
```

**Important**: Progress is **monotonic** - states only advance forward:
- Once `DONE`, a waypoint stays `DONE`
- Once `ARRIVED`, a waypoint can only become `DONE` (never goes back to `APPROACHING`)

### Waypoint Progress Fields

| Field | Type | Description |
|-------|------|-------------|
| `waypointIndex` | Integer | 0-based index of the waypoint in the original trip |
| `waypointId` | String (nullable) | Optional ID of the waypoint |
| `waypointName` | String (nullable) | Optional name of the waypoint |
| `latitude` | Double | Waypoint latitude |
| `longitude` | Double | Waypoint longitude |
| `state` | String | Current state: `APPROACHING`, `ARRIVED`, or `DONE` |
| `arrivedAt` | Instant (nullable) | Timestamp when waypoint was arrived at (null if not arrived) |
| `remainingDistance` | Double | Remaining distance to waypoint in meters |
| `remainingTime` | Double | Estimated time to waypoint in seconds |

### includeOrigin Behavior

**If `includeOrigin=false` (default)**:
- Waypoint at index 0 (origin) is **not tracked** for progress
- Only waypoints at indices 1, 2, 3... are tracked
- Example: For 3 waypoints, only 2 are tracked (indices 1 and 2)

**If `includeOrigin=true`**:
- All waypoints including index 0 (origin) are tracked
- Example: For 3 waypoints, all 3 are tracked (indices 0, 1, and 2)

---

## Trip Completion

### Completion Criteria

A trip is marked as `COMPLETED` when:

1. **All waypoints except the final one** are in `DONE` state
2. **The final waypoint** is either `ARRIVED` or `DONE`

### Completion Process

When the final waypoint reaches `ARRIVED` or `DONE`:

1. Trip status changes from `ACTIVE` to `COMPLETED`
2. `completedAt` timestamp is set to the current time
3. Navigation state remains in Redis (with 24-hour TTL)
4. Final snapshot is saved to the database

### Completed Trip Response

```json
{
  "trip": {
    "id": 123,
    "carId": "vehicle-001",
    "status": "COMPLETED",
    "waypoints": [...],
    "waypointProgresses": [
      {
        "waypointIndex": 0,
        "state": "DONE",
        "arrivedAt": "2025-12-19T10:15:00Z",
        "remainingDistance": 0.0,
        "remainingTime": 0.0
      },
      {
        "waypointIndex": 1,
        "state": "ARRIVED",      // Final waypoint can be ARRIVED or DONE
        "arrivedAt": "2025-12-19T10:30:00Z",
        "remainingDistance": 0.0,
        "remainingTime": 0.0
      }
    ],
    "createdAt": "2025-12-19T10:00:00Z",
    "completedAt": "2025-12-19T10:30:00Z"  // Set when trip completes
  },
  "currentLocation": {
    "carId": "vehicle-001",
    "latitude": 49.368903,
    "longitude": 9.108073,
    "speed": 0.0,
    "timestamp": "2025-12-19T10:30:00Z"
  }
}
```

---

## API Endpoints Reference

### Create Trip

```
POST /api/trips
Content-Type: application/json

Request: TripCreateRequest
Response: TripResponse (201 Created)
```

### Get Trip by ID

```
GET /api/trips/{tripId}?render=false
Response: TripResponse (200 OK)

Query Parameters:
- render (boolean, default: false): Include route polyline and instructions
```

### Get All Trips (Paginated)

```
GET /api/trips?page=0&size=20&sortBy=createdAt&sortDir=DESC&render=false
Response: {
  "trips": [...],
  "currentPage": 0,
  "totalItems": 50,
  "totalPages": 3,
  "pageSize": 20,
  "hasNext": true,
  "hasPrevious": false
}

Query Parameters:
- page (int, default: 0): Page number (0-indexed)
- size (int, default: 20): Page size
- sortBy (string, default: "createdAt"): Field to sort by
- sortDir (string, default: "DESC"): Sort direction (ASC or DESC)
- render (boolean, default: false): Include route polyline and instructions
```

### Send GPS Update (Single)

```
POST /api/gps
Content-Type: application/json

Request: GpsUpdateRequest[] (array, even for single item)
Response: TripResponse (200 OK)
```

### Send GPS Updates (Batch)

```
POST /api/gps
Content-Type: application/json

Request: GpsUpdateRequest[] (array)
Response: TripResponse (200 OK)

Note: All updates must have the same carId
```

### Reset System

```
DELETE /api/reset

Response: {
  "success": true,
  "deletedTrips": 5,
  "deletedSnapshots": 120,
  "deletedRedisStates": 3,
  "message": "System reset completed successfully"
}

Deletes:
- All inactive trips (COMPLETED, CANCELLED, CREATED)
- Active trips older than 24 hours
- Associated navigation snapshots
- Redis navigation state (only if no active trips remain for that car)
```

---

## Best Practices

### GPS Updates

1. **Timestamps**: Always use current timestamps (ISO 8601 format). Old timestamps (>30 seconds) will be rejected
2. **Update Frequency**: Send updates every 1-5 seconds for best accuracy
3. **Batch Updates**: Use batch mode for better performance when sending multiple updates
4. **Chronological Order**: Ensure timestamps are in chronological order (increasing)

### Trip Creation

1. **Waypoint Order**: Order waypoints in the sequence you want to visit them
2. **includeOrigin**: Set to `false` if the vehicle is already at the first waypoint (common case)
3. **isCityTrip**: Set to `true` for city driving (affects off-route detection sensitivity)

### Error Handling

- **404 Not Found**: No active trip found for the `carId`
- **400 Bad Request**: Invalid request (missing fields, invalid data, GPS update rejected)
- **500 Internal Server Error**: Server error (check logs)

### State Persistence

- **Redis**: Real-time navigation state (24-hour TTL)
- **Database**: Periodic snapshots (every 10 seconds) and on state changes
- **Recovery**: If Redis is cleared, state can be recovered from the latest database snapshot

---

## Example: Complete Trip Flow

### 1. Create Trip

```bash
curl -X POST http://localhost:8080/api/trips \
  -H "Content-Type: application/json" \
  -d '{
    "carId": "vehicle-001",
    "waypoints": [
      {"latitude": 49.390674, "longitude": 9.082976},
      {"latitude": 49.37816, "longitude": 9.088095},
      {"latitude": 49.368903, "longitude": 9.108073}
    ],
    "includeOrigin": false
  }'
```

**Response**: Trip created with status `CREATED`, waypoint progress initialized

### 2. Send GPS Updates

```bash
# Single update
curl -X POST http://localhost:8080/api/gps \
  -H "Content-Type: application/json" \
  -d '{
    "carId": "vehicle-001",
    "latitude": 49.390674,
    "longitude": 9.082976,
    "speed": 16.5,
    "timestamp": "2025-12-19T10:00:00Z"
  }'

# Batch updates
curl -X POST http://localhost:8080/api/gps \
  -H "Content-Type: application/json" \
  -d '[
    {"carId": "vehicle-001", "latitude": 49.390674, "longitude": 9.082976, "speed": 16.5, "timestamp": "2025-12-19T10:00:00Z"},
    {"carId": "vehicle-001", "latitude": 49.390700, "longitude": 9.083000, "speed": 17.2, "timestamp": "2025-12-19T10:00:01Z"}
  ]'
```

**Response**: Trip status becomes `ACTIVE`, waypoint progress updates, `currentLocation` shows map-matched position

### 3. Monitor Progress

Continue sending GPS updates. Watch for:
- Waypoint state changes: `APPROACHING` → `ARRIVED` → `DONE`
- Decreasing `remainingDistance` and `remainingTime`
- Trip status change to `COMPLETED` when final waypoint is reached

### 4. Trip Completion

When the final waypoint reaches `ARRIVED` or `DONE`:
- Trip status automatically changes to `COMPLETED`
- `completedAt` timestamp is set
- All waypoints show `remainingDistance: 0.0` and `remainingTime: 0.0`

---

## Configuration

### GPS Age Validation

By default, GPS updates older than 30 seconds are rejected. To disable this check:

```yaml
navigation:
  gps:
    validate-age: false  # Set to false to disable age validation
```

### Off-Route Detection

```yaml
navigation:
  off-route:
    city:
      distance-threshold-meters: 40      # Distance threshold for city trips
      consecutive-updates: 2             # Consecutive updates before rerouting
    non-city:
      distance-threshold-meters: 40       # Distance threshold for non-city trips
      consecutive-updates: 3             # Consecutive updates before rerouting
```

### Arrival Detection

```yaml
navigation:
  arrival:
    radius-meters: 20          # Distance to waypoint to trigger ARRIVED state
    pass-threshold-meters: 10  # Distance past waypoint to trigger DONE state
```

---

## Troubleshooting

### GPS Update Rejected

**Error**: `400 Bad Request` or "GPS update rejected"

**Possible Causes**:
- Timestamp is too old (>30 seconds)
- No active trip found for `carId`
- GPS update is out of chronological order
- Invalid data (missing required fields)

**Solution**: Ensure timestamps are current and in chronological order

### Trip Not Completing

**Issue**: Final waypoint is `ARRIVED` but trip status is still `ACTIVE`

**Solution**: This should be automatically handled. If it persists, check:
- All previous waypoints are `DONE`
- Final waypoint is `ARRIVED` or `DONE`
- System logs for errors

### Waypoint Progress Not Updating

**Issue**: Waypoint states remain `APPROACHING`

**Possible Causes**:
- Vehicle is not moving (speed too low)
- GPS updates are not being sent
- Vehicle is too far from route

**Solution**: Ensure GPS updates are being sent regularly with valid coordinates

---

## TypeScript Types (Frontend)

```typescript
// Trip Creation Request
interface TripCreateRequest {
  id?: number;
  carId: string;
  waypoints: Waypoint[];
  includeInstructions?: boolean;
  includeOrigin?: boolean;
  isCityTrip?: boolean;
}

// Waypoint
interface Waypoint {
  id?: string | null;
  name?: string | null;
  latitude: number;
  longitude: number;
}

// GPS Update Request
interface GpsUpdateRequest {
  carId: string;
  latitude: number;
  longitude: number;
  speed: number;
  heading?: number | null;
  accuracy?: number | null;
  timestamp: string; // ISO 8601
}

// Trip Response
interface TripResponse {
  trip: TripDto;
  instructions?: Instruction | null;
  currentLocation?: CurrentLocation | null;
}

// Trip DTO
interface TripDto {
  id: number;
  carId: string;
  status: 'CREATED' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED';
  waypoints: Waypoint[];
  route?: RouteDto | null;
  instructions?: Instruction | null;
  waypointProgresses: WaypointProgressDto[];
  includeOrigin: boolean;
  isCityTrip: boolean;
  createdAt: string; // ISO 8601
  completedAt?: string | null; // ISO 8601
}

// Waypoint Progress
interface WaypointProgressDto {
  waypointIndex: number;
  waypointId?: string | null;
  waypointName?: string | null;
  latitude: number;
  longitude: number;
  state: 'APPROACHING' | 'ARRIVED' | 'DONE';
  arrivedAt?: string | null; // ISO 8601
  remainingDistance: number;
  remainingTime: number;
}

// Current Location
interface CurrentLocation {
  carId: string;
  latitude: number;
  longitude: number;
  speed: number;
  heading?: number | null;
  timestamp: string; // ISO 8601
}

// Route DTO (when render=true)
interface RouteDto {
  polyline: number[][]; // [[lat, lon], ...]
  cumulativeDistances: number[];
  totalDistance: number;
  totalDuration: number;
  legStopIndices: number[];
  legCumulativeDistances: number[];
  legDurations: number[];
}

// Instruction (when render=true and includeInstructions=true)
interface Instruction {
  steps: InstructionStep[];
}

interface InstructionStep {
  distance: number;
  duration: number;
  instruction: string;
  maneuver: string;
  location: number[]; // [lon, lat]
}
```

---

## Summary

1. **Create Trip**: `POST /api/trips` with waypoints
2. **Send GPS Updates**: `POST /api/gps` (single or batch) regularly
3. **Monitor Progress**: Check `waypointProgresses` in responses
4. **Trip Completes**: Automatically when final waypoint reaches `ARRIVED` or `DONE`
5. **Get Trip Status**: `GET /api/trips/{tripId}` to check current state

The system handles:
- Map matching (snapping GPS to route)
- Waypoint progress tracking
- Off-route detection and rerouting
- State persistence (Redis + Database)
- Automatic trip completion

