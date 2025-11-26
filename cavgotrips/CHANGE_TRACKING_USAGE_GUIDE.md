# Change Tracking System - Usage Guide

This guide explains how to use the hash-based change tracking system to sync locations and routes.

## Overview

The system uses hash-based synchronization where:
- **Hash** = SHA-256 hash representing the current state of all locations and routes
- **Client** stores the hash locally
- **Client** sends hash to server to check if data changed
- **Server** returns changed data + new hash if different

## API Endpoints

### 1. Get Latest Main Hash
**GET** `/main-hash`

Returns the current main hash on the server.

**Response:**
```json
{
  "id": 1,
  "hash": "d97fae1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
  "location_ids": [1, 2, 3, 5, 7],
  "route_ids": [10, 15, 20],
  "included_batches": [1, 2, 3],
  "created_at": "2025-01-15T10:30:00Z",
  "type": "auto"
}
```

**Use Case:** 
- First time setup - get initial hash
- After errors - reset to latest hash
- Periodic check - verify you have latest hash

---

### 2. Sync Routes by Hash
**GET** `/routes/hash?hash={hash}&page={page}&limit={limit}`

**Query Parameters:**
- `hash` (optional) - The hash you currently have. If not provided, returns all routes with latest hash (for initial sync)
- `page` (optional, default: 1) - Page number for pagination
- `limit` (optional, default: 20, max: 100) - Items per page

**Behavior:**
- **Without hash**: Returns all routes with latest hash (for first-time sync/new devices with pagination)
- **With valid hash**: Returns only changed routes since that hash
- **With invalid hash**: Returns empty data with message (HTTP 200, not an error)

**Response when hash matches (no changes):**
```json
{
  "hash": "d97fae1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
  "changed": false,
  "routes": [],
  "deleted_ids": []
}
```

**Response when hash doesn't match (has changes):**
```json
{
  "hash": "new_hash_after_merge_abcdef1234567890abcdef1234567890abcdef1234567890",
  "changed": true,
  "routes": [
    {
      "id": 10,
      "name": "Route A",
      "origin_id": 1,
      "destination_id": 2,
      "route_price": 50.0,
      "city_route": false,
      "origin": { "id": 1, "custom_name": "Location A" },
      "destination": { "id": 2, "custom_name": "Location B" },
      "waypoints": null
    }
  ],
  "deleted_ids": [15],
  "page": 1,
  "limit": 20,
  "total": 5
}
```

**Response when invalid hash is provided:**
```json
{
  "hash": "current_latest_hash",
  "changed": false,
  "routes": [],
  "deleted_ids": [],
  "message": "Invalid hash: hash not found in database. Please sync without hash parameter to get all data."
}
```
Note: Returns HTTP 200 (not an error) with empty data and message, allowing client to handle gracefully.

**Error Responses:**
- `500 Internal Server Error` - Server error

---

### 3. Sync Locations by Hash
**GET** `/locations/hash?hash={hash}&page={page}&limit={limit}`

**Query Parameters:**
- `hash` (optional) - The hash you currently have. If not provided, returns all locations with latest hash (for initial sync)
- `page` (optional, default: 1) - Page number for pagination
- `limit` (optional, default: 20, max: 100) - Items per page

**Behavior:**
- **Without hash**: Returns all locations with latest hash (for first-time sync/new devices with pagination)
- **With valid hash**: Returns only changed locations since that hash
- **With invalid hash**: Returns empty data with message (HTTP 200, not an error)

**Response when hash matches (no changes):**
```json
{
  "hash": "d97fae1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
  "changed": false,
  "locations": [],
  "deleted_ids": []
}
```

**Response when hash doesn't match (has changes):**
```json
{
  "hash": "new_hash_after_merge_abcdef1234567890abcdef1234567890abcdef1234567890",
  "changed": true,
  "locations": [
    {
      "id": 1,
      "latitude": -1.9441,
      "longitude": 30.0619,
      "code": "KGL-001",
      "custom_name": "Kigali Downtown",
      "google_place_name": "Kigali, Rwanda",
      "province": "Kigali",
      "district": "Nyarugenge"
    }
  ],
  "deleted_ids": [5],
  "page": 1,
  "limit": 20,
  "total": 3
}
```

**Response when invalid hash is provided:**
```json
{
  "hash": "current_latest_hash",
  "changed": false,
  "locations": [],
  "deleted_ids": [],
  "message": "Invalid hash: hash not found in database. Please sync without hash parameter to get all data."
}
```
Note: Returns HTTP 200 (not an error) with empty data and message, allowing client to handle gracefully.

---

### 4. Manual Merge (Admin/Debug)
**POST** `/merge`

Manually trigger a merge operation (usually automatic).

**Response:**
```json
{
  "message": "Merge completed successfully"
}
```

---

### 5. Get Unmerged Batches (Debug)
**GET** `/changes/unmerged`

Returns unmerged change batches (for debugging).

---

## Client Implementation Flow

### Step 1: Initial Setup (First Time)

