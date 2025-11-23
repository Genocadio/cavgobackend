# Vehicle Status Types

## 📊 Supported Status Values

The backend accepts the following status values in location update messages:

| Status | Description | Location Data | Use Case |
|--------|-------------|---------------|----------|
| **`ONLINE`** | Vehicle is active and transmitting | Required | During trips, actively moving |
| **`READY`** | Vehicle is ready for assignment | Required | Parked, GPS fixed, waiting for trip |
| **`OFFLINE`** | Vehicle is offline/shutdown | Optional (null) | Powered off, no GPS, end of shift |

---

## 🎯 Status Definitions

### 1️⃣ ONLINE Status

**When to Use:**
- Vehicle is actively on a trip
- Driver is logged in and moving
- Continuous location tracking

**Message Example:**
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

**Backend Actions:**
- ✅ Saves location to database
- ✅ Updates `lastOnlineAt` timestamp
- ✅ Vehicle marked as online (within 30 min)

---

### 2️⃣ READY Status

**When to Use:**
- Vehicle is powered on and connected
- GPS has a fix and location is available
- Driver is logged in but no active trip
- Waiting for trip assignment
- Parked but available

**Message Example:**
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

**Backend Actions:**
- ✅ Saves location to database
- ✅ Updates `lastOnlineAt` timestamp
- ✅ Vehicle marked as online (within 30 min)
- ℹ️ Treated same as ONLINE for location tracking

**Difference from ONLINE:**
- Semantic meaning: "Ready for work" vs "Actively working"
- Typically speed = 0.0 (parked)
- Used for dispatcher/assignment systems

---

### 3️⃣ OFFLINE Status

**When to Use:**
- Vehicle is shutting down
- GPS lost (no fix)
- Driver logged out
- End of shift
- Vehicle maintenance mode

**Message Example:**
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

**Backend Actions:**
- ⏩ **Skips** saving location (coordinates are null)
- ✅ Updates `lastOnlineAt` timestamp
- ⏳ After 30 minutes of no updates, `vehicle.isOnline()` returns `false`

---

## 🔄 Status Transition Examples

### Typical Daily Flow

```
1. Vehicle Startup (Morning)
   ↓
   OFFLINE → READY
   - Driver turns on vehicle
   - GPS acquires fix
   - Sends location with status: READY

2. Trip Accepted
   ↓
   READY → ONLINE
   - Driver accepts trip
   - Starts driving
   - Sends location with status: ONLINE

3. Trip Completed
   ↓
   ONLINE → READY
   - Trip ends
   - Vehicle parked
   - Sends location with status: READY

4. End of Shift
   ↓
   READY → OFFLINE
   - Driver logs out
   - Vehicle shut down
   - Sends status: OFFLINE
```

---

## 📨 Complete Message Examples

### Morning Start (OFFLINE → READY)

```json
{
  "status": "READY",
  "car_id": "17",
  "timestamp": 1730347200000,
  "current_latitude": -1.2921,
  "current_longitude": 36.8219,
  "current_speed": 0.0,
  "accuracy": 8.0
}
```

---

### Accepting Trip (READY → ONLINE)

```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1730347500000,
  "current_latitude": -1.2921,
  "current_longitude": 36.8219,
  "current_speed": 0.0,
  "accuracy": 5.0,
  "bearing": null
}
```

---

### During Trip (ONLINE)

```json
{
  "status": "ONLINE",
  "car_id": "17",
  "timestamp": 1730347800000,
  "current_latitude": -1.2850,
  "current_longitude": 36.8280,
  "current_speed": 18.5,
  "accuracy": 5.0,
  "bearing": 45.0
}
```

---

### Trip Complete (ONLINE → READY)

```json
{
  "status": "READY",
  "car_id": "17",
  "timestamp": 1730348100000,
  "current_latitude": -1.2800,
  "current_longitude": 36.8350,
  "current_speed": 0.0,
  "accuracy": 5.0
}
```

---

### End of Day (READY → OFFLINE)

```json
{
  "status": "OFFLINE",
  "car_id": "17",
  "timestamp": 1730361600000,
  "current_latitude": null,
  "current_longitude": null,
  "current_speed": null
}
```

---

## 🏗️ Backend Processing Logic

