# Complete Route Operations Guide

This guide covers all available operations for managing routes in the CavGo Trips API.

## Overview

The route management system provides comprehensive CRUD operations, advanced search and filtering, and analytics capabilities.

## API Endpoints

### Core CRUD Operations

#### 1. Create Route
```
POST /routes
```
**Description**: Create a new route with origin, destination, and optional waypoints.

**Request Body**:
```json
{
  "name": "Kigali to Musanze",
  "origin_id": 1,
  "destination_id": 2,
  "route_price": 25.50,
  "distance_meters": 85000,
  "estimated_duration_seconds": 7200,
  "city_route": false,
  "google_route_id": "optional_google_id",
  "waypoints": [
    {
      "location_id": 3,
      "order": 1,
      "price": 5.00
    }
  ]
}
```

**Response**: `201 Created` with the created route object.

#### 2. Get All Routes
```
GET /routes
```
**Description**: Get all routes with optional search, filtering, and pagination.

**Query Parameters**:
- `origin` - Search by origin location name
- `destination` - Search by destination location name
- `city_route` - Filter by city route status (`true`/`false`)
- `origin_province` - Filter by origin province
- `destination_province` - Filter by destination province
- `page` - Page number (default: 1)
- `limit` - Items per page (default: 20, max: 100)

**Example**:
```bash
GET /routes?origin=kigali&city_route=true&page=1&limit=10
```

#### 3. Get Single Route
```
GET /routes/{id}
```
**Description**: Get a specific route by ID with all relationships.

**Response**: `200 OK` with route object including origin, destination, and waypoints.

#### 4. Update Route
```
PUT /routes/{id}
```
**Description**: Update an existing route.

**Request Body**:
```json
{
  "name": "Updated Route Name",
  "route_price": 30.00,
  "distance_meters": 90000,
  "estimated_duration_seconds": 7800,
  "city_route": true
}
```

**Response**: `200 OK` with the updated route object.

#### 5. Delete Route
```
DELETE /routes/{id}
```
**Description**: Delete a route and all its waypoints.

**Response**: `200 OK` with success message.

### Advanced Search and Filtering

#### 6. Search by Origin/Destination
```
GET /routes?origin={term}&destination={term}
```
**Description**: Search routes by origin and/or destination location names.

**Features**:
- Case-insensitive partial matching
- Searches both `custom_name` and `google_place_name` fields
- Can be combined with other filters and pagination

#### 7. Filter by City Route Status
```
GET /routes?city_route={true|false}
```
**Description**: Filter routes by city route status.

#### 8. Filter by Provinces
```
GET /routes?origin_province={province}&destination_province={province}
```
**Description**: Filter routes by origin and/or destination provinces.

### Range-Based Filtering

#### 9. Filter by Price Range
```
GET /routes/price-range?min_price={price}&max_price={price}
```
**Description**: Get routes within a specific price range.

**Query Parameters**:
- `min_price` - Minimum price (optional)
- `max_price` - Maximum price (optional)
- `page` - Page number (optional)
- `limit` - Items per page (optional)

**Examples**:
```bash
# Routes between $10 and $50
GET /routes/price-range?min_price=10&max_price=50

# Routes under $30
GET /routes/price-range?max_price=30

# Routes over $20 with pagination
GET /routes/price-range?min_price=20&page=1&limit=10
```

#### 10. Filter by Distance Range
```
GET /routes/distance-range?min_distance={meters}&max_distance={meters}
```
**Description**: Get routes within a specific distance range.

**Query Parameters**:
- `min_distance` - Minimum distance in meters (optional)
- `max_distance` - Maximum distance in meters (optional)
- `page` - Page number (optional)
- `limit` - Items per page (optional)

**Examples**:
```bash
# Routes between 10km and 100km
GET /routes/distance-range?min_distance=10000&max_distance=100000

# Routes under 50km
GET /routes/distance-range?max_distance=50000

# Routes over 20km with pagination
GET /routes/distance-range?min_distance=20000&page=1&limit=10
```

### Analytics and Statistics

