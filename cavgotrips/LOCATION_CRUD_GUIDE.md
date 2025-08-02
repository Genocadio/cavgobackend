# Location CRUD Operations Guide

This guide documents the complete CRUD (Create, Read, Update, Delete) operations for locations in the CavgoTrips API.

## Overview

The location management system now supports full CRUD operations with proper validation, error handling, and duplicate checking.

## API Endpoints

### 1. Create Location
- **Method**: `POST`
- **URL**: `/locations`
- **Description**: Create a new location with automatic code generation

**Request Body**:
```json
{
  "latitude": 40.7128,
  "longitude": -74.0060,
  "google_place_name": "New York City",
  "custom_name": "NYC Office",
  "province": "kigali",
  "district": "gasabo"
}
```

**Response**: `201 Created`
```json
{
  "id": 1,
  "latitude": 40.7128,
  "longitude": -74.0060,
  "code": "11001",
  "google_place_name": "New York City",
  "custom_name": "NYC Office",
  "province": "kigali",
  "district": "gasabo",
  "place_id": null,
  "created_at": "2024-01-01T00:00:00Z",
  "updated_at": "2024-01-01T00:00:00Z"
}
```

### 2. Get All Locations
- **Method**: `GET`
- **URL**: `/locations`
- **Description**: Get paginated list of all locations

**Query Parameters**:
- `page` (optional): Page number (default: 1)
- `limit` (optional): Items per page (default: 20, max: 100)
- `search` (optional): Search term for custom name, Google place name, or location code

**Examples**:
```bash
GET /locations
GET /locations?page=1&limit=10
GET /locations?search=NYC
GET /locations?search=11001&page=2&limit=5
```

### 3. Get Location by ID
- **Method**: `GET`
- **URL**: `/locations/{id}`
- **Description**: Get a specific location by its ID

**Response**: `200 OK`
```json
{
  "id": 1,
  "latitude": 40.7128,
  "longitude": -74.0060,
  "code": "11001",
  "google_place_name": "New York City",
  "custom_name": "NYC Office",
  "province": "kigali",
  "district": "gasabo",
  "place_id": null,
  "created_at": "2024-01-01T00:00:00Z",
  "updated_at": "2024-01-01T00:00:00Z"
}
```

### 4. Update Location
- **Method**: `PUT`
- **URL**: `/locations/{id}`
- **Description**: Update an existing location

**Request Body**:
```json
{
  "latitude": 40.7128,
  "longitude": -74.0060,
  "google_place_name": "Updated New York City",
  "custom_name": "Updated NYC Office",
  "province": "kigali",
  "district": "gasabo"
}
```

**Response**: `200 OK` (returns updated location)

**Special Behavior**: 
- If `province` or `district` is changed, a new location code will be automatically generated
- The new code will follow the format: `{new_province_code}{new_district_code}{next_available_location_number}`
- Both `province` and `district` are required when changing administrative area

### 5. Delete Location
- **Method**: `DELETE`
- **URL**: `/locations/{id}`
- **Description**: Delete a location by its ID

**Response**: `200 OK`
```json
{
  "message": "Location deleted successfully"
}
```

## Validation Rules

### Create/Update Validation
1. **Required Fields**:
   - `latitude` and `longitude` must be non-zero
   - Either `custom_name` or `google_place_name` must be provided
   - `province` and `district` are required for code generation

2. **Duplicate Checking**:
   - Custom name must be unique (if provided)
   - Place ID must be unique (if provided)
   - Latitude/longitude combination must be unique
   - Location code must be unique (if provided)

### Update-Specific Validation
- Location must exist before updating
- Duplicate checks exclude the current location being updated
- All validation rules from create operation apply
- **Automatic Code Generation**: If province or district changes, a new location code is automatically generated
- **Province/District Change**: Both province and district must be provided when changing administrative area

## Error Responses

### 400 Bad Request
```json
{
  "error": "Invalid request body"
}
```

### 404 Not Found
```json
{
  "error": "Location not found"
}
```

### 409 Conflict
```json
{
  "error": "location with this custom name already exists"
}
```