**Option A: Using sync endpoints without hash (Recommended)**
```javascript
// 1. Fetch all routes with pagination (no hash needed)
GET /routes/hash?page=1&limit=20
Response: { 
  "hash": "abc123...",  // Latest hash returned
  "changed": true,
  "routes": [...],
  "deleted_ids": [],
  "page": 1,
  "limit": 20,
  "total": 100
}

// 2. Store hash and routes
localStorage.setItem('main_hash', response.hash)
// Store routes locally

// 3. Fetch remaining pages if needed
for (let page = 2; page <= Math.ceil(response.total / response.limit); page++) {
  GET /routes/hash?page={page}&limit=20
}

// 4. Repeat for locations
GET /locations/hash?page=1&limit=20
// Same pagination logic
```

**Option B: Using main-hash endpoint**
```javascript
// 1. Get the latest hash from server
GET /main-hash
Response: { "hash": "abc123...", ... }

// 2. Store hash locally
localStorage.setItem('main_hash', 'abc123...')

// 3. Fetch all initial data (use regular endpoints)
GET /routes?page=1&limit=100
GET /locations?page=1&limit=100

// Store all routes and locations locally
```

### Step 2: Regular Sync (Periodic or On-Demand)

```javascript
// 1. Get stored hash
const currentHash = localStorage.getItem('main_hash')

// 2. Check for route changes
GET /routes/hash?hash={currentHash}&page=1&limit=20

// 3. Handle response
if (response.changed === false) {
  // No changes, you're up to date!
  console.log('Routes are up to date')
} else {
  // Has changes
  // Update local routes with response.routes
  // Remove routes with IDs in response.deleted_ids
  // Update stored hash
  localStorage.setItem('main_hash', response.hash)
  
  // If paginated, fetch more pages
  if (response.total > response.limit) {
    // Fetch remaining pages
    for (let page = 2; page <= Math.ceil(response.total / response.limit); page++) {
      GET /routes/hash?hash={currentHash}&page={page}&limit=20
    }
  }
}

// 4. Repeat for locations
GET /locations/hash?hash={currentHash}&page=1&limit=20
// Same handling logic
```

### Step 3: Error Handling

```javascript
// Check response for invalid hash message
if (response.message && response.message.includes("Invalid hash")) {
  // Your hash is invalid (maybe database was reset)
  // Sync without hash to get all data
  GET /routes/hash?page=1&limit=20  // No hash parameter
  GET /locations/hash?page=1&limit=20  // No hash parameter
  // Update stored hash from response
  localStorage.setItem('main_hash', response.hash)
}

// If you get 500 Internal Server Error
if (response.status === 500) {
  // Server error, retry later
  // Keep your current hash, don't update
}
```

---

## Complete Example (JavaScript/TypeScript)

