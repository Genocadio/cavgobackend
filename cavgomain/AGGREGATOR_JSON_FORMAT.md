# Internal API Documentation

This document describes the internal microservice API endpoints and the JSON structures used for communication.

## Table of Contents

1. [GET Endpoints](#get-endpoints)
2. [PUT Endpoints](#put-endpoints)
3. [Aggregator JSON Format](#aggregator-json-format)

---

## GET Endpoints

All GET endpoints are available at the base path: `/internal/api`

### Vehicle Endpoints

#### Get All Vehicles

**Endpoint:** `GET /internal/api/vehicles`

**Description:** Retrieves all vehicles across all companies.

**Response:** Array of vehicle objects

**Example Request:**
```http
GET /internal/api/vehicles
```

**Example Response:**
```json
[
  {
    "id": "1",
    "companyId": "1",
    "companyCode": "RWA",
    "plate": "RAC 123A",
    "model": "Corolla",
    "make": "Toyota",
    "capacity": 4,
    "connectionStatus": "ONLINE",
    "operationalStatus": "AVAILABLE",
    "currentLocation": {
      "latitude": -1.9441,
      "longitude": 30.0619,
      "address": null,
      "timestamp": "2024-01-15T10:30:00.000Z",
      "bearing": 45.5,
      "speed": 0.0
    },
    "lastUpdated": "2024-01-15T10:30:00.000Z"
  },
  {
    "id": "2",
    "companyId": "1",
    "companyCode": "RWA",
    "plate": "RAC 456B",
    "model": "Camry",
    "make": "Toyota",
    "capacity": 5,
    "connectionStatus": "OFFLINE",
    "operationalStatus": "MAINTENANCE",
    "currentLocation": null,
    "lastUpdated": "2024-01-15T09:00:00.000Z"
  }
]
```

**Sync Behavior:** Does NOT trigger aggregator sync

---

#### Get Vehicle by ID

**Endpoint:** `GET /internal/api/vehicles/{id}`

**Description:** Retrieves a single vehicle by its ID.

**Path Parameters:**
- `id` (Long, required) - Vehicle ID

**Response:** Single vehicle object

**Example Request:**
```http
GET /internal/api/vehicles/1
```

**Example Response:**
```json
{
  "id": "1",
  "companyId": "1",
  "companyCode": "RWA",
  "plate": "RAC 123A",
  "model": "Corolla",
  "make": "Toyota",
  "capacity": 4,
  "connectionStatus": "ONLINE",
  "operationalStatus": "AVAILABLE",
  "currentLocation": {
    "latitude": -1.9441,
    "longitude": 30.0619,
    "address": null,
    "timestamp": "2024-01-15T10:30:00.000Z",
    "bearing": 45.5,
    "speed": 0.0
  },
  "lastUpdated": "2024-01-15T10:30:00.000Z"
}
```

**Error Responses:**
- `404 Not Found` - Vehicle with the specified ID does not exist

**Sync Behavior:** Does NOT trigger aggregator sync

---

#### Get Vehicles by Company

**Endpoint:** `GET /internal/api/vehicles/company/{companyId}`

**Description:** Retrieves all vehicles for a specific company. **This endpoint triggers a 10-minute delayed aggregator sync.**

**Path Parameters:**
- `companyId` (Long, required) - Company ID

**Response:** Array of vehicle objects

**Example Request:**
```http
GET /internal/api/vehicles/company/1
```

**Example Response:**
```json
[
  {
    "id": "1",
    "companyId": "1",
    "companyCode": "RWA",
    "plate": "RAC 123A",
    "model": "Corolla",
    "make": "Toyota",
    "capacity": 4,
    "connectionStatus": "ONLINE",
    "operationalStatus": "AVAILABLE",
    "currentLocation": {
      "latitude": -1.9441,
      "longitude": 30.0619,
      "address": null,
      "timestamp": "2024-01-15T10:30:00.000Z",
      "bearing": 45.5,
      "speed": 0.0
    },
    "lastUpdated": "2024-01-15T10:30:00.000Z"
  }
]
```

**Sync Behavior:** 
- Triggers a 10-minute delayed aggregator sync
- If a sync timer already exists for this company, it is cancelled and a new 10-minute timer is started
- Sync will POST all vehicles for this company to `{AGGREGATOR_BASE_URL}/company/{companyId}/vehicle`

---

### Worker Endpoints

#### Get All Workers

**Endpoint:** `GET /internal/api/workers`

**Description:** Retrieves all company users (workers) across all companies. Workers can have different roles: ADMIN, DRIVER, FLEET_MANAGER, or SUPERVISOR.

**Response:** Array of worker objects

**Example Request:**
```http
GET /internal/api/workers
```

**Example Response:**
```json
[
  {
    "id": "1",
    "name": "John Doe",
    "phone": "+250788123456",
    "email": "john.doe@example.com",
    "licenseNumber": "DL123456",
    "status": "ACTIVE",
    "role": "DRIVER",
    "vehicle": {
      "id": "1",
      "companyId": "1",
      "companyCode": "RWA",
      "plate": "RAC 123A",
      "model": "Corolla",
      "make": "Toyota",
      "capacity": 4,
      "connectionStatus": "ONLINE",
      "operationalStatus": "AVAILABLE",
      "currentLocation": {
        "latitude": -1.9441,
        "longitude": 30.0619,
        "address": null,
        "timestamp": "2024-01-15T10:30:00.000Z",
        "bearing": 45.5,
        "speed": 0.0
      },
      "lastUpdated": "2024-01-15T10:30:00.000Z"
    }
  },
  {
    "id": "2",
    "name": "Jane Smith",
    "phone": "+250788654321",
    "email": "jane.smith@example.com",
    "licenseNumber": "DL789012",
    "status": "ACTIVE",
    "role": "DRIVER",
    "vehicle": null
  }
]
```

**Sync Behavior:** Does NOT trigger aggregator sync

---

#### Get Worker by ID

**Endpoint:** `GET /internal/api/workers/{id}`

**Description:** Retrieves a single company user (worker) by their ID. Can be any role: ADMIN, DRIVER, FLEET_MANAGER, or SUPERVISOR.

**Path Parameters:**
- `id` (Long, required) - Worker ID

**Response:** Single worker object

**Example Request:**
```http
GET /internal/api/workers/1
```

**Example Response:**
```json
{
  "id": "1",
  "name": "John Doe",
  "phone": "+250788123456",
  "email": "john.doe@example.com",
  "licenseNumber": "DL123456",
  "status": "ACTIVE",
  "role": "DRIVER",
  "vehicle": {
    "id": "1",
    "companyId": "1",
    "companyCode": "RWA",
    "plate": "RAC 123A",
    "model": "Corolla",
    "make": "Toyota",
    "capacity": 4,
    "connectionStatus": "ONLINE",
    "operationalStatus": "AVAILABLE",
    "currentLocation": {
      "latitude": -1.9441,
      "longitude": 30.0619,
      "address": null,
      "timestamp": "2024-01-15T10:30:00.000Z",
      "bearing": 45.5,
      "speed": 0.0
    },
    "lastUpdated": "2024-01-15T10:30:00.000Z"
  }
}
```

**Error Responses:**
- `404 Not Found` - Worker with the specified ID does not exist

**Sync Behavior:** Does NOT trigger aggregator sync

---

#### Get Workers by Company

**Endpoint:** `GET /internal/api/workers/company/{companyId}`

**Description:** Retrieves all company users (workers) for a specific company. Returns workers of all roles: ADMIN, DRIVER, FLEET_MANAGER, and SUPERVISOR. **This endpoint triggers a 10-minute delayed aggregator sync.**

**Path Parameters:**
- `companyId` (Long, required) - Company ID

**Response:** Array of worker objects

**Example Request:**
```http
GET /internal/api/workers/company/1
```

**Example Response:**
```json
[
  {
    "id": "1",
    "name": "John Doe",
    "phone": "+250788123456",
    "email": "john.doe@example.com",
    "licenseNumber": "DL123456",
    "status": "ACTIVE",
    "role": "DRIVER",
    "vehicle": {
      "id": "1",
      "companyId": "1",
      "companyCode": "RWA",
      "plate": "RAC 123A",
      "model": "Corolla",
      "make": "Toyota",
      "capacity": 4,
      "connectionStatus": "ONLINE",
      "operationalStatus": "AVAILABLE",
      "currentLocation": {
        "latitude": -1.9441,
        "longitude": 30.0619,
        "address": null,
        "timestamp": "2024-01-15T10:30:00.000Z",
        "bearing": 45.5,
        "speed": 0.0
      },
      "lastUpdated": "2024-01-15T10:30:00.000Z"
    }
  }
]
```

**Sync Behavior:** 
- Triggers a 10-minute delayed aggregator sync
- If a sync timer already exists for this company, it is cancelled and a new 10-minute timer is started
- Sync will POST all workers for this company to `{AGGREGATOR_BASE_URL}/company/{companyId}/worker`

---

## PUT Endpoints

### Toggle Worker Status

**Endpoint:** `PUT /internal/api/workers/{id}/status`

**Description:** Toggles the status of a worker between ACTIVE and INACTIVE. **This endpoint triggers an immediate aggregator sync.**

**Path Parameters:**
- `id` (Long, required) - Worker ID

**Request Body:** None

**Response:** Updated worker object

**Example Request:**
```http
PUT /internal/api/workers/1/status
```

**Example Response:**
```json
{
  "id": "1",
  "name": "John Doe",
  "phone": "+250788123456",
  "email": "john.doe@example.com",
    "licenseNumber": "DL123456",
    "status": "INACTIVE",
    "role": "DRIVER",
    "vehicle": {
    "id": "1",
    "companyId": "1",
    "companyCode": "RWA",
    "plate": "RAC 123A",
    "model": "Corolla",
    "make": "Toyota",
    "capacity": 4,
    "connectionStatus": "ONLINE",
    "operationalStatus": "AVAILABLE",
    "currentLocation": {
      "latitude": -1.9441,
      "longitude": 30.0619,
      "address": null,
      "timestamp": "2024-01-15T10:30:00.000Z",
      "bearing": 45.5,
      "speed": 0.0
    },
    "lastUpdated": "2024-01-15T10:30:00.000Z"
  }
}
```

**Status Toggle Logic:**
- If current status is `ACTIVE` → changes to `INACTIVE`
- If current status is `INACTIVE` → changes to `ACTIVE`
- For any other status → changes to `ACTIVE`

**Note:** This endpoint works for workers of any role (ADMIN, DRIVER, FLEET_MANAGER, SUPERVISOR).

**Error Responses:**
- `404 Not Found` - Worker with the specified ID does not exist

**Sync Behavior:** 
- Triggers an **immediate** aggregator sync for the worker's company
- Sync will POST all workers and vehicles for the company to the aggregator

---

## Aggregator JSON Format

This section describes the JSON structure that will be POSTed to the aggregator service endpoints.

### Aggregator Endpoints

- `POST {AGGREGATOR_BASE_URL}/company/{companyId}/vehicle` - Receives array of vehicles
- `POST {AGGREGATOR_BASE_URL}/company/{companyId}/worker` - Receives array of workers

## Vehicle JSON Structure

The aggregator will receive an array of vehicle objects in the following format:

```json
[
  {
    "id": "1",
    "companyId": "1",
    "companyCode": "RWA",
    "plate": "RAC 123A",
    "model": "Corolla",
    "make": "Toyota",
    "capacity": 4,
    "connectionStatus": "ONLINE",
    "operationalStatus": "AVAILABLE",
    "currentLocation": {
      "latitude": -1.9441,
      "longitude": 30.0619,
      "address": null,
      "timestamp": "2024-01-15T10:30:00.000Z",
      "bearing": 45.5,
      "speed": 0.0
    },
    "lastUpdated": "2024-01-15T10:30:00.000Z"
  }
]
```

### Vehicle Field Descriptions

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Vehicle unique identifier |
| `companyId` | String | Yes | Company ID that owns the vehicle |
| `companyCode` | String | Yes | Company code (e.g., "RWA") |
| `plate` | String | Yes | License plate number |
| `model` | String | Yes | Vehicle model (e.g., "Corolla") |
| `make` | String | Yes | Vehicle manufacturer (e.g., "Toyota") |
| `capacity` | Integer | Yes | Vehicle passenger capacity |
| `connectionStatus` | String | Yes | Either "ONLINE" or "OFFLINE" |
| `operationalStatus` | String | Yes | One of: "AVAILABLE", "MAINTENANCE", "OUT_OF_SERVICE", "OCCUPIED" |
| `currentLocation` | Object | No | Location object (null if no location data) |
| `lastUpdated` | String | Yes | ISO 8601 timestamp of last update |

### Location Object Structure

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `latitude` | Double | Yes | Latitude coordinate |
| `longitude` | Double | Yes | Longitude coordinate |
| `address` | String | No | Address string (can be null) |
| `timestamp` | String | Yes | ISO 8601 timestamp when location was recorded |
| `bearing` | Double | No | Bearing in degrees (can be null) |
| `speed` | Double | No | Speed in meters per second (can be null) |

**Note:** If a vehicle has no location data, `currentLocation` will be `null`.

### Operational Status Values

- `AVAILABLE` - Vehicle is available for assignment
- `MAINTENANCE` - Vehicle is in maintenance
- `OUT_OF_SERVICE` - Vehicle is out of service
- `OCCUPIED` - Vehicle is currently occupied/assigned

## Worker JSON Structure

The aggregator will receive an array of worker (company user) objects in the following format. Workers can have different roles: ADMIN, DRIVER, FLEET_MANAGER, or SUPERVISOR.

```json
[
  {
    "id": "1",
    "name": "John Doe",
    "phone": "+250788123456",
    "email": "john.doe@example.com",
    "licenseNumber": "DL123456",
    "status": "ACTIVE",
    "role": "DRIVER",
    "vehicle": {
      "id": "1",
      "companyId": "1",
      "companyCode": "RWA",
      "plate": "RAC 123A",
      "model": "Corolla",
      "make": "Toyota",
      "capacity": 4,
      "connectionStatus": "ONLINE",
      "operationalStatus": "AVAILABLE",
      "currentLocation": {
        "latitude": -1.9441,
        "longitude": 30.0619,
        "address": null,
        "timestamp": "2024-01-15T10:30:00.000Z",
        "bearing": 45.5,
        "speed": 0.0
      },
      "lastUpdated": "2024-01-15T10:30:00.000Z"
    }
  }
]
```

### Worker Field Descriptions

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Worker unique identifier |
| `name` | String | Yes | Full name (firstName + lastName) |
| `phone` | String | Yes | Phone number with country code |
| `email` | String | Yes | Email address |
| `licenseNumber` | String | No | Driver's license number (null for non-driver roles) |
| `status` | String | Yes | One of: "ACTIVE", "INACTIVE", "SUSPENDED", "PENDING_VERIFICATION" |
| `role` | String | Yes | One of: "ADMIN", "DRIVER", "FLEET_MANAGER", "SUPERVISOR" |
| `vehicle` | Object | No | Assigned vehicle object (null if no assignment or non-driver role) |

**Notes:**
- If a worker has no assigned vehicle, `vehicle` will be `null`
- The `vehicle` field is only populated for workers with role `DRIVER`
- The `licenseNumber` field may be `null` for non-driver roles (ADMIN, FLEET_MANAGER, SUPERVISOR)

### Worker Status Values

- `ACTIVE` - Worker is active and available
- `INACTIVE` - Worker is inactive
- `SUSPENDED` - Worker is suspended
- `PENDING_VERIFICATION` - Worker account pending verification

### Worker Role Values

- `ADMIN` - Administrative user
- `DRIVER` - Driver/worker who can be assigned vehicles
- `FLEET_MANAGER` - Fleet management user
- `SUPERVISOR` - Supervisory user

**Note:** Only workers with role `DRIVER` can have vehicle assignments. For other roles, the `vehicle` field will always be `null`.

## Example: Worker Without Vehicle Assignment

```json
[
  {
    "id": "2",
    "name": "Jane Smith",
    "phone": "+250788654321",
    "email": "jane.smith@example.com",
    "licenseNumber": "DL789012",
    "status": "ACTIVE",
    "role": "DRIVER",
    "vehicle": null
  }
]
```

## Example: Non-Driver Worker (Admin)

```json
[
  {
    "id": "5",
    "name": "Admin User",
    "phone": "+250788111222",
    "email": "admin@example.com",
    "licenseNumber": null,
    "status": "ACTIVE",
    "role": "ADMIN",
    "vehicle": null
  }
]
```

## Example: Fleet Manager

```json
[
  {
    "id": "6",
    "name": "Fleet Manager",
    "phone": "+250788333444",
    "email": "fleet.manager@example.com",
    "licenseNumber": null,
    "status": "ACTIVE",
    "role": "FLEET_MANAGER",
    "vehicle": null
  }
]
```

## Example: Vehicle Without Location

```json
[
  {
    "id": "3",
    "companyId": "1",
    "companyCode": "RWA",
    "plate": "RAC 456B",
    "model": "Camry",
    "make": "Toyota",
    "capacity": 5,
    "connectionStatus": "OFFLINE",
    "operationalStatus": "MAINTENANCE",
    "currentLocation": null,
    "lastUpdated": "2024-01-15T09:00:00.000Z"
  }
]
```

## HTTP Request Details

- **Method:** POST
- **Content-Type:** application/json
- **Body:** Array of objects (vehicles or workers)
- **URL Pattern:** 
  - Vehicles: `{AGGREGATOR_BASE_URL}/company/{companyId}/vehicle`
  - Workers: `{AGGREGATOR_BASE_URL}/company/{companyId}/worker`

## Sync Behavior

1. **Immediate Sync:** Triggered when a vehicle or worker is created
2. **Delayed Sync:** Triggered 10 minutes after a GET request for company data
3. **Timer Reset:** If a new GET request comes for the same company, the existing 10-minute timer is cancelled and a new one is started

