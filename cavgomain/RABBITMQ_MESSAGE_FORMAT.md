# RabbitMQ Message Format - Updated

## 📥 Messages RECEIVED by Backend (From Vehicles)

### Combined Location & Status Updates

**Queue Name:** `vehicle.location.updates`

**Purpose:** Vehicles publish combined location and status updates in a single message

---

#### ✅ ONLINE Status (Actively Transmitting)

Vehicle is on a trip and actively transmitting location:

```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1761765840449,
  "current_latitude": -1.9468107540160418,
  "current_longitude": 30.11626107618212,
  "current_speed": 25.5,
  "accuracy": 5.0,
  "bearing": 180.0
}
```

**What Backend Does:**
- ✅ Saves location to `vehicle_locations` table
- ✅ Updates vehicle's `lastOnlineAt` timestamp
- ✅ Vehicle is marked as online

---

#### ✅ READY Status (Available for Assignment)

Vehicle is powered on, GPS fixed, waiting for trip assignment:

```json
{
  "status": "READY",
  "car_id": "17",
  "timestamp": 1761765840449,
  "current_latitude": -1.9468107540160418,
  "current_longitude": 30.11626107618212,
  "current_speed": 0.0,
  "accuracy": 5.0
}
```

**What Backend Does:**
- ✅ Saves location to `vehicle_locations` table
- ✅ Updates vehicle's `lastOnlineAt` timestamp
- ✅ Vehicle is marked as online
- ℹ️ Treated same as ONLINE for tracking purposes

**Difference from ONLINE:**
- Semantic: "Ready for work" vs "Actively working"
- Typically speed = 0.0 (parked/waiting)

---

#### ❌ OFFLINE Status (No Location Data)

Vehicle is shutting down or unavailable:

```json
{
  "status": "OFFLINE",
  "car_id": "17",
  "timestamp": 1761765837341,
  "current_latitude": null,
  "current_longitude": null,
  "current_speed": null
}
```

**What Backend Does:**
- ⏩ **Skips** saving location (since coordinates are null)
- ✅ Updates vehicle's `lastOnlineAt` timestamp
- ℹ️ Vehicle status determined by 30-minute rule

---

### Field Mapping

| JSON Field | Java Field | Type | Required | Notes |
|-----------|-----------|------|----------|-------|
| `status` | `status` | String | ✅ Yes | "ONLINE", "READY", or "OFFLINE" |
| `car_id` | `carId` | String | ✅ Yes | Vehicle database ID (as string) |
| `timestamp` | `timestamp` | Long | ✅ Yes | Unix timestamp in milliseconds |
| `current_latitude` | `currentLatitude` | Double | ⚠️ Required if ONLINE/READY | GPS latitude, null if OFFLINE |
| `current_longitude` | `currentLongitude` | Double | ⚠️ Required if ONLINE/READY | GPS longitude, null if OFFLINE |
| `current_speed` | `currentSpeed` | Double | ❌ Optional | Speed in m/s, can be null |
| `accuracy` | `accuracy` | Double | ❌ Optional | GPS accuracy in meters, defaults to 0.0 |
| `bearing` | `bearing` | Double | ❌ Optional | Direction in degrees (0-360), can be null |

---

## 📤 Messages PUBLISHED by Backend (To Vehicles)

### Settings Updates

**Exchange Name:** `vehicle.settings.exchange`  
**Exchange Type:** Topic Exchange  
**Routing Key Pattern:** `vehicle.settings.{vehicleId}`

**Example Routing Keys:**
- `vehicle.settings.17` (for vehicle with database ID 17)
- `vehicle.settings.123` (for vehicle with database ID 123)

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

**When Published:**
- Automatically when settings are updated via `PUT /main/vehicles/{id}/settings`

**How Vehicles Subscribe:**
Vehicles should subscribe using their **vehicle ID** (from database):
```
Exchange: vehicle.settings.exchange
Routing Key: vehicle.settings.{your_vehicle_id}
Queue: (create your own queue and bind it)
```

**Important:**
- ✅ Routing key uses vehicle **ID** (number): `vehicle.settings.17`
- ✅ Message body includes **license plate** (string): `"ABC123"`
- ✅ Vehicle knows its ID from the `car_id` field in location messages

---

### Vehicle Events

**Exchange:** `vehicle.events` (fanout)

#### 1.1 Vehicle CREATE Event

```json
{
  "event": "CREATE",
  "data": {
    "id": 123,
    "companyId": 1,
    "companyName": "ABC Transport",
    "make": "Toyota",
    "model": "Camry",
    "capacity": 4,
    "licensePlate": "ABC-123",
    "vehicleType": "SEDAN",
    "status": "AVAILABLE",
    "createdAt": "2024-01-01T10:00:00",
    "updatedAt": "2024-01-01T10:00:00",
    "driver": null,
    "initialPassword": null
  }
}
```

