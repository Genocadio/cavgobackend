# Trip Mapping Documentation

## API Trip Structure → GraphQL Trip Mapping

### Source: API Trip Response
```json
{
  "id": 327,
  "route_id": 171,
  "vehicle_id": 74,
  "vehicle": {
    "id": 74,
    "company_id": 1,
    "company_name": "Tech Innovators Ltd",
    "capacity": 29,
    "license_plate": "CAVGO LTD",
    "driver": {
      "id": 0,
      "name": "",
      "phone": ""
    }
  },
  "status": "SCHEDULED",
  "departure_time": 1763493000,
  "completion_time": null,
  "seats": 29,
  "price": 371,
  "remaining_distance_to_destination": null,
  "route": {
    "id": 171,
    "distance_meters": 12100,
    "origin": {
      "id": 69,
      "latitude": -1.9500331457477693,
      "longitude": 30.125579905436233,
      "google_place_name": "KG 194 St, Kigali, Rwanda",
      "custom_name": "Kimironko Gare"
    },
    "destination": {
      "id": 6,
      "latitude": -1.9409615285609572,
      "longitude": 30.044097706532565,
      "google_place_name": "Nyabugogo Bus Station St, Kigali, Rwanda",
      "custom_name": "Nyabugogo Gare"
    }
  },
  "waypoints": [
    {
      "id": 839,
      "order": 1,
      "price": 60,
      "is_passed": false,
      "passed_timestamp": null,
      "remaining_distance": null,
      "location": {
        "id": 89,
        "latitude": -1.9515253299317101,
        "longitude": 30.12202306171454,
        "google_place_name": "KG 11 Ave, Kigali, Rwanda",
        "custom_name": "KIE 1 Kimironko"
      }
    }
  ]
}
```

### Destination: GraphQL Trip
```json
{
  "id": "327",
  "car": { /* resolved by GraphQL resolver */ },
  "driver": null,
  "startTime": "2025-11-18T19:10:00.000Z",
  "endTime": null,
  "origin": {
    "placename": "Kimironko Gare",  // custom_name preferred over google_place_name
    "latitude": -1.9500331457477693,
    "longitude": 30.125579905436233,
    "passed": false,  // false for SCHEDULED/IN_PROGRESS, true otherwise
    "passedTimestamp": null,  // ISO string if passed, null otherwise
    "remainingDistance": null,  // trip.remaining_distance_to_destination / 1000 (km)
    "fare": null
  },
  "destination": {
    "placename": "Nyabugogo Gare",  // custom_name preferred
    "latitude": -1.9409615285609572,
    "longitude": 30.044097706532565,
    "passed": false,  // true only if status === "COMPLETED"
    "passedTimestamp": null,
    "remainingDistance": null,  // trip.remaining_distance_to_destination / 1000 (km) if not passed
    "fare": 371  // trip.price
  },
  "waypoints": [
    {
      "placename": "KIE 1 Kimironko",  // from waypoint.location.custom_name
      "latitude": -1.9515253299317101,  // from waypoint.location.latitude
      "longitude": 30.12202306171454,  // from waypoint.location.longitude
      "passed": false,  // from waypoint.is_passed
      "passedTimestamp": null,  // waypoint.passed_timestamp (Unix timestamp, number)
      "remainingDistance": null,  // waypoint.remaining_distance
      "fare": 60  // waypoint.price
    }
  ],
  "distance": 12.1,  // trip.route.distance_meters / 1000 (km)
  "status": "SCHEDULED",
  "departureTime": "2025-11-18T19:10:00.000Z",
  "remainingSeats": 29,
  "totalRevenue": 371
}
```

## Field Mapping Details

### Origin & Destination
- **Source**: `trip.route.origin` and `trip.route.destination`
- **placename**: `custom_name` > `google_place_name` > `place_name` > "Unknown Origin/Destination"
- **latitude/longitude**: From route origin/destination, defaults to 0 if missing
- **passed**: 
  - Origin: `true` if status !== "SCHEDULED" && status !== "IN_PROGRESS"
  - Destination: `true` only if status === "COMPLETED"
- **passedTimestamp**: ISO string if passed, `null` otherwise
- **remainingDistance**: `trip.remaining_distance_to_destination / 1000` (converted to km), `null` if destination passed
- **fare**: 
  - Origin: always `null`
  - Destination: `trip.price`

### Waypoints
- **Source**: `trip.waypoints` array (sorted by `order` field)
- **placename**: `waypoint.location.custom_name` > `waypoint.location.google_place_name` > "Unknown Location"
- **latitude/longitude**: From `waypoint.location.latitude` and `waypoint.location.longitude`
- **passed**: From `waypoint.is_passed` (boolean)
- **passedTimestamp**: From `waypoint.passed_timestamp` (Unix timestamp as number, not converted to ISO)
- **remainingDistance**: From `waypoint.remaining_distance` (meters, not converted)
- **fare**: From `waypoint.price`

### Trip Distance
- **Source**: `trip.route.distance_meters`
- **Value**: `distance_meters / 1000` (converted to kilometers)

### Driver
- **Source**: `trip.vehicle.driver`
- **Driverless detection**: If `driver.id === 0` AND `driver.name === ""` (or empty), trip is driverless
- **Result**: `null` if driverless or driver not found

### Car
- **Source**: `trip.vehicle` object (preferred) or fetched from API using `trip.vehicle_id`
- **Mapping**: Converted from `ApiTripVehicle` to `ApiVehicle` format, then to GraphQL Car

## Important Notes

1. **Origin is ALWAYS present**: Even if `trip.route.origin` is missing, a default origin object is created
2. **Destination is ALWAYS present**: Even if `trip.route.destination` is missing, a default destination object is created
3. **Waypoints are sorted**: By `order` field before mapping
4. **Distance units**: All distances converted to kilometers (meters / 1000)
5. **Location names**: Always prefer `custom_name` over `google_place_name` or `place_name`
6. **Waypoint timestamps**: Kept as Unix timestamp (number), not converted to ISO string


