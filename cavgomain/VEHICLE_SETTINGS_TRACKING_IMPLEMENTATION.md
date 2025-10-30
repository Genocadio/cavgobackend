# Vehicle Settings and Location Tracking Implementation

## Overview

This implementation adds comprehensive vehicle settings management, real-time location tracking, and online/offline status monitoring to the vehicle management system.

## Key Features Implemented

### 1. Vehicle Settings
- **Logout Control**: Ensures only one client per vehicle (single device login)
- **Dev Mode**: Toggle development mode for vehicles
- **Deactivate**: Flag to deactivate vehicles

### 2. Location Tracking
- Stores vehicle location data for 48 hours
- Tracks: latitude, longitude, speed, accuracy, bearing, timestamp
- Automatic cleanup of old location data

### 3. Online/Offline Status
- Vehicles are considered online if they've sent data within the last 30 minutes
- Status determined by location updates or explicit status messages
- Last online timestamp tracked

### 4. RabbitMQ Integration
- Real-time location and status updates from vehicles
- Settings changes published to vehicles

## Database Schema Changes

### New Tables

#### vehicle_settings
- `id` (PK)
- `vehicle_id` (FK to vehicles, unique)
- `logout` (boolean, default: true)
- `devmode` (boolean, default: false)
- `deactivate` (boolean, default: false)
- Timestamps (created_at, updated_at)

#### vehicle_locations
- `id` (PK)
- `vehicle_id` (FK to vehicles)
- `latitude` (double)
- `longitude` (double)
- `speed` (double, meters/second)
- `accuracy` (double, meters)
- `bearing` (double, nullable, degrees)
- `timestamp` (long, milliseconds since epoch)
- `recorded_at` (datetime, when received)
- Index on `recorded_at` for efficient cleanup

### Modified Tables

#### vehicles
- Added `last_online_at` (datetime) - tracks last activity
- Added OneToOne relationship to `vehicle_settings`

## API Endpoints

### Vehicle Settings

#### Get Vehicle Settings
```
GET /main/vehicles/{id}/settings
```
Returns the current settings for a vehicle.

**Response:**
```json
{
  "id": 1,
  "vehicleId": 123,
  "logout": false,
  "devmode": true,
  "deactivate": false
}
```

#### Update Vehicle Settings
```
PUT /main/vehicles/{id}/settings
```
Updates vehicle settings and publishes changes to RabbitMQ.

**Request Body:**
```json
{
  "logout": true,
  "devmode": false,
  "deactivate": false
}
```

### Vehicle Location

#### Get Location History
```
GET /main/vehicles/{id}/locations?since=2025-10-30T10:00:00
```
Returns location history for the past 48 hours (or filtered by `since` parameter).

**Response:**
```json
[
  {
    "id": 1,
    "vehicleId": 123,
    "latitude": 40.7128,
    "longitude": -74.0060,
    "speed": 15.5,
    "accuracy": 5.0,
    "bearing": 180.0,
    "timestamp": 1730284800000,
    "recordedAt": "2025-10-30T10:00:00"
  }
]
```

#### Get Latest Location
```
GET /main/vehicles/{id}/location/latest
```
Returns the most recent location for a vehicle.

### Enhanced Vehicle Response

The standard vehicle endpoints now include:
```json
{
  "id": 123,
  "licensePlate": "ABC123",
  "isOnline": true,
  "lastOnlineAt": "2025-10-30T10:30:00",
  "lastLocation": null
  // ... other vehicle fields
}
```

**Note:** `lastLocation` is null in standard responses for performance. Use the dedicated location endpoints to fetch location data.

## RabbitMQ Configuration

### Queues to Publish To (from vehicles)

#### Location Updates
**Queue:** `vehicle.location.updates`

**Message Format:**
```json
{
  "licensePlate": "ABC123",
  "latitude": 40.7128,
  "longitude": -74.0060,
  "speed": 15.5,
  "accuracy": 5.0,
  "bearing": 180.0,
  "timestamp": 1730284800000
}
```

#### Status Updates
**Queue:** `vehicle.status.updates`

**Message Format:**
```json
{
  "licensePlate": "ABC123",
  "status": "Online"
}
```
or
```json
{
  "licensePlate": "ABC123",
  "status": "Offline"
}
```

### Topic to Subscribe To (from backend)

#### Settings Changes
**Exchange:** `vehicle.settings.exchange`
**Routing Key Pattern:** `vehicle.settings.{vehicleId}`

**Example:** For vehicle with ID 17, subscribe to routing key: `vehicle.settings.17`

**Message Format:**
```json
{
  "licensePlate": "ABC123",
  "logout": true,
  "devmode": false,
  "deactivate": false,
  "appmode": true,
  "simulate": false
}
```

**Note:** The routing key uses the vehicle **ID**, but the message body contains the **license plate** for identification.

## Login Flow with Single Client Enforcement

### Vehicle Login Process

1. Vehicle sends login request with credentials and new public key
2. Backend checks if `settings.logout == true`
3. If `logout == false`, login is rejected (another client already logged in)
4. If `logout == true`, login succeeds and:
   - Public key is updated
   - `logout` is set to `false`
   - `lastOnlineAt` is updated