#### 1.2 Vehicle UPDATE Event

```json
{
  "event": "UPDATE",
  "data": {
    "id": 123,
    "companyId": 1,
    "companyName": "ABC Transport",
    "make": "Toyota",
    "model": "Camry",
    "capacity": 4,
    "licensePlate": "ABC-123",
    "vehicleType": "SEDAN",
    "status": "OCCUPIED",
    "createdAt": "2024-01-01T10:00:00",
    "updatedAt": "2024-01-01T11:00:00",
    "driver": {
      "id": 456,
      "companyId": 1,
      "companyName": "ABC Transport",
      "firstName": "John",
      "lastName": "Doe",
      "email": "john.doe@example.com",
      "phone": "+1234567890",
      "status": "ACTIVE",
      "dateOfBirth": "1990-01-01",
      "address": "123 Main St",
      "role": "DRIVER",
      "licenseNumber": "DL123456",
      "licenseExpiry": "2025-12-31",
      "createdAt": "2024-01-01T09:00:00",
      "updatedAt": "2024-01-01T09:00:00",
      "vehicle": null
    },
    "initialPassword": null
  }
}
```

#### 1.3 Vehicle DELETE Event

```json
{
  "event": "DELETE",
  "data": {
    "vehicleId": 123
  }
}
```

#### 1.4 Driver Assignment Event

```json
{
  "event": "DRIVER_ASSIGNMENT",
  "data": {
    "vehicleId": 123,
    "driverId": 456
  }
}
```

**Event Types:**
- `CREATE` - New vehicle created
- `UPDATE` - Vehicle updated (status change, etc.)
- `DELETE` - Vehicle deleted
- `DRIVER_ASSIGNMENT` - Driver assigned/unassigned to/from vehicle

**Note:** Driver assignment events are published as part of vehicle events, not driver events.

---

### Driver Events

**Exchange:** `driver.events` (fanout)

#### 2.1 Driver CREATE Event

```json
{
  "event": "CREATE",
  "data": {
    "id": 456,
    "companyId": 1,
    "companyName": "ABC Transport",
    "firstName": "John",
    "lastName": "Doe",
    "email": "john.doe@example.com",
    "phone": "+1234567890",
    "status": "ACTIVE",
    "dateOfBirth": "1990-01-01",
    "address": "123 Main St",
    "role": "DRIVER",
    "licenseNumber": "DL123456",
    "licenseExpiry": "2025-12-31",
    "createdAt": "2024-01-01T09:00:00",
    "updatedAt": "2024-01-01T09:00:00",
    "vehicle": {
      "id": 123,
      "companyId": 1,
      "companyName": "ABC Transport",
      "make": "Toyota",
      "model": "Camry",
      "capacity": 4,
      "licensePlate": "ABC-123",
      "vehicleType": "SEDAN",
      "status": "OCCUPIED",
      "createdAt": "2024-01-01T10:00:00",
      "updatedAt": "2024-01-01T10:00:00",
      "driver": null,
      "initialPassword": null
    }
  }
}
```

#### 2.2 Driver UPDATE Event

```json
{
  "event": "UPDATE",
  "data": {
    "id": 456,
    "companyId": 1,
    "companyName": "ABC Transport",
    "firstName": "John",
    "lastName": "Doe",
    "email": "john.doe@example.com",
    "phone": "+1234567890",
    "status": "ACTIVE",
    "dateOfBirth": "1990-01-01",
    "address": "123 Main St",
    "role": "DRIVER",
    "licenseNumber": "DL123456",
    "licenseExpiry": "2025-12-31",
    "createdAt": "2024-01-01T09:00:00",
    "updatedAt": "2024-01-01T12:00:00",
    "vehicle": null
  }
}
```

#### 2.3 Driver DELETE Event

```json
{
  "event": "DELETE",
  "data": {
    "driverId": 456
  }
}
```

**Event Types:**
- `CREATE` - New driver created
- `UPDATE` - Driver updated
- `DELETE` - Driver deleted

**Note:** Only drivers (users with `role: "DRIVER"`) trigger driver events.

---

## 🔄 Processing Logic

### Backend Processing Flow

