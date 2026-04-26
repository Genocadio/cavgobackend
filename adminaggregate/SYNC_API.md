# Sync API for Routes and Locations

This document defines remote sync behavior for:

- `GET /routes/hash`
- `GET /locations/hash`
- `GET /main-hash`

It is designed for mobile/web clients that need:

- Initial full download with pagination
- Incremental updates using hash checkpoints
- Explicit operation tracking (`created`, `updated`, `deleted`)
- Safe handling of deleted entities
- Deterministic dependency order (`locations` first, then `routes`)

## 1) Endpoint Summary

- `GET /routes/hash`
- `GET /locations/hash`
- `GET /main-hash`

Common query parameters for hash endpoints:

- `hash` (optional): last checkpoint hash held by client
- `page` (optional, default `1`)
- `limit` (optional, default `20`, max `100`)

## 2) Sync Modes

Important dependency rule:

- Devices MUST sync `locations` first, then `routes`.
- Routes reference locations via `origin_id`, `destination_id`, and waypoint `location_id`.
- Applying route updates before location updates can create unresolved references in local storage.

### A. Initial Sync (No `hash`)

Request:

```http
GET /routes/hash?page=1&limit=50
```

Behavior:

- Returns full paginated records in `routes`/`locations`
- `changed` is `true`
- `deleted_ids` is empty
- `changes` is empty (initial load is full state, not delta stream)
- `hash` is latest server main hash (or empty if none exists yet)

### B. Delta Sync (With `hash`)

Request:

```http
GET /routes/hash?hash=<client_hash>&page=1&limit=50
```

Behavior:

- If hash is valid, server computes changes since that hash timestamp
- Returns operation-aware `changes` entries (includes deletions)
- Returns compatibility fields:
  - `routes`/`locations`: non-deleted changed entities
  - `deleted_ids`: current deleted entity IDs since hash
- Returns `hash` as latest server main hash

### C. Invalid Hash

If provided hash does not exist in DB:

- HTTP `200`
- `changed=false`
- empty data arrays
- `message` explains client must resync without `hash`

## 3) Response Contracts

All route and location payloads follow the full model fields returned by the API.

## Full Location Object (Expected)

```json
{
  "id": 55,
  "latitude": -1.9441,
  "code": "11001",
  "longitude": 30.0619,
  "google_place_name": "Kigali City Tower",
  "custom_name": "Downtown",
  "province": "kigali",
  "district": "nyarugenge",
  "place_id": "ChIJx0qKj8V5lhkR8W0v0kQ5l1w",
  "created_at": "2026-04-15T08:00:00Z",
  "updated_at": "2026-04-15T08:10:00Z"
}
```

## Full Route Object (Expected in Sync Endpoints)

Note: sync endpoints intentionally return `waypoints: null` for compact payloads.

```json
{
  "id": 101,
  "name": "Kigali to Musanze",
  "distance_meters": 90000,
  "estimated_duration_seconds": 7200,
  "google_route_id": "route_abc_123",
  "origin_id": 1,
  "destination_id": 2,
  "route_price": 3500,
  "city_route": false,
  "created_at": "2026-04-15T08:00:00Z",
  "updated_at": "2026-04-15T08:20:00Z",
  "origin": {
    "id": 1,
    "latitude": -1.9441,
    "code": "11001",
    "longitude": 30.0619,
    "google_place_name": "Kigali Downtown",
    "custom_name": "Kigali CBD",
    "province": "kigali",
    "district": "nyarugenge",
    "place_id": "place_origin_1",
    "created_at": "2026-04-10T08:00:00Z",
    "updated_at": "2026-04-10T08:00:00Z"
  },
  "destination": {
    "id": 2,
    "latitude": -1.4996,
    "code": "23001",
    "longitude": 29.6347,
    "google_place_name": "Musanze Bus Park",
    "custom_name": "Musanze Center",
    "province": "north",
    "district": "musanze",
    "place_id": "place_destination_2",
    "created_at": "2026-04-10T08:00:00Z",
    "updated_at": "2026-04-10T08:00:00Z"
  },
  "waypoints": null
}
```

## Full Route Object (Non-sync Route APIs)

For `GET /routes` and `GET /routes/{id}`, `waypoints` are returned with location details.