#### 11. Get Route Statistics
```
GET /routes/statistics
```
**Description**: Get comprehensive statistics about all routes.

**Response**:
```json
{
  "total_routes": 45,
  "city_routes": 15,
  "non_city_routes": 30,
  "average_price": 28.75,
  "average_distance_meters": 65000,
  "price_range": {
    "min": 5.00,
    "max": 150.00
  },
  "distance_range": {
    "min": 5000,
    "max": 200000
  }
}
```

## Response Formats

### Standard Route Object
```json
{
  "id": 1,
  "name": "Kigali to Musanze",
  "distance_meters": 85000,
  "estimated_duration_seconds": 7200,
  "google_route_id": "abc123",
  "origin_id": 1,
  "destination_id": 2,
  "route_price": 25.50,
  "city_route": false,
  "created_at": "2024-01-15T10:30:00Z",
  "updated_at": "2024-01-15T10:30:00Z",
  "origin": {
    "id": 1,
    "custom_name": "Kigali City",
    "province": "Kigali",
    "district": "Gasabo"
  },
  "destination": {
    "id": 2,
    "custom_name": "Musanze Town",
    "province": "North",
    "district": "Musanze"
  },
  "waypoints": [
    {
      "id": 1,
      "route_id": 1,
      "location_id": 3,
      "order": 1,
      "price": 5.00,
      "location": {
        "id": 3,
        "custom_name": "Waypoint Location"
      }
    }
  ]
}
```

### Paginated Response
```json
{
  "routes": [...],
  "pagination": {
    "page": 1,
    "limit": 20,
    "total": 45,
    "total_pages": 3,
    "has_next": true,
    "has_prev": false,
    "next_page": 2,
    "prev_page": null
  }
}
```

## Error Handling

### Common Error Responses

#### 400 Bad Request
```json
{
  "error": "Invalid request body"
}
```

#### 404 Not Found
```json
{
  "error": "Route not found"
}
```

#### 409 Conflict
```json
{
  "error": "A route with the same origin and destination already exists"
}
```

#### 500 Internal Server Error
```json
{
  "error": "Internal server error"
}
```

## Usage Examples

### Complete CRUD Workflow

```bash
# 1. Create a route
POST /routes
{
  "name": "Kigali to Musanze",
  "origin_id": 1,
  "destination_id": 2,
  "route_price": 25.50,
  "distance_meters": 85000,
  "estimated_duration_seconds": 7200,
  "city_route": false
}

# 2. Get the created route
GET /routes/1

# 3. Update the route
PUT /routes/1
{
  "route_price": 30.00,
  "distance_meters": 90000
}

# 4. Search for similar routes
GET /routes?origin=kigali&city_route=false

# 5. Get routes in price range
GET /routes/price-range?min_price=20&max_price=40

# 6. Delete the route
DELETE /routes/1
```

### Advanced Search Examples

```bash
# Complex search with multiple filters
GET /routes?origin=kigali&city_route=true&origin_province=kigali&page=1&limit=10

# Search by destination with pagination
GET /routes?destination=musanze&page=2&limit=5

# Filter by provinces only
GET /routes?origin_province=kigali&destination_province=north

# Get statistics
GET /routes/statistics
```

## Best Practices

1. **Use Pagination**: Always use pagination for large datasets
2. **Combine Filters**: Use multiple filters to narrow down results
3. **Validate Input**: Ensure all required fields are provided when creating/updating routes
4. **Handle Errors**: Implement proper error handling for all API calls
5. **Use Statistics**: Use the statistics endpoint to understand your data distribution

## Performance Considerations

1. **Database Indexes**: Ensure proper indexes on searchable fields
2. **Pagination**: Use pagination to limit result sets
3. **Efficient Queries**: The API uses optimized queries with proper JOINs
4. **Caching**: Consider caching frequently accessed data

## Security Notes

1. **Input Validation**: All inputs are validated at multiple layers
2. **SQL Injection Protection**: Uses parameterized queries
3. **Error Handling**: Sensitive information is not exposed in error messages 