```
┌─────────────────────────────┐
│ Receive Message             │
│ status: "ONLINE/READY/..."  │
└──────────┬──────────────────┘
           │
           ▼
    ┌──────────────┐
    │ Parse Status │
    └──────┬───────┘
           │
    ┌──────┴────────────────────┐
    │                           │
    ▼                           ▼
┌─────────┐              ┌──────────────┐
│ OFFLINE │              │ ONLINE/READY │
└────┬────┘              └──────┬───────┘
     │                          │
     ▼                          ▼
┌────────────┐         ┌──────────────────┐
│ Skip save  │         │ Check coordinates│
│ location   │         └────────┬─────────┘
└─────┬──────┘                  │
      │                  ┌──────┴──────┐
      │                  │             │
      │                  ▼             ▼
      │            ┌──────────┐  ┌──────────┐
      │            │ Valid    │  │ NULL     │
      │            │ lat/lng  │  │ coords   │
      │            └────┬─────┘  └────┬─────┘
      │                 │             │
      │                 ▼             │
      │            ┌──────────┐       │
      │            │ Save     │       │
      │            │ location │       │
      │            └────┬─────┘       │
      │                 │             │
      └─────────┬───────┴─────────────┘
                │
                ▼
       ┌─────────────────┐
       │ Update vehicle  │
       │ lastOnlineAt    │
       └─────────────────┘
```

---

## 🎨 Use Case Scenarios

### Scenario 1: Dispatch System Integration

```javascript
// Vehicle status determines availability
if (vehicle.status === "READY") {
  // Show in available vehicles list
  dispatcher.addAvailableVehicle(vehicle);
} else if (vehicle.status === "ONLINE") {
  // Show as "On Trip"
  dispatcher.markAsOccupied(vehicle);
} else if (vehicle.status === "OFFLINE") {
  // Don't show in dispatcher
  dispatcher.removeVehicle(vehicle);
}
```

---

### Scenario 2: Fleet Dashboard

```sql
-- Count vehicles by status
SELECT 
  CASE 
    WHEN last_online_at > NOW() - INTERVAL '30 minutes' THEN 'Active'
    ELSE 'Inactive'
  END as computed_status,
  COUNT(*) as count
FROM vehicles
GROUP BY computed_status;

-- Get ready vehicles (last 5 minutes)
SELECT * FROM vehicles 
WHERE last_online_at > NOW() - INTERVAL '5 minutes';
```

---

### Scenario 3: Real-time Tracking

**ONLINE vehicles** (on trips):
- Update every 5-10 seconds
- High frequency location updates
- Speed and bearing important

**READY vehicles** (waiting):
- Update every 30-60 seconds
- Lower frequency acceptable
- Speed typically 0

**OFFLINE vehicles**:
- No location updates
- Single offline message when shutting down

---

## ⚙️ Configuration Recommendations

### Update Frequencies

| Status | Recommended Frequency | Reason |
|--------|----------------------|--------|
| ONLINE | 5-10 seconds | Real-time tracking during trips |
| READY | 30-60 seconds | Monitoring availability, save bandwidth |
| OFFLINE | One-time | Sent once when shutting down |

---

## 🔍 Backend Status Determination

The backend uses **time-based** online detection:

```java
public boolean isOnline() {
    if (lastOnlineAt == null) {
        return false;
    }
    // Consider vehicle online if last update was within 30 minutes
    return lastOnlineAt.isAfter(LocalDateTime.now().minusMinutes(30));
}
```

**Key Points:**
- Status in message is informational
- Backend determines online/offline based on `lastOnlineAt`
- 30-minute threshold for all status types
- READY and ONLINE both update `lastOnlineAt`

---

## 📋 Quick Reference

### Sending Status Messages

```python
# READY - Vehicle available
publish_location({
    "status": "READY",
    "car_id": "17",
    "timestamp": int(time.time() * 1000),
    "current_latitude": -1.2921,
    "current_longitude": 36.8219,
    "current_speed": 0.0
})

# ONLINE - Active trip
publish_location({
    "status": "ONLINE",
    "car_id": "17",
    "timestamp": int(time.time() * 1000),
    "current_latitude": -1.2850,
    "current_longitude": 36.8280,
    "current_speed": 25.5,
    "bearing": 180.0
})

# OFFLINE - Shutting down
publish_location({
    "status": "OFFLINE",
    "car_id": "17",
    "timestamp": int(time.time() * 1000),
    "current_latitude": None,
    "current_longitude": None,
    "current_speed": None
})
```

---

## ✅ Summary

**3 Status Types:**
1. ✅ **ONLINE** - Active operation, moving
2. ✅ **READY** - Available, waiting for assignment
3. ✅ **OFFLINE** - Shutdown, unavailable

**All statuses update `lastOnlineAt` timestamp**  
**ONLINE and READY save location data**  
**OFFLINE skips location save**  
**30-minute threshold determines online/offline state**

Use these statuses to build sophisticated fleet management and dispatch systems! 🚗📍