```
┌─────────────────────────────────────┐
│  Receive Message from Queue         │
│  vehicle.location.updates           │
└──────────────┬──────────────────────┘
               │
               ▼
      ┌────────────────┐
      │ Parse car_id   │
      │ Find Vehicle   │
      └────────┬───────┘
               │
               ▼
      ┌─────────────────┐
      │ Check "status"  │
      └────────┬────────┘
               │
      ┌────────┴────────┐
      │                 │
      ▼                 ▼
┌──────────┐      ┌──────────┐
│ OFFLINE  │      │  ONLINE  │
└────┬─────┘      └─────┬────┘
     │                  │
     ▼                  ▼
┌─────────────┐   ┌──────────────────┐
│ Skip saving │   │ Check if lat/lng │
│ location    │   │ are not null     │
└──────┬──────┘   └────────┬─────────┘
       │                   │
       │                   ▼
       │          ┌─────────────────┐
       │          │ Save location   │
       │          │ to database     │
       │          └────────┬────────┘
       │                   │
       └───────┬───────────┘
               ▼
     ┌──────────────────┐
     │ Update vehicle   │
     │ lastOnlineAt     │
     └──────────────────┘
```

---

## 🎯 Important Notes

### 1. **OFFLINE Messages Still Update Timestamp**
Even when status is "OFFLINE", we update `lastOnlineAt` to record when the offline message was received. The actual online/offline determination is based on:
- **Online**: `lastOnlineAt` is within the last 30 minutes
- **Offline**: `lastOnlineAt` is older than 30 minutes

### 2. **Location Only Saved When ONLINE**
Location data is **only saved** when:
- ✅ Status is "ONLINE"
- ✅ `current_latitude` is not null
- ✅ `current_longitude` is not null

### 3. **car_id vs license_plate**
- Incoming messages use **`car_id`** (vehicle database ID)
- Settings messages use **`licensePlate`** for routing
- Make sure your vehicle knows both its ID and license plate

### 4. **Default Values**
- If `current_speed` is null, defaults to `0.0`
- If `accuracy` is null, defaults to `0.0`
- `bearing` can be null (optional)

---

## 🧪 Testing Examples

### Test ONLINE Message

Publish to queue `vehicle.location.updates`:

```json
{
  "status": "ONLINE",
  "car_id": "1",
  "timestamp": 1730300000000,
  "current_latitude": -1.2921,
  "current_longitude": 36.8219,
  "current_speed": 25.5
}
```

**Expected Result:**
- Location saved to database
- Vehicle lastOnlineAt updated
- Vehicle.isOnline() returns `true`

---

### Test OFFLINE Message

Publish to queue `vehicle.location.updates`:

```json
{
  "status": "OFFLINE",
  "car_id": "1",
  "timestamp": 1730300100000,
  "current_latitude": null,
  "current_longitude": null,
  "current_speed": null
}
```

**Expected Result:**
- No location saved (nulls ignored)
- Vehicle lastOnlineAt updated
- After 30 minutes, Vehicle.isOnline() returns `false`

---

## 🔧 RabbitMQ Setup Commands

### Create Queue (if not auto-created)

```bash
# Using rabbitmqadmin (if installed)
rabbitmqadmin declare queue name=vehicle.location.updates durable=true

# Or via Management UI
# Go to http://localhost:15672
# Queues tab → Add a new queue
# Name: vehicle.location.updates
# Durability: Durable
```

### Publish Test Message (Python Example)

```python
import pika
import json
import time

connection = pika.BlockingConnection(
    pika.ConnectionParameters('localhost')
)
channel = connection.channel()

# ONLINE message
online_message = {
    "status": "ONLINE",
    "car_id": "17",
    "timestamp": int(time.time() * 1000),
    "current_latitude": -1.9468107540160418,
    "current_longitude": 30.11626107618212,
    "current_speed": 15.5
}

channel.basic_publish(
    exchange='',
    routing_key='vehicle.location.updates',
    body=json.dumps(online_message),
    properties=pika.BasicProperties(
        content_type='application/json',
        delivery_mode=2  # make message persistent
    )
)

print("✅ Published ONLINE message")
connection.close()
```

---

## 📋 Quick Reference

### What Vehicles Should Do

1. **When Running/Moving:**
   - Publish ONLINE messages with GPS coordinates
   - Frequency: Every 5-30 seconds recommended
   
2. **When Stopping/Shutting Down:**
   - Publish OFFLINE message with null coordinates
   - This immediately signals the backend

3. **Settings Updates:**
   - Subscribe to `vehicle.settings.{your_car_id}`
   - Listen for logout/devmode/deactivate changes

### What Backend Does

- ✅ Stores ONLINE messages with valid coordinates
- ⏩ Skips storing OFFLINE messages (coordinates are null)
- ✅ Always updates lastOnlineAt timestamp
- ✅ Determines online/offline based on 30-minute threshold
- ✅ Auto-deletes location records older than 48 hours

---

## ⚠️ Error Handling

The listener handles these errors gracefully:

- **Invalid car_id format:** Logs error, skips message
- **Vehicle not found:** Logs error, skips message  
- **Null coordinates on ONLINE:** Logs warning, updates timestamp only
- **Unknown status:** Logs warning, skips message

All errors are logged but don't crash the listener.

