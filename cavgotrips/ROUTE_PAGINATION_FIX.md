# Route Pagination Fix

## Problem
The `/routes` endpoint was not supporting pagination parameters (`page` and `limit`) and was returning all routes without pagination metadata.

## Solution
Updated the `GetRoutes` handler in `internal/handlers/route.go` to support pagination parameters and return pagination metadata.

## Changes Made

### 1. Updated GetRoutes Handler
**File:** `internal/handlers/route.go`

**Before:**
```go
func (h *RouteHandler) GetRoutes(w http.ResponseWriter, r *http.Request) {
	routes, err := h.service.GetAllRoutes()
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, routes, http.StatusOK)
}
```

**After:**
```go
func (h *RouteHandler) GetRoutes(w http.ResponseWriter, r *http.Request) {
	// Parse query parameters
	queryParams := r.URL.Query()
	
	// Parse pagination parameters with defaults
	page, _ := strconv.Atoi(queryParams.Get("page"))
	if page <= 0 {
		page = 1
	}
	
	limit, _ := strconv.Atoi(queryParams.Get("limit"))
	if limit <= 0 {
		limit = 20 // Default limit
	}
	if limit > 100 {
		limit = 100 // Maximum limit
	}
	
	// Calculate offset
	offset := (page - 1) * limit
	
	routes, total, err := h.service.GetAllRoutesPaginated(limit, offset)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.PaginatedJSONResponse(w, routes, total, page, limit, http.StatusOK)
}
```

## Features Added

1. **Pagination Parameters Support:**
   - `page`: Page number (default: 1)
   - `limit`: Number of items per page (default: 20, max: 100)

2. **Pagination Metadata:**
   The response now includes pagination information:
   ```json
   {
     "data": [...],
     "pagination": {
       "page": 1,
       "limit": 20,
       "total": 50,
       "total_pages": 3,
       "has_next": true,
       "has_prev": false
     }
   }
   ```

3. **Parameter Validation:**
   - Page must be > 0 (defaults to 1)
   - Limit must be > 0 (defaults to 20)
   - Limit is capped at 100

## Usage Examples

### Get first page with 20 items (default)
```bash
curl "http://localhost:8080/routes"
```

### Get first page with 5 items
```bash
curl "http://localhost:8080/routes?page=1&limit=5"
```

### Get second page with 10 items
```bash
curl "http://localhost:8080/routes?page=2&limit=10"
```

## Testing
Use the provided test script to verify pagination functionality:
```bash
./test_pagination_routes.sh
```

## Dependencies
The implementation uses:
- `strconv.Atoi()` for parameter parsing
- `h.service.GetAllRoutesPaginated()` for paginated data retrieval
- `utils.PaginatedJSONResponse()` for formatted response

## Backward Compatibility
The endpoint remains backward compatible - if no pagination parameters are provided, it defaults to page 1 with 20 items per page. 