```typescript
class ChangeTrackingClient {
  private baseUrl: string;
  private currentHash: string | null = null;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
    this.currentHash = localStorage.getItem('main_hash');
  }

  // Initialize - get all routes/locations with hash (no hash needed)
  async initialize(): Promise<void> {
    try {
      // Fetch all routes without hash (returns all routes + latest hash)
      const routesResponse = await fetch(`${this.baseUrl}/routes/hash?page=1&limit=100`);
      const routesData = await routesResponse.json();
      
      // Fetch all locations without hash
      const locationsResponse = await fetch(`${this.baseUrl}/locations/hash?page=1&limit=100`);
      const locationsData = await locationsResponse.json();
      
      // Store hash (both should have same hash)
      this.currentHash = routesData.hash;
      localStorage.setItem('main_hash', this.currentHash);
      
      // Store initial data
      // ... store routesData.routes and locationsData.locations locally
      
      console.log('Initialized with hash:', this.currentHash);
    } catch (error) {
      console.error('Failed to initialize:', error);
      throw error;
    }
  }

  // Sync routes (with or without hash)
  async syncRoutes(useHash: boolean = true): Promise<{ routes: any[], deletedIds: number[] }> {
    if (!this.currentHash && useHash) {
      // If no hash and we need hash, do initial sync without hash
      useHash = false;
    }

    const allRoutes: any[] = [];
    const allDeletedIds: number[] = [];
    let page = 1;
    const limit = 20;

    while (true) {
      try {
        // Build URL - with or without hash
        const url = useHash 
          ? `${this.baseUrl}/routes/hash?hash=${this.currentHash}&page=${page}&limit=${limit}`
          : `${this.baseUrl}/routes/hash?page=${page}&limit=${limit}`;

        const response = await fetch(url);

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        
        // Check for invalid hash message
        if (data.message && data.message.includes("Invalid hash")) {
          // Invalid hash, fetch all without hash
          console.warn('Invalid hash, fetching all routes...');
          useHash = false;
          page = 1; // Reset to first page
          continue;
        }

        if (useHash && !data.changed) {
          // No changes when using hash
          break;
        }

        // Collect data
        allRoutes.push(...data.routes);
        allDeletedIds.push(...data.deleted_ids);

        // Update hash
        this.currentHash = data.hash;
        localStorage.setItem('main_hash', this.currentHash);

        // Check if more pages
        if (page * limit >= data.total) {
          break;
        }
        page++;
      } catch (error) {
        console.error('Sync error:', error);
        throw error;
      }
    }

    return {
      routes: allRoutes,
      deletedIds: [...new Set(allDeletedIds)] // Remove duplicates
    };
  }

  // Sync locations (with or without hash)
  async syncLocations(useHash: boolean = true): Promise<{ locations: any[], deletedIds: number[] }> {
    if (!this.currentHash && useHash) {
      // If no hash and we need hash, do initial sync without hash
      useHash = false;
    }

    const allLocations: any[] = [];
    const allDeletedIds: number[] = [];
    let page = 1;
    const limit = 20;

    while (true) {
      try {
        // Build URL - with or without hash
        const url = useHash 
          ? `${this.baseUrl}/locations/hash?hash=${this.currentHash}&page=${page}&limit=${limit}`
          : `${this.baseUrl}/locations/hash?page=${page}&limit=${limit}`;

        const response = await fetch(url);

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        
        // Check for invalid hash message
        if (data.message && data.message.includes("Invalid hash")) {
          // Invalid hash, fetch all without hash
          console.warn('Invalid hash, fetching all locations...');
          useHash = false;
          page = 1; // Reset to first page
          continue;
        }

        if (useHash && !data.changed) {
          // No changes when using hash
          break;
        }

        allLocations.push(...data.locations);
        allDeletedIds.push(...data.deleted_ids);

        this.currentHash = data.hash;
        localStorage.setItem('main_hash', this.currentHash);

        if (page * limit >= data.total) {
          break;
        }
        page++;
      } catch (error) {
        console.error('Sync error:', error);
        throw error;
      }
    }

    return {
      locations: allLocations,
      deletedIds: [...new Set(allDeletedIds)]
    };
  }

  // Full sync (routes + locations)
  async syncAll(useHash: boolean = true): Promise<void> {
    const routesData = await this.syncRoutes(useHash);
    const locationsData = await this.syncLocations(useHash);

    // Update your local database/cache
    // Update routes
    routesData.routes.forEach(route => {
      // Update or insert route
    });
    routesData.deletedIds.forEach(id => {
      // Delete route with this ID
    });

    // Update locations
    locationsData.locations.forEach(location => {
      // Update or insert location
    });
    locationsData.deletedIds.forEach(id => {
      // Delete location with this ID
    });
  }
}

// Usage
const client = new ChangeTrackingClient('http://localhost:8080');

// First time - initial sync (no hash needed)
await client.syncAll(false); // false = fetch all data without hash

// Regular sync (call periodically, e.g., every 5 minutes) - with hash
setInterval(async () => {
  try {
    await client.syncAll(true); // true = use hash for incremental sync
    console.log('Sync completed');
  } catch (error) {
    console.error('Sync failed:', error);
  }
}, 5 * 60 * 1000); // 5 minutes
```

---

## Important Notes

1. **Hash Storage**: Always store the hash locally (localStorage, database, etc.)
2. **Pagination**: Always handle pagination - changes might span multiple pages
3. **Deleted IDs**: Always process `deleted_ids` to remove deleted records
4. **Error Recovery**: If hash is invalid (400), reset to latest hash from `/main-hash`
5. **Routes vs Locations**: Routes include `origin_id` and `destination_id` references only (not full location objects). Fetch locations separately.
6. **Merge Timing**: Hashes are merged automatically:
   - Every 2 hours after last edit, OR
   - After 10 minutes of no edits
7. **Hash Format**: Hash is always 64 hex characters (SHA-256)

---

## Testing with cURL

```bash
# 1. Initial sync - Get all routes without hash (with pagination)
curl "http://localhost:8080/routes/hash?page=1&limit=20"
# Returns: All routes + latest hash

# 2. Initial sync - Get all locations without hash (with pagination)
curl "http://localhost:8080/locations/hash?page=1&limit=20"
# Returns: All locations + latest hash

# 3. Get latest hash (alternative method)
curl http://localhost:8080/main-hash

# 4. Sync routes with hash (incremental sync)
curl "http://localhost:8080/routes/hash?hash=YOUR_HASH&page=1&limit=20"
# Returns: Only changed routes since hash

# 5. Sync locations with hash (incremental sync)
curl "http://localhost:8080/locations/hash?hash=YOUR_HASH&page=1&limit=20"
# Returns: Only changed locations since hash

# 6. Manual merge (admin)
curl -X POST http://localhost:8080/merge
```

---

## Best Practices

1. **Initial Load**: On first app start, use `/routes/hash` and `/locations/hash` **without hash parameter** to get all data with pagination + latest hash
2. **Periodic Sync**: Sync every 5-10 minutes in background using hash parameter
3. **On-Demand Sync**: Sync when user manually refreshes
4. **Error Handling**: Always handle 400 (invalid hash) by resetting - call endpoint without hash to get all data again
5. **Pagination**: Always fetch all pages when `changed: true` or when doing initial sync
6. **Local Updates**: Update local storage/database atomically after successful sync
7. **Hash Storage**: Always store the hash from response to use in next sync

