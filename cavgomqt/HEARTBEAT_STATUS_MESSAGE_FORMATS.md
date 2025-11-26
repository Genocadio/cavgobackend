# Heartbeat & Status Message Formats

This document describes the supported message formats for vehicle heartbeat and status updates.

## 📡 MQTT Topics

- **`car/{carId}/heartbeat`** - Periodic heartbeat messages from vehicles
- **`car/{carId}/status`** - Status update messages from vehicles

Both topics support the same message formats and forward to RabbitMQ queue `vehicle.location.updates`.

---

## 🎯 Vehicle Status Values

The system supports three status values:

- **`ONLINE`** - Vehicle is active and operational (e.g., currently on a trip)
- **`READY`** - Vehicle is available and ready for trip assignment (idle, waiting for work)
- **`OFFLINE`** - Vehicle is not available (shutdown, maintenance, etc.)

**Note:** Both `ONLINE` and `READY` mark the vehicle as **active** in the registry. Only `OFFLINE` marks it as inactive.

---

## 📋 Supported Message Formats

### Format 1: Full Location Update (ONLINE with GPS)

**Use Case:** Vehicle is online and has GPS location data available

```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1730300000000,
  "current_latitude": -1.9468107540160418,
  "current_longitude": 30.11626107618212,
  "current_speed": 25.5,
  "accuracy": 5.0,
  "bearing": 180.0
}
```

**Result:**
- ✅ Vehicle marked as ONLINE in registry
- ✅ Location data forwarded to RabbitMQ
- ✅ Backend can save location to database

---

### Format 2: Partial Location Update (Some Fields Missing)

**Use Case:** Vehicle has GPS but some sensors unavailable

```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1730300000000,
  "current_latitude": -1.9468107540160418,
  "current_longitude": 30.11626107618212,
  "current_speed": null,
  "accuracy": null,
  "bearing": null
}
```

**Alternative (omit missing fields):**
```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1730300000000,
  "current_latitude": -1.9468107540160418,
  "current_longitude": 30.11626107618212
}
```

**Result:**
- ✅ Vehicle marked as ONLINE
- ✅ Location (lat/lng) saved
- ✅ Missing fields handled gracefully (saved as null)

---

### Format 3: READY Status (Available for Assignment)

**Use Case:** Vehicle is idle and ready to accept trip assignments

```json
{
  "status": "READY",
  "car_id": "17",
  "timestamp": 1730300000000,
  "current_latitude": -1.9468107540160418,
  "current_longitude": 30.11626107618212,
  "current_speed": 0.0
}
```

**Alternative (READY without location):**
```json
{
  "status": "READY",
  "car_id": "17",
  "timestamp": 1730300000000
}
```

**Result:**
- ✅ Vehicle marked as ONLINE/ACTIVE in registry (ready for assignment)
- ✅ Location saved if coordinates provided
- ✅ Backend can identify vehicle as available for dispatch

---

### Format 4: Status-Only Update (No Location)

**Use Case:** Vehicle is online but GPS unavailable or disabled

```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1730300000000
}
```

**Result:**
- ✅ Vehicle marked as ONLINE
- ✅ Timestamp updated in registry
- ✅ No location saved (coordinates are null)

---

### Format 5: OFFLINE Status

**Use Case:** Vehicle is going offline or shutting down

```json
{
  "status": "OFFLINE",
  "car_id": "17",
  "timestamp": 1730300100000,
  "current_latitude": null,
  "current_longitude": null,
  "current_speed": null
}
```

**Alternative (minimal):**
```json
{
  "status": "OFFLINE",
  "car_id": "17",
  "timestamp": 1730300100000
}
```

**Result:**
- ✅ Vehicle marked as OFFLINE
- ✅ `lastOnlineAt` updated with timestamp
- ⚠️ No location saved (as per spec)

---

### Format 6: Simple Heartbeat (Non-JSON)

**Use Case:** Legacy/simple heartbeat ping

**Payload:** Any non-JSON string (e.g., `"alive"`, `"ping"`, etc.)

**Result:**
- ✅ Vehicle marked as ONLINE
- ✅ Timestamp generated automatically
- ✅ Simple status-only message sent to RabbitMQ

---

## 🔧 Field Details

### Required Fields

| Field | Type | Description | Default if Missing |
|-------|------|-------------|-------------------|
| `status` | String | `"ONLINE"`, `"READY"`, or `"OFFLINE"` | `"ONLINE"` |
| `car_id` | String | Vehicle identifier | Extracted from topic |
| `timestamp` | Long | Unix timestamp (milliseconds) | Current server time |