### Example Login Request
```
POST /main/vehicles/login
```

**Request:**
```json
{
  "companyCode": "COMP001",
  "licensePlate": "ABC123",
  "password": "123456",
  "pubKey": "new-public-key"
}
```

**Success:** Returns vehicle details
**Failure:** Throws exception if `logout == false`

### Logout Process

To allow a new client to login, update settings via API:
```
PUT /main/vehicles/{id}/settings
```

**Request:**
```json
{
  "logout": true
}
```

This allows the next login attempt to succeed.

## Scheduled Tasks

### Location Cleanup
- **Frequency:** Every hour
- **Action:** Deletes location records older than 48 hours

### Offline Vehicle Check
- **Frequency:** Every 5 minutes
- **Action:** Informational logging (actual status determined by `isOnline()` method)

## Configuration

### Application Properties (application.yml)
```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

### Docker Configuration (application-docker.yml)
```yaml
spring:
  rabbitmq:
    host: rabbitmq
    port: 5672
    username: guest
    password: guest
```

## Dependencies Added

```gradle
implementation 'org.springframework.boot:spring-boot-starter-amqp'
```

## Important Notes

1. **Single Client Per Vehicle:** The `logout` setting enforces this. Only when `logout == true` can a vehicle login.

2. **Online/Offline Status:** A vehicle is considered online if `lastOnlineAt` is within the last 30 minutes.

3. **Location History:** Automatically limited to 48 hours. Older records are deleted by the scheduled task.

4. **Settings Not Pushed:** Settings are NOT automatically pushed to vehicles. Vehicles must fetch settings via API endpoint. Settings changes are published to RabbitMQ for vehicles that are listening.

5. **Location Write-Only from Vehicles:** Vehicles cannot fetch their own location history via the endpoints they use. Location is written via RabbitMQ and read via REST API by the backend/admin.

## Next Steps

After implementation, you need to:

1. **Reload Gradle Dependencies:**
   ```bash
   ./gradlew clean build
   ```

2. **Start RabbitMQ:**
   ```bash
   docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
   ```

3. **Run Database Migrations:**
   The application will auto-create the new tables on startup (using `ddl-auto: update`).

4. **Configure External Publishers:**
   - Update vehicle clients to publish to `vehicle.location.updates` and `vehicle.status.updates`
   - Configure vehicles to listen on `vehicle.settings.{licensePlate}` routing keys

## Testing

### Test Settings Update
```bash
# Get current settings
curl http://localhost:8060/main/vehicles/1/settings

# Update settings
curl -X PUT http://localhost:8060/main/vehicles/1/settings \
  -H "Content-Type: application/json" \
  -d '{"logout": true, "devmode": true}'
```

### Test Location Retrieval
```bash
# Get latest location
curl http://localhost:8060/main/vehicles/1/location/latest

# Get location history
curl http://localhost:8060/main/vehicles/1/locations
```

### Test Single Client Login
```bash
# First login (should succeed)
curl -X POST http://localhost:8060/main/vehicles/login \
  -H "Content-Type: application/json" \
  -d '{"companyCode": "COMP001", "licensePlate": "ABC123", "password": "123456", "pubKey": "key1"}'

# Second login attempt (should fail)
curl -X POST http://localhost:8060/main/vehicles/login \
  -H "Content-Type: application/json" \
  -d '{"companyCode": "COMP001", "licensePlate": "ABC123", "password": "123456", "pubKey": "key2"}'

# Set logout to true
curl -X PUT http://localhost:8060/main/vehicles/1/settings \
  -H "Content-Type: application/json" \
  -d '{"logout": true}'

# Try login again (should succeed)
curl -X POST http://localhost:8060/main/vehicles/login \
  -H "Content-Type: application/json" \
  -d '{"companyCode": "COMP001", "licensePlate": "ABC123", "password": "123456", "pubKey": "key2"}'
```

## Files Created/Modified

### New Files
- `entity/VehicleSettings.java`
- `entity/VehicleLocation.java`
- `repository/VehicleSettingsRepository.java`
- `repository/VehicleLocationRepository.java`
- `dto/request/VehicleSettingsUpdateDto.java`
- `dto/response/VehicleSettingsResponseDto.java`
- `dto/response/VehicleLocationResponseDto.java`
- `dto/message/VehicleLocationMessage.java`
- `dto/message/VehicleStatusMessage.java`
- `dto/message/VehicleSettingsMessage.java`
- `config/RabbitMQConfig.java`
- `messaging/VehicleLocationListener.java`
- `messaging/VehicleStatusListener.java`
- `messaging/VehicleSettingsPublisher.java`
- `scheduled/VehicleMaintenanceScheduler.java`

### Modified Files
- `entity/Vehicle.java` - Added settings relationship and lastOnlineAt
- `service/VehicleService.java` - Added settings/location methods, modified login
- `controller/VehicleController.java` - Added new endpoints
- `dto/response/VehicleResponseDto.java` - Added online status and location fields
- `CavgomainApplication.java` - Enabled scheduling
- `build.gradle` - Added RabbitMQ dependency
- `application.yml` - Added RabbitMQ configuration
- `application-docker.yml` - Added RabbitMQ configuration

