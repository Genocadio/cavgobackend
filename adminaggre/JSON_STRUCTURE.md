# JSON Structure for Drivers and Vehicles

## Vehicle (Car) JSON Structure

### Required Fields for Creating/Updating a Vehicle:

```json
{
  "id": "string (required, unique identifier)",
  "companyId": "string (required, company identifier)",
  "companyCode": "string (required, company code like 'RWA')",
  "plate": "string (required, license plate number)",
  "model": "string (required, e.g., 'Corolla')",
  "make": "string (required, e.g., 'Toyota')",
  "capacity": "integer (required, number of seats)",
  "connectionStatus": "string (required, enum: 'ONLINE' | 'OFFLINE')",
  "operationalStatus": "string (required, enum: 'WORKING' | 'MAINTENANCE' | 'DEACTIVATED')",
  "currentLocation": {
    "latitude": "number (required)",
    "longitude": "number (required)",
    "address": "string (optional)",
    "timestamp": "string (required, ISO 8601 format)",
    "bearing": "number (optional, degrees 0-360)",
    "speed": "number (optional, km/h)"
  },
  "lastUpdated": "string (required, ISO 8601 format)"
}
```

### Example Vehicle JSON:

```json
{
  "id": "1",
  "companyId": "COMP001",
  "companyCode": "RWA",
  "plate": "RAC 123A",
  "model": "Corolla",
  "make": "Toyota",
  "capacity": 4,
  "connectionStatus": "ONLINE",
  "operationalStatus": "WORKING",
  "currentLocation": {
    "latitude": -1.9441,
    "longitude": 30.0619,
    "address": "Kigali City Tower, KN 4 Ave",
    "timestamp": "2024-01-15T10:30:00.000Z",
    "bearing": 45.5,
    "speed": 0.0
  },
  "lastUpdated": "2024-01-15T10:30:00.000Z"
}
```

### Computed Fields (Returned by API, not required in input):
- `activeTrip`: Trip object (if car has active trip)
- `latestTripCompletionTime`: String (timestamp of last completed trip)
- `isOnline`: Boolean (derived from connectionStatus)
- `driver`: Driver object (if car has assigned driver)

---

## Driver JSON Structure

### Required Fields for Creating/Updating a Driver:

```json
{
  "id": "string (required, unique identifier)",
  "name": "string (required, driver full name)",
  "phone": "string (required, phone number with country code)",
  "email": "string (required, email address)",
  "licenseNumber": "string (required, driver's license number)",
  "rating": "number (required, float 0.0-5.0)",
  "totalTrips": "integer (required, total number of trips completed)"
}
```

### Example Driver JSON:

```json
{
  "id": "DRV001",
  "name": "John Mugisha",
  "phone": "+250788123456",
  "email": "john.m@example.com",
  "licenseNumber": "LIC001234",
  "rating": 4.8,
  "totalTrips": 245
}
```

### Computed Fields (Returned by API, not required in input):
- `currentCar`: Car object (if driver has active car assignment)
- `totalDistance`: Float (total distance driven today in km)
- `totalRevenue`: Float (total revenue from paid/boarded bookings today)
- `lastTripTimestamp`: String (timestamp of most recent trip)

---

## Field Descriptions

### Vehicle Fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique vehicle identifier |
| `companyId` | String | Yes | Company identifier this vehicle belongs to |
| `companyCode` | String | Yes | Company code (e.g., 'RWA', 'UGA') |
| `plate` | String | Yes | License plate number |
| `model` | String | Yes | Vehicle model (e.g., 'Corolla', 'Camry') |
| `make` | String | Yes | Vehicle manufacturer (e.g., 'Toyota', 'Honda') |
| `capacity` | Integer | Yes | Number of passenger seats |
| `connectionStatus` | Enum | Yes | 'ONLINE' or 'OFFLINE' - connection status |
| `operationalStatus` | Enum | Yes | 'WORKING', 'MAINTENANCE', or 'DEACTIVATED' |
| `currentLocation` | Object | Yes | Current GPS location |
| `currentLocation.latitude` | Float | Yes | Latitude coordinate |
| `currentLocation.longitude` | Float | Yes | Longitude coordinate |
| `currentLocation.address` | String | No | Human-readable address |
| `currentLocation.timestamp` | String | Yes | ISO 8601 timestamp |
| `currentLocation.bearing` | Float | No | Direction in degrees (0-360) |
| `currentLocation.speed` | Float | No | Speed in km/h |
| `lastUpdated` | String | Yes | ISO 8601 timestamp of last update |

### Driver Fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique driver identifier |
| `name` | String | Yes | Driver's full name |
| `phone` | String | Yes | Phone number with country code (e.g., '+250788123456') |
| `email` | String | Yes | Email address |
| `licenseNumber` | String | Yes | Driver's license number |
| `rating` | Float | Yes | Driver rating (0.0 to 5.0) |
| `totalTrips` | Integer | Yes | Total number of trips completed |

---

## Enums

### ConnectionStatus:
- `ONLINE` - Vehicle is connected and online
- `OFFLINE` - Vehicle is disconnected/offline

### OperationalStatus:
- `WORKING` - Vehicle is operational and available
- `MAINTENANCE` - Vehicle is in maintenance
- `DEACTIVATED` - Vehicle is deactivated

---

## Notes

1. **Timestamps**: All timestamp fields should be in ISO 8601 format (e.g., "2024-01-15T10:30:00.000Z")

2. **Location**: The `currentLocation` object is required but can have null values for optional fields like `bearing` and `speed` when vehicle is stationary

3. **Computed Fields**: Fields like `totalDistance`, `totalRevenue`, `isOnline`, `activeTrip`, etc. are computed by the API and should not be sent in create/update requests

4. **IDs**: Both vehicle and driver IDs should be unique across the system

5. **Phone Numbers**: Should include country code prefix (e.g., +250 for Rwanda)