### Optional Fields

| Field | Type | Description | Notes |
|-------|------|-------------|-------|
| `current_latitude` | Double | GPS latitude | Can be null |
| `current_longitude` | Double | GPS longitude | Can be null |
| `current_speed` | Double | Speed in km/h | Can be null |
| `accuracy` | Double | GPS accuracy in meters | Can be null |
| `bearing` | Double | Direction (0-360 degrees) | Can be null |

---

## 📤 RabbitMQ Output Format

All messages are forwarded to RabbitMQ queue `vehicle.location.updates` with the same structure:

```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1730300000000,
  "current_latitude": -1.9468107540160418,
  "current_longitude": 30.11626107618212,
  "current_speed": 25.5,
  "accuracy": 5.0,
  "bearing": 180.0
}
```

**Note:** Missing fields will be `null` in the JSON output.

---

## 🎯 Processing Logic

### MQTT Handler Flow

1. **Receive message** from `car/{carId}/heartbeat` or `car/{carId}/status`
2. **Extract car ID** from topic
3. **Try to parse as JSON**
   - If successful → Use provided fields
   - If failed → Generate simple ONLINE status
4. **Apply defaults:**
   - `status` → defaults to `"ONLINE"` if missing
   - `car_id` → uses topic car ID if missing
   - `timestamp` → uses current time if missing
5. **Log received data:**
   - Status
   - Whether location data is present
   - Which optional fields are provided
6. **Update local registry:**
   - Set vehicle online/offline status
   - Update last heartbeat timestamp
7. **Forward to RabbitMQ:**
   - Publish to `vehicle.location.updates`
   - Include all fields (null for missing data)

### Backend Processing (Expected)

According to your specification, the backend should:

1. ✅ **Always update `lastOnlineAt`** (even for OFFLINE messages)
2. ✅ **Only save location when:**
   - Status is `"ONLINE"` or `"READY"`
   - `current_latitude` is not null
   - `current_longitude` is not null
3. ✅ **Determine online status by:**
   - Online: `lastOnlineAt` within last 30 minutes
   - Offline: `lastOnlineAt` older than 30 minutes
4. ✅ **Vehicle status interpretation:**
   - `ONLINE` → Vehicle is active (e.g., on trip)
   - `READY` → Vehicle is idle and available for assignment
   - `OFFLINE` → Vehicle is unavailable

---

## 🧪 Testing Examples

### Test 1: Full Location Data
**Publish to:** `car/17/heartbeat`
```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1730300000000,
  "current_latitude": -1.2921,
  "current_longitude": 36.8219,
  "current_speed": 25.5,
  "accuracy": 5.0,
  "bearing": 90.0
}
```

**Expected Logs:**
```
=== VEHICLE HEARTBEAT ===
Car ID: 17
  - Status: ONLINE
  - Has Location: true
  - Has Speed: true
  - Has Accuracy: true
  - Has Bearing: true
📤 === PUBLISHING VEHICLE LOCATION TO RABBITMQ ===
  - Location: (-1.2921, 36.8219)
  - Speed: 25.5 km/h
  - Accuracy: 5.0 m
  - Bearing: 90.0°
✅ SUCCESS: Vehicle location published to RabbitMQ
```

---

### Test 2: Partial Location Data
**Publish to:** `car/17/heartbeat`
```json
{
  "status": "ONLINE",
  "timestamp": 1730300000000,
  "current_latitude": -1.2921,
  "current_longitude": 36.8219
}
```

**Expected Logs:**
```
=== VEHICLE HEARTBEAT ===
Car ID: 17
  - Status: ONLINE
  - Has Location: true
  - Has Speed: false
  - Has Accuracy: false
  - Has Bearing: false
📤 === PUBLISHING VEHICLE LOCATION TO RABBITMQ ===
  - Location: (-1.2921, 36.8219)
  - Speed: not provided
  - Accuracy: not provided
  - Bearing: not provided
✅ SUCCESS: Vehicle location published to RabbitMQ
```

---

### Test 3: Status Only (No Location)
**Publish to:** `car/17/heartbeat`
```json
{
  "status": "ONLINE",
  "timestamp": 1730300000000
}
```

**Expected Logs:**
```
=== VEHICLE HEARTBEAT ===
Car ID: 17
  - Status: ONLINE
  - Has Location: false
  - Has Speed: false
  - Has Accuracy: false
  - Has Bearing: false
📤 === PUBLISHING VEHICLE LOCATION TO RABBITMQ ===
  - Location: NOT PROVIDED (status update only)
✅ SUCCESS: Vehicle status published to RabbitMQ
```