### 400 Bad Request (Province/District Change)
```json
{
  "error": "province and district are required when changing location administrative area"
}
```

### 500 Internal Server Error
```json
{
  "error": "Database error message"
}
```

## Location Code Generation

The system automatically generates location codes based on Rwanda's administrative structure:

- **Format**: `{province_code}{district_code}{location_number}`
- **Example**: `11001` = Province 1 (Kigali), District 1 (Gasabo), Location 1

### Automatic Code Generation on Updates

When updating a location, if the province or district changes, the system will automatically generate a new location code:

- **Province Change**: If province changes, a new code is generated for the new province-district combination
- **District Change**: If district changes within the same province, a new code is generated
- **Both Required**: When changing administrative area, both province and district must be provided
- **Sequential Numbering**: The system finds the next available location number within the new province-district combination

**Example Code Changes**:
- Initial: `11001` (Kigali/Gasabo)
- After district change: `12001` (Kigali/Kicukiro)
- After province change: `23001` (North/Musanze)
- After another change: `33001` (East/Kayonza)

### Supported Provinces and Districts

**Kigali (1)**:
- Gasabo (1)
- Kicukiro (2)
- Nyarugenge (3)

**North (2)**:
- Burera (1)
- Gakenke (2)
- Musanze (3)
- Rulindo (4)
- Gicumbi (5)

**East (3)**:
- Bugesera (1)
- Gatsibo (2)
- Kayonza (3)
- Kirehe (4)
- Ngoma (5)
- Nyagatare (6)
- Rwamagana (7)

**South (4)**:
- Gisagara (1)
- Huye (2)
- Kamonyi (3)
- Muhanga (4)
- Nyamagabe (5)
- Nyanza (6)
- Nyaruguru (7)
- Ruhango (8)

**West (5)**:
- Karongi (1)
- Ngororero (2)
- Nyabihu (3)
- Nyamasheke (4)
- Rubavu (5)
- Rusizi (6)
- Rutsiro (7)

## Testing

### Basic CRUD Operations
Use the provided test script to verify all CRUD operations:

```bash
./test_location_crud.sh
```

This script will:
1. Create a new location
2. Retrieve the created location
3. Update the location
4. Verify the update
5. List all locations
6. Search for the location
7. Delete the location
8. Verify deletion
9. Test error cases

### Code Generation Testing
Use the specialized test script to verify automatic code generation:

```bash
./test_location_code_generation.sh
```

This script will:
1. Create a location in Kigali/Gasabo (code: 11001)
2. Update to different district in same province (code: 12001)
3. Update to different province (code: 23001)
4. Update to another province/district combination (code: 33001)
5. Test error cases for incomplete administrative area changes
6. Verify final state and clean up

## Implementation Details

### Repository Layer
- `Update(location *models.Location) error`: Updates location in database
- `Delete(id int64) error`: Deletes location by ID
- `ValidateExists(id int64) error`: Validates location exists

### Service Layer
- `UpdateLocation(id int64, location *models.Location) error`: Business logic for updates
- `DeleteLocation(id int64) error`: Business logic for deletion
- Includes validation and duplicate checking

### Handler Layer
- `GetLocation(w http.ResponseWriter, r *http.Request)`: Get by ID
- `UpdateLocation(w http.ResponseWriter, r *http.Request)`: Update location
- `DeleteLocation(w http.ResponseWriter, r *http.Request)`: Delete location
- Proper error handling and HTTP status codes

### Router Configuration
- `GET /locations/{id}`: Get location by ID
- `PUT /locations/{id}`: Update location
- `DELETE /locations/{id}`: Delete location

## Security Considerations

1. **Input Validation**: All inputs are validated before processing
2. **SQL Injection Protection**: Using GORM with parameterized queries
3. **Error Handling**: Proper error responses without exposing internal details
4. **Duplicate Prevention**: Comprehensive duplicate checking for data integrity

## Performance Considerations

1. **Database Indexes**: Ensure proper indexing on frequently queried fields
2. **Pagination**: All list endpoints support pagination
3. **Efficient Queries**: Optimized database queries with proper joins
4. **Caching**: Consider implementing caching for frequently accessed locations 