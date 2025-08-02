# Route Search and Filtering Guide

This guide explains how to use the enhanced route search and filtering capabilities in the CavGo Trips API.

## Overview

The `/routes` endpoint now supports advanced search and filtering options to help you find specific routes based on various criteria.

## API Endpoint

```
GET /routes
```

## Query Parameters

### Search Parameters

- `origin` (string, optional): Search for routes by origin location name
  - Searches in both `custom_name` and `google_place_name` fields
  - Case-insensitive partial matching
  - Example: `?origin=kigali`

- `destination` (string, optional): Search for routes by destination location name
  - Searches in both `custom_name` and `google_place_name` fields
  - Case-insensitive partial matching
  - Example: `?destination=musanze`

### Filter Parameters

- `city_route` (boolean, optional): Filter routes by city route status
  - `true`: Only city routes
  - `false`: Only non-city routes
  - If not provided: No filtering (returns both city and non-city routes)
  - Example: `?city_route=true`

- `origin_province` (string, optional): Filter routes by origin province
  - Case-insensitive partial matching
  - Example: `?origin_province=kigali`

- `destination_province` (string, optional): Filter routes by destination province
  - Case-insensitive partial matching
  - Example: `?destination_province=north`

### Pagination Parameters

- `page` (integer, optional): Page number (default: 1)
  - Must be greater than 0
  - Example: `?page=2`

- `limit` (integer, optional): Number of items per page (default: 20, max: 100)
  - Must be between 1 and 100
  - Example: `?limit=50`

**Note**: When pagination parameters are provided, the response will always include pagination metadata, even if no search or filter parameters are specified.

## Usage Examples

### Basic Search

```bash
# Search for routes with "kigali" in origin
GET /routes?origin=kigali

# Search for routes with "musanze" in destination
GET /routes?destination=musanze

# Search for routes with both origin and destination
GET /routes?origin=kigali&destination=musanze
```

### Filtering

```bash
# Get only city routes
GET /routes?city_route=true

# Get only non-city routes
GET /routes?city_route=false

# Filter by origin province
GET /routes?origin_province=kigali

# Filter by destination province
GET /routes?destination_province=north

# Combine multiple filters
GET /routes?city_route=true&origin_province=kigali&destination_province=north
```

### Search and Filter Combination

```bash
# Search by origin and filter by city route
GET /routes?origin=kigali&city_route=true

# Search by destination and filter by provinces
GET /routes?destination=musanze&origin_province=kigali&destination_province=north

# Complex search with multiple criteria
GET /routes?origin=kigali&destination=musanze&city_route=true&origin_province=kigali&destination_province=north
```

### Pagination

```bash
# Get first page with 20 items (default)
GET /routes

# Get second page with 20 items
GET /routes?page=2

# Get first page with 50 items
GET /routes?limit=50

# Get third page with 10 items
GET /routes?page=3&limit=10
```

### Search with Pagination

```bash
# Search for routes with "kigali" origin, paginated
GET /routes?origin=kigali&page=1&limit=20

# Complex search with pagination
GET /routes?origin=kigali&city_route=true&page=2&limit=50
```

## Response Format

### Without Pagination

```json
{
  "routes": [
    {
      "id": 1,
      "name": "Kigali to Musanze",
      "distance_meters": 85000,
      "estimated_duration_seconds": 7200,
      "route_price": 25.50,
      "city_route": false,
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
      "waypoints": []
    }
  ]
}
```

### With Pagination

```json
{
  "routes": [
    {
      "id": 1,
      "name": "Kigali to Musanze",
      "distance_meters": 85000,
      "estimated_duration_seconds": 7200,
      "route_price": 25.50,
      "city_route": false,
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
      "waypoints": []
    }
  ],
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

## Error Responses

### Invalid Parameters

```json
{
  "error": "Invalid route ID"
}
```

### Server Error

```json
{
  "error": "Internal server error"
}
```

## Notes

1. **Case Insensitive**: All text searches are case-insensitive
2. **Partial Matching**: Text searches use partial matching (LIKE %term%)
3. **Combined Filters**: Multiple filters can be combined using AND logic
4. **Performance**: Pagination is recommended for large datasets
5. **Maximum Limit**: The maximum items per page is 100
6. **Default Values**: If pagination parameters are not provided, all matching results are returned
7. **Pagination Metadata**: Includes `has_next`, `has_prev`, `next_page`, and `prev_page` for easy navigation
8. **Parameter Validation**: Invalid pagination parameters will return a 400 Bad Request error

## Implementation Details

The search and filtering functionality is implemented using:

- **Repository Layer**: Database queries with JOINs and WHERE clauses
- **Service Layer**: Business logic and data processing
- **Handler Layer**: HTTP request parsing and response formatting

The system efficiently handles complex queries by:
- Using database indexes on searchable fields
- Implementing proper JOIN strategies for related data
- Providing both paginated and non-paginated versions of all operations 