---

### Test 4: READY Status (Available for Assignment)
**Publish to:** `car/17/status`
```json
{
  "status": "READY",
  "car_id": "17",
  "timestamp": 1730300000000,
  "current_latitude": -1.2921,
  "current_longitude": 36.8219,
  "current_speed": 0.0
}
```

**Expected Logs:**
```
=== CAR STATUS UPDATE ===
Car ID: 17
  - Status: READY
  - Has Location: true
  - Has Speed: true
  - Has Accuracy: false
  - Has Bearing: false
📤 === PUBLISHING VEHICLE LOCATION TO RABBITMQ ===
  - Location: (-1.2921, 36.8219)
  - Speed: 0.0 km/h
  - Accuracy: not provided
  - Bearing: not provided
✅ SUCCESS: Vehicle location published to RabbitMQ
```

**Note:** Vehicle is marked as ONLINE/ACTIVE in registry (ready for trip assignment)

---

### Test 5: OFFLINE Message
**Publish to:** `car/17/status`
```json
{
  "status": "OFFLINE",
  "timestamp": 1730300100000
}
```

**Expected Logs:**
```
=== CAR STATUS UPDATE ===
Car ID: 17
  - Status: OFFLINE
  - Has Location: false
  - Has Speed: false
  - Has Accuracy: false
  - Has Bearing: false
📤 === PUBLISHING VEHICLE LOCATION TO RABBITMQ ===
  - Location: NOT PROVIDED (status update only)
✅ SUCCESS: Vehicle status published to RabbitMQ
```

---

### Test 6: Simple Heartbeat (Non-JSON)
**Publish to:** `car/17/heartbeat`
```
alive
```

**Expected Logs:**
```
=== VEHICLE HEARTBEAT ===
Car ID: 17
Payload: alive
  - Unable to parse as JSON, treating as simple heartbeat
📤 === PUBLISHING VEHICLE LOCATION TO RABBITMQ ===
  - Location: NOT PROVIDED (status update only)
✅ SUCCESS: Vehicle status published to RabbitMQ
```

---

## 💡 Best Practices

### For Vehicle Clients

1. **Always include timestamp** - Use device time for better tracking
2. **Send full location when available** - Include all GPS data
3. **Use OFFLINE before shutdown** - Helps backend track status
4. **Regular heartbeats** - Send every 30-60 seconds when online
5. **Omit optional fields** - Don't send null values unnecessarily

### Example Heartbeat Loop (Pseudocode)

```python
def send_heartbeat():
    # Determine vehicle status
    if is_on_trip():
        status = "ONLINE"
    elif is_available_for_assignment():
        status = "READY"
    else:
        status = "OFFLINE"
    
    message = {
        "status": status,
        "car_id": str(vehicle_id),
        "timestamp": int(time.time() * 1000)
    }
    
    # Add GPS if available
    if gps.is_available():
        message["current_latitude"] = gps.latitude
        message["current_longitude"] = gps.longitude
        
        # Add optional fields if available
        if gps.has_speed():
            message["current_speed"] = gps.speed
        if gps.has_accuracy():
            message["accuracy"] = gps.accuracy
        if gps.has_bearing():
            message["bearing"] = gps.bearing
    
    mqtt_client.publish(f"car/{vehicle_id}/heartbeat", json.dumps(message))

# Run every 30 seconds
schedule.every(30).seconds.do(send_heartbeat)
```

---

## 🔍 Troubleshooting

### Issue: Location not being saved
- ✅ Check that `status` is `"ONLINE"`
- ✅ Ensure both `current_latitude` and `current_longitude` are not null
- ✅ Verify coordinates are valid numbers

### Issue: Vehicle showing as offline
- ✅ Check that heartbeats are being sent regularly (< 30 minutes)
- ✅ Verify timestamp is current (not old or future)
- ✅ Ensure MQTT connection is stable

### Issue: Optional fields not working
- ✅ All optional fields support null values
- ✅ Backend should handle null gracefully
- ✅ Check JSON serialization doesn't strip nulls

---

## 📊 Summary

✅ **Flexible message format** - From minimal to full location data  
✅ **Graceful degradation** - Works with missing fields  
✅ **Backward compatible** - Supports non-JSON heartbeats  
✅ **Clear logging** - Shows exactly what data was received  
✅ **Automatic defaults** - Fills in missing required fields  

The system is designed to handle real-world scenarios where GPS, sensors, or network may be unreliable! 🚗💚