```json
{
  "id": 101,
  "name": "Kigali to Musanze",
  "distance_meters": 90000,
  "estimated_duration_seconds": 7200,
  "google_route_id": "route_abc_123",
  "origin_id": 1,
  "destination_id": 2,
  "route_price": 3500,
  "city_route": false,
  "created_at": "2026-04-15T08:00:00Z",
  "updated_at": "2026-04-15T08:20:00Z",
  "origin": {
    "id": 1,
    "latitude": -1.9441,
    "code": "11001",
    "longitude": 30.0619,
    "google_place_name": "Kigali Downtown",
    "custom_name": "Kigali CBD",
    "province": "kigali",
    "district": "nyarugenge",
    "place_id": "place_origin_1",
    "created_at": "2026-04-10T08:00:00Z",
    "updated_at": "2026-04-10T08:00:00Z"
  },
  "destination": {
    "id": 2,
    "latitude": -1.4996,
    "code": "23001",
    "longitude": 29.6347,
    "google_place_name": "Musanze Bus Park",
    "custom_name": "Musanze Center",
    "province": "north",
    "district": "musanze",
    "place_id": "place_destination_2",
    "created_at": "2026-04-10T08:00:00Z",
    "updated_at": "2026-04-10T08:00:00Z"
  },
  "waypoints": [
    {
      "id": 301,
      "route_id": 101,
      "location_id": 12,
      "order": 1,
      "price": null,
      "is_pass_through": true,
      "created_at": "2026-04-15T08:00:00Z",
      "location": {
        "id": 12,
        "latitude": -1.7402,
        "code": "21015",
        "longitude": 29.8508,
        "google_place_name": "Rulindo Junction",
        "custom_name": "Rulindo Stop",
        "province": "north",
        "district": "rulindo",
        "place_id": "place_wp_12",
        "created_at": "2026-04-10T08:00:00Z",
        "updated_at": "2026-04-10T08:00:00Z"
      }
    }
  ]
}
```

## Route Sync Response

```json
{
  "hash": "<latest_server_hash>",
  "changed": true,
  "routes": [
    {
      "id": 101,
      "origin": { "id": 1 },
      "destination": { "id": 2 },
      "waypoints": null
    }
  ],
  "changes": [
    {
      "id": 101,
      "operation": "updated",
      "route": {
        "id": 101,
        "origin": { "id": 1 },
        "destination": { "id": 2 },
        "waypoints": null
      },
      "changed_at": "2026-04-15T08:30:00Z"
    },
    {
      "id": 202,
      "operation": "deleted",
      "changed_at": "2026-04-15T08:31:00Z"
    }
  ],
  "deleted_ids": [202],
  "page": 1,
  "limit": 50,
  "total": 2,
  "message": ""
}
```

## Location Sync Response

```json
{
  "hash": "<latest_server_hash>",
  "changed": true,
  "locations": [
    {
      "id": 55,
      "custom_name": "Downtown"
    }
  ],
  "changes": [
    {
      "id": 55,
      "operation": "created",
      "location": {
        "id": 55,
        "custom_name": "Downtown"
      },
      "changed_at": "2026-04-15T08:25:00Z"
    },
    {
      "id": 88,
      "operation": "deleted",
      "changed_at": "2026-04-15T08:29:00Z"
    }
  ],
  "deleted_ids": [88],
  "page": 1,
  "limit": 50,
  "total": 2,
  "message": ""
}
```

## 4) Operation Semantics

Each `changes[]` item has one of:

- `created`
- `updated`
- `deleted`

Notes:

- For `deleted`, object payload (`route` or `location`) is omitted.
- `deleted_ids` is kept for backward compatibility and fast delete processing.
- `total` is the count of unique entities changed since client hash (latest state per entity).

## 5) Pagination Semantics

For hash sync endpoints with `hash`:

- Pagination applies to unique changed entities (latest operation per entity ID)
- Stable ordering is by entity ID ascending
- Use `page` + `limit` until all pages are consumed

For initial sync without `hash`:

- Pagination applies to full dataset (`routes` or `locations`)

## 6) Client Sync Algorithm (Recommended)

1. If no local checkpoint hash exists:
- Call `/locations/hash` without hash, paginating through all pages, and store all locations.
- Then call `/routes/hash` without hash, paginating through all pages, and store all routes.
- Save returned `hash` as local checkpoint.

2. If local checkpoint hash exists:
- Call `/locations/hash?hash=<checkpoint>` first and apply all pages.
- Then call `/routes/hash?hash=<checkpoint>` and apply all pages.
- For each `changes[]` item:
  - `created`/`updated`: upsert entity
  - `deleted`: delete local entity by `id`
- Update local checkpoint to returned `hash` after successful full delta processing.

3. If response says invalid hash:
- Discard local checkpoint hash.
- Perform full initial sync again.

## 7) Merge and Freshness Behavior

The server tracks every create/update/delete in change batches.

Merges are triggered by:

- inactivity monitor (10 minutes)
- merge timer (2 hours)
- manual `POST /merge`

Delta queries are based on client hash creation time and include changes across merged and unmerged history, so clients can recover from older hashes safely.

## 8) Compatibility Notes

- Existing clients using `routes`/`locations` + `deleted_ids` continue to work.
- New clients should prefer `changes[]` for exact operation-aware sync.
