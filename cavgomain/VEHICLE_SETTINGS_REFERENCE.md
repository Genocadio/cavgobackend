# Vehicle Settings Reference

## All Settings Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `logout` | Boolean | `true` | Controls single client login. If `false`, vehicle cannot login (client already connected). Set to `false` on successful login. |
| `devmode` | Boolean | `false` | Development/debug mode for the vehicle |
| `deactivate` | Boolean | `false` | Deactivates the vehicle |
| `appmode` | Boolean | `false` | Single app mode - vehicle operates in single application mode |
| `simulate` | Boolean | `false` | Simulation mode for testing without actual hardware |

---

## API Examples

### Get Vehicle Settings

```bash
GET /main/vehicles/{id}/settings
```

**Response:**
```json
{
  "id": 1,
  "vehicleId": 123,
  "logout": false,
  "devmode": true,
  "deactivate": false,
  "appmode": true,
  "simulate": false
}
```

---

### Update Vehicle Settings

```bash
PUT /main/vehicles/{id}/settings
Content-Type: application/json
```

**Request Body (all fields optional):**
```json
{
  "logout": true,
  "devmode": false,
  "deactivate": false,
  "appmode": true,
  "simulate": false
}
```

**Response:**
```json
{
  "id": 1,
  "vehicleId": 123,
  "logout": true,
  "devmode": false,
  "deactivate": false,
  "appmode": true,
  "simulate": false
}
```

---

### Update Single Field

You can update just one field at a time:

```bash
PUT /main/vehicles/123/settings
Content-Type: application/json

{
  "simulate": true
}
```

All other fields remain unchanged.

---

## RabbitMQ Settings Message

When settings are updated, this message is published to RabbitMQ:

**Exchange:** `vehicle.settings.exchange`  
**Routing Key:** `vehicle.settings.{vehicleId}` (e.g., `vehicle.settings.17`)

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

**Important:** 
- Routing key uses **vehicle ID** (number): `vehicle.settings.17`
- Message contains **license plate** (string): `"ABC123"`
- Vehicles subscribe using their vehicle ID, but identify themselves using license plate in messages

---

## Use Cases

### 1. Single Client Login (`logout`)

**Scenario:** Ensure only one device can connect to a vehicle at a time.

```bash
# Vehicle tries to login
POST /main/vehicles/login
{
  "companyCode": "COMP001",
  "licensePlate": "ABC123",
  "password": "123456",
  "pubKey": "device-key-1"
}

# ✅ Success if logout=true
# After login, logout automatically set to false

# Second device tries to login
POST /main/vehicles/login
{
  "companyCode": "COMP001",
  "licensePlate": "ABC123",
  "password": "123456",
  "pubKey": "device-key-2"
}

# ❌ Fails with "Vehicle is already logged in"
```

**To allow new login:**
```bash
PUT /main/vehicles/123/settings
{
  "logout": true
}
```

---

### 2. Development Mode (`devmode`)

**Scenario:** Enable debug logging or special features for testing.

```bash
PUT /main/vehicles/123/settings
{
  "devmode": true
}
```

Vehicle receives this setting via RabbitMQ and can enable debug features.

---

### 3. Deactivate Vehicle (`deactivate`)

**Scenario:** Temporarily disable a vehicle from operations.

```bash
PUT /main/vehicles/123/settings
{
  "deactivate": true
}
```

Vehicle should stop accepting trips when this is enabled.

---

### 4. Single App Mode (`appmode`)

**Scenario:** Lock vehicle to run only the designated application.

```bash
PUT /main/vehicles/123/settings
{
  "appmode": true
}
```

Vehicle device operates in kiosk/single-app mode, preventing users from switching apps.

---

### 5. Simulation Mode (`simulate`)

**Scenario:** Test vehicle behavior without actual hardware/GPS.

```bash
PUT /main/vehicles/123/settings
{
  "simulate": true
}
```

Vehicle uses simulated GPS data or other test data instead of real sensors.

---

## Default Values on Vehicle Creation

When a new vehicle is created via `POST /main/vehicles`, these defaults are set:

```json
{
  "logout": true,      // ✅ Allows first login
  "devmode": false,    // ❌ Production mode
  "deactivate": false, // ✅ Active
  "appmode": false,    // ❌ Normal mode
  "simulate": false    // ❌ Real mode
}
```

---

## Common Workflows

### Reset Vehicle for New Driver

```bash
# 1. Force logout current session
PUT /main/vehicles/123/settings
{"logout": true}

# 2. New driver can now login
POST /main/vehicles/login
{...}
```

---

### Enable Test Mode

```bash
# Enable both simulation and dev mode
PUT /main/vehicles/123/settings
{
  "devmode": true,
  "simulate": true
}
```

---

### Production Lock-down

```bash
# Disable all test features, enable app lock
PUT /main/vehicles/123/settings
{
  "devmode": false,
  "simulate": false,
  "appmode": true
}
```

---

## Database Schema

```sql
CREATE TABLE vehicle_settings (
    id BIGSERIAL PRIMARY KEY,
    vehicle_id BIGINT NOT NULL UNIQUE,
    logout BOOLEAN NOT NULL DEFAULT true,
    devmode BOOLEAN NOT NULL DEFAULT false,
    deactivate BOOLEAN NOT NULL DEFAULT false,
    appmode BOOLEAN NOT NULL DEFAULT false,
    simulate BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);
```

---

## Testing

### Test All Settings

```bash
curl -X PUT http://localhost:8060/main/vehicles/1/settings \
  -H "Content-Type: application/json" \
  -d '{
    "logout": true,
    "devmode": true,
    "deactivate": false,
    "appmode": true,
    "simulate": true
  }'
```

### Verify Settings

```bash
curl http://localhost:8060/main/vehicles/1/settings
```

### Check RabbitMQ Message

After updating settings, check the RabbitMQ exchange `vehicle.settings.exchange` with routing key `vehicle.settings.{licensePlate}` to see the published message.

---

## Summary

✅ **5 Boolean Settings** control vehicle behavior  
✅ **Partial Updates** - only send fields you want to change  
✅ **Auto-publish** to RabbitMQ when settings change  
✅ **Default values** set on vehicle creation  
✅ **Per-vehicle** settings (each vehicle has its own)  

All settings are optional in update requests and can be modified independently! 🎉

