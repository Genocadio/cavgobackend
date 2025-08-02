# Location API Pagination Guide

This guide explains the pagination functionality implemented for the Location API to handle large datasets efficiently.

## Overview

The Location API now supports pagination for both regular location retrieval and search operations. This allows clients to efficiently handle large datasets (1000+ locations) by requesting data in smaller, manageable chunks.

## Implementation Details

### Repository Layer (`internal/repository/location_repository.go`)

Added two new methods:
- `GetAllPaginated(limit, offset int) ([]models.Location, int64, error)`
- `SearchPaginated(searchTerm string, limit, offset int) ([]models.Location, int64, error)`

Both methods return:
- `[]models.Location`: The paginated results
- `int64`: Total count of matching records
- `error`: Any error that occurred

### Service Layer (`internal/service/location_service.go`)

Added corresponding service methods:
- `GetAllLocationsPaginated(limit, offset int) ([]models.Location, int64, error)`
- `SearchLocationsPaginated(searchTerm string, limit, offset int) ([]models.Location, int64, error)`

### Handler Layer (`internal/handlers/location.go`)

Updated `GetLocations()` to support pagination parameters:
- `page`: Page number (default: 1)
- `limit`: Items per page (default: 20, max: 100)
- `search`: Search term (optional)

### Response Format (`pkg/utils/response.go`)

Added paginated response structure:
```go
type PaginatedResponse struct {
    Data       interface{} `json:"data"`
    Pagination Pagination  `json:"pagination"`
}

type Pagination struct {
    Page       int   `json:"page"`
    Limit      int   `json:"limit"`
    Total      int64 `json:"total"`
    TotalPages int   `json:"total_pages"`
    HasNext    bool  `json:"has_next"`
    HasPrev    bool  `json:"has_prev"`
}
```

## API Usage

### Basic Pagination

```bash
# Get first page with default limit (20)
GET /locations

# Get specific page with custom limit
GET /locations?page=2&limit=10

# Get first page with maximum limit
GET /locations?page=1&limit=100
```

### Search with Pagination

```bash
# Search with pagination
GET /locations?search=NYC&page=1&limit=20

# Search by location code with pagination
GET /locations?search=11001&page=2&limit=15
```

### Parameter Details

- **page** (optional): Page number starting from 1 (default: 1)
- **limit** (optional): Number of items per page (default: 20, max: 100)
- **search** (optional): Search term for filtering by custom name, Google place name, or location code

## Response Examples

### Successful Paginated Response

```json
{
  "data": [
    {
      "id": 1,
      "latitude": 40.7128,
      "longitude": -74.0060,
      "code": "11001",
      "google_place_name": "New York City",
      "custom_name": "NYC Office",
      "province": "New York",
      "district": "Manhattan",
      "place_id": "ChIJOwg_06VPwokRYv534QaPC8g",
      "created_at": "2024-01-01T00:00:00Z",
      "updated_at": "2024-01-01T00:00:00Z"
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 20,
    "total": 150,
    "total_pages": 8,
    "has_next": true,
    "has_prev": false
  }
}
```

### Empty Results

```json
{
  "data": [],
  "pagination": {
    "page": 1,
    "limit": 20,
    "total": 0,
    "total_pages": 0,
    "has_next": false,
    "has_prev": false
  }
}
```

## Pagination Metadata

The `pagination` object provides useful metadata for client applications:

- **page**: Current page number
- **limit**: Number of items per page
- **total**: Total number of matching records
- **total_pages**: Total number of pages
- **has_next**: Whether there's a next page
- **has_prev**: Whether there's a previous page

## Performance Considerations

1. **Database Efficiency**: Uses `LIMIT` and `OFFSET` for efficient pagination
2. **Count Optimization**: Separate count query for accurate total calculation
3. **Maximum Limit**: Capped at 100 items per page to prevent performance issues
4. **Default Limits**: Sensible defaults (20 items per page) for good UX

## Error Handling

- Invalid page numbers (≤ 0) default to page 1
- Invalid limit values (≤ 0) default to 20
- Limit values > 100 are capped at 100
- Invalid search parameters return 400 Bad Request

## Client Implementation Tips

1. **Use has_next/has_prev**: Check these flags before making additional requests
2. **Cache total**: Store the total count to avoid unnecessary requests
3. **Handle empty results**: Always check if data array is empty
4. **URL encoding**: Properly encode search terms in URLs
5. **Progressive loading**: Load pages as needed rather than all at once

## Migration Notes

- Existing clients using `/locations` will now receive paginated responses
- The `data` field contains the actual location array
- Pagination metadata is always included
- Search functionality remains backward compatible 