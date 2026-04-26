# Get Vehicle Trips API Guide

## Overview
This document describes the vehicle trips endpoint:

- `GET /trips/vehicle/{vehicle_id}`

It supports:

- filtering by one or more trip statuses
- pagination with `limit` and `offset`
- optional SSE session reuse with `session_uuid`

## Endpoint

```http
GET /trips/vehicle/{vehicle_id}
```

## Path Parameter

| Name | Type | Required | Notes |
|---|---|---|---|
| `vehicle_id` | `int64` | Yes | Must be a valid numeric vehicle ID |

## Query Parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `status` | `string` or repeated param | No | - | Single status or list of statuses |
| `limit` | `int` | No | `20` | Must be `>= 0` |
| `offset` | `int` | No | `0` | Must be `>= 0` |
| `session_uuid` | `string` | No | - | Existing SSE session UUID to update |

## Status Filter

The endpoint supports filtering by one or many statuses.

Allowed values:

- `SCHEDULED`
- `IN_PROGRESS`
- `COMPLETED`
- `NOT_COMPLETED`
- `CANCELLED`

You can pass a list in either format:

1. Comma-separated:

```http
GET /trips/vehicle/456?status=SCHEDULED,IN_PROGRESS,CANCELLED
```

2. Repeated query params:

```http
GET /trips/vehicle/456?status=SCHEDULED&status=IN_PROGRESS&status=CANCELLED
```

If an invalid status is provided, the API returns `400 Bad Request`.

## Pagination

Pagination is applied at the database query level.

- `limit`: max number of trips returned
- `offset`: number of matching trips to skip
- `total`: count of all matching trips before pagination

Page metadata is returned as:

- `total`
- `limit`
- `offset`
- `page`
- `total_pages`

`page` is calculated from `offset` and `limit`:

- `page = (offset / limit) + 1` when `limit > 0`

## Response

### Success (`200 OK`)

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
        "destination": { "id": 2, "custom_name": "Musanze" }
      },
      "waypoints": []
    }
  ],
  "total": 14,
  "limit": 20,
  "offset": 0,
  "page": 1,
  "total_pages": 1,
  "sse_uuid": "7f5d8ef0-7cc6-4f98-9d9f-0a9e0f88f233"
}
```

Notes:

- `sse_uuid` is included only when a new session is created.
- If `session_uuid` is provided and valid, response typically omits `sse_uuid`.
- Trips are ordered newest first (`created_at DESC`).

### Error Shape

```json
{
  "error": "error message"
}
```

Common errors:

- `400 Bad Request`: invalid vehicle ID, invalid `limit`/`offset`, invalid status value
- `500 Internal Server Error`: unexpected server error

## Examples

### 1) Get all trips for vehicle

```bash
curl "http://localhost:8080/trips/vehicle/456"
```

### 2) Filter by one status

```bash
curl "http://localhost:8080/trips/vehicle/456?status=IN_PROGRESS"
```

### 3) Filter by multiple statuses (comma-separated)

```bash
curl "http://localhost:8080/trips/vehicle/456?status=SCHEDULED,IN_PROGRESS,CANCELLED"
```

### 4) Filter by multiple statuses (repeated params)

```bash
curl "http://localhost:8080/trips/vehicle/456?status=SCHEDULED&status=IN_PROGRESS&status=CANCELLED"
```

### 5) Multi-status + pagination

```bash
curl "http://localhost:8080/trips/vehicle/456?status=SCHEDULED,IN_PROGRESS&limit=10&offset=20"
```

### 6) Reuse existing SSE session

```bash
curl "http://localhost:8080/trips/vehicle/456?status=IN_PROGRESS&limit=20&offset=0&session_uuid=7f5d8ef0-7cc6-4f98-9d9f-0a9e0f88f233"
```
