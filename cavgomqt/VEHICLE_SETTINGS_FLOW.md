# Vehicle Settings Flow Documentation

This document describes how vehicle settings are propagated from the backend to vehicles via RabbitMQ and MQTT.

---

## 📡 Message Flow

```
Backend Service
    ↓ publishes to
RabbitMQ Exchange: vehicle.settings.exchange
    ↓ routing key: vehicle.settings.{vehicleId}
RabbitMQ Queue: vehicle.settings.queue
    ↓ consumed by
RabbitMQVehicleSettingsListenerService
    ↓ forwards to
MQTT Topic: car/{vehicleId}/settings
    ↓ received by
Vehicle
```

---

## 🔧 Configuration

### RabbitMQ Setup

**Exchange:**
- Name: `vehicle.settings.exchange`
- Type: `topic`
- Durable: `true`

**Queue:**
- Name: `vehicle.settings.queue`
- Durable: `true`
- Binding Pattern: `vehicle.settings.*`

**Routing Key Pattern:**
- Format: `vehicle.settings.{vehicleId}`
- Example: `vehicle.settings.17` for vehicle ID 17

**Dead Letter Queue:**
- Name: `vehicle.settings.queue.dlq`

### MQTT Setup

**Topic Pattern:**
- Format: `car/{vehicleId}/settings`
- Example: `car/17/settings` for vehicle ID 17

**Message Properties:**
- QoS: `1` (at least once delivery)
- Retained: `true` (vehicle receives settings on reconnect)

---

## 📋 Message Format

### Complete Example

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

### Field Descriptions

| Field | Type | Description | Possible Values |
|-------|------|-------------|-----------------|
| `licensePlate` | String | Vehicle license plate for identification | Any string (e.g., "ABC123") |
| `logout` | Boolean | Force vehicle to logout | `true`, `false` |
| `devmode` | Boolean | Enable developer mode | `true`, `false` |
| `deactivate` | Boolean | Deactivate the vehicle | `true`, `false` |
| `appmode` | Boolean | Enable app mode | `true`, `false` |
| `simulate` | Boolean | Enable simulation mode | `true`, `false` |

**Note:** All fields are optional and can be `null`. Only include fields you want to update.

---

## 🚀 Usage Examples

### Example 1: Force Vehicle Logout

**Publish to RabbitMQ:**

```bash
# Using rabbitmqadmin
rabbitmqadmin publish \
  exchange=vehicle.settings.exchange \
  routing_key=vehicle.settings.17 \
  payload='{"licensePlate":"ABC123","logout":true,"devmode":false,"deactivate":false,"appmode":true,"simulate":false}'
```

**Python Example:**

```python
import pika
import json

connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()

vehicle_id = 17
settings = {
    "licensePlate": "ABC123",
    "logout": True,
    "devmode": False,
    "deactivate": False,
    "appmode": True,
    "simulate": False
}

channel.basic_publish(
    exchange='vehicle.settings.exchange',
    routing_key=f'vehicle.settings.{vehicle_id}',
    body=json.dumps(settings),
    properties=pika.BasicProperties(
        content_type='application/json',
        delivery_mode=2  # persistent
    )
)

print(f"✅ Published settings for vehicle {vehicle_id}")
connection.close()
```

**Expected Flow:**

1. Message published to `vehicle.settings.exchange` with routing key `vehicle.settings.17`
2. RabbitMQ routes message to `vehicle.settings.queue`
3. `RabbitMQVehicleSettingsListenerService` consumes the message
4. Service extracts vehicle ID from routing key: `17`
5. Service publishes to MQTT topic: `car/17/settings`
6. Vehicle receives settings and processes them

---

### Example 2: Enable Developer Mode

**Publish to RabbitMQ:**

```json
{
  "licensePlate": "XYZ789",
  "logout": false,
  "devmode": true,
  "deactivate": false,
  "appmode": true,
  "simulate": false
}
```

**Routing Key:** `vehicle.settings.42`

**MQTT Topic:** `car/42/settings`

---

### Example 3: Deactivate Vehicle

**Publish to RabbitMQ:**

```json
{
  "licensePlate": "DEF456",
  "logout": true,
  "devmode": false,
  "deactivate": true,
  "appmode": false,
  "simulate": false
}
```

**Routing Key:** `vehicle.settings.99`

**MQTT Topic:** `car/99/settings`

---

## 📊 Processing Flow Details

### 1. RabbitMQ Message Reception

When a message arrives on `vehicle.settings.queue`:

```java
@RabbitListener(queues = "vehicle.settings.queue")
public void handleVehicleSettings(Message message) {
    // Extract routing key
    String routingKey = message.getMessageProperties().getReceivedRoutingKey();
    
    // Extract vehicle ID from routing key (vehicle.settings.17 → 17)
    Long vehicleId = extractVehicleIdFromRoutingKey(routingKey);
    
    // Parse message body
    VehicleSettingsMessage settings = objectMapper.readValue(payload, VehicleSettingsMessage.class);
    
    // Forward to MQTT
    mqttService.publishVehicleSettings(vehicleId, settings);
}
```

### 2. MQTT Message Publication

The service publishes to MQTT with:

```java
public void publishVehicleSettings(Long vehicleId, VehicleSettingsMessage settings) {
    String topic = "car/" + vehicleId + "/settings";
    String jsonPayload = objectMapper.writeValueAsString(settings);
    
    vehicleSettingsOutboundChannel.send(
        MessageBuilder.withPayload(jsonPayload)
            .setHeader("mqtt_topic", topic)
            .setHeader("mqtt_qos", 1)
            .setHeader("mqtt_retained", true)  // Vehicle gets settings on reconnect
            .build()
    );
}
```

### 3. Console Output

**On RabbitMQ Reception:**

```
📥 === RECEIVED VEHICLE SETTINGS FROM RABBITMQ ===
  - Timestamp: 1730304567890
  - Routing Key: vehicle.settings.17
  - Vehicle ID: 17
  - Payload: {"licensePlate":"ABC123","logout":true,...}
✅ Successfully parsed vehicle settings:
  - License Plate: ABC123
  - Logout: true
  - Devmode: false
  - Deactivate: false
  - Appmode: true
  - Simulate: false
```

**On MQTT Publication:**

```
⚙️  === PUBLISHING VEHICLE SETTINGS TO MQTT ===
  - Timestamp: 1730304567891
  - Vehicle ID: 17
  - License Plate: ABC123
  - Logout: true
  - Devmode: false
  - Deactivate: false
  - Appmode: true
  - Simulate: false
  - MQTT Topic: car/17/settings
  - Payload length: 123
  - Payload: {"licensePlate":"ABC123",...}
📤 Sending vehicle settings to MQTT...
✅ SUCCESS: Vehicle settings published to MQTT
  - Topic: car/17/settings
  - Vehicle ID: 17
  - License Plate: ABC123
```

---

## 🎯 Key Features

### 1. Retained Messages

Settings messages are published with `mqtt_retained = true`, which means:
- ✅ Vehicle receives settings immediately on reconnect
- ✅ No need to wait for next settings update
- ✅ Vehicle always has latest settings

### 2. Routing Key Pattern

The routing key includes the vehicle ID:
- ✅ Easy to target specific vehicles
- ✅ Supports wildcards for bulk updates
- ✅ Backend doesn't need to know MQTT topics

### 3. License Plate Identification

Message body includes license plate:
- ✅ Vehicle can verify settings are for correct vehicle
- ✅ Additional validation layer
- ✅ Useful for debugging

---

## 🔍 Troubleshooting

### Issue: Settings not reaching vehicle

**Check 1: RabbitMQ Exchange and Queue**
```bash
# List exchanges
rabbitmqadmin list exchanges

# List queues
rabbitmqadmin list queues

# Check bindings
rabbitmqadmin list bindings
```

**Check 2: Message in Queue**
```bash
# Get message count
rabbitmqadmin list queues name messages

# Peek at messages (doesn't consume)
rabbitmqadmin get queue=vehicle.settings.queue count=1
```

**Check 3: MQTT Connection**
- Verify vehicle is subscribed to `car/{vehicleId}/settings`
- Check MQTT broker logs
- Verify MQTT connection is active

### Issue: Wrong vehicle receiving settings

**Verify:**
- Routing key matches vehicle ID: `vehicle.settings.{vehicleId}`
- Vehicle is subscribing to correct topic: `car/{vehicleId}/settings`
- License plate in message matches vehicle

### Issue: Settings not persisting after vehicle restart

**Check:**
- MQTT message retention is enabled (`mqtt_retained = true`)
- MQTT broker is persisting retained messages
- Vehicle clears retained messages on connect

---

## 🧪 Testing

### Test 1: End-to-End Flow

1. **Start the application**
   ```bash
   ./gradlew bootRun
   ```

2. **Verify RabbitMQ setup**
   ```bash
   rabbitmqadmin list exchanges name=vehicle.settings.exchange
   rabbitmqadmin list queues name=vehicle.settings.queue
   ```

3. **Subscribe to MQTT topic** (using mosquitto_sub)
   ```bash
   mosquitto_sub -h localhost -t "car/17/settings" -v
   ```

4. **Publish test message to RabbitMQ**
   ```bash
   rabbitmqadmin publish \
     exchange=vehicle.settings.exchange \
     routing_key=vehicle.settings.17 \
     payload='{"licensePlate":"TEST123","logout":false,"devmode":true,"deactivate":false,"appmode":true,"simulate":true}'
   ```

5. **Verify MQTT reception**
   - Check mosquitto_sub output
   - Should see JSON message on `car/17/settings`

6. **Check application logs**
   - Should see "RECEIVED VEHICLE SETTINGS FROM RABBITMQ"
   - Should see "SUCCESS: Vehicle settings published to MQTT"

---

### Test 2: Multiple Vehicles

Publish settings to multiple vehicles:

```python
import pika
import json

connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()

vehicles = [
    {"id": 17, "plate": "ABC123", "devmode": True},
    {"id": 42, "plate": "XYZ789", "devmode": False},
    {"id": 99, "plate": "DEF456", "devmode": True}
]

for vehicle in vehicles:
    settings = {
        "licensePlate": vehicle["plate"],
        "logout": False,
        "devmode": vehicle["devmode"],
        "deactivate": False,
        "appmode": True,
        "simulate": False
    }
    
    channel.basic_publish(
        exchange='vehicle.settings.exchange',
        routing_key=f'vehicle.settings.{vehicle["id"]}',
        body=json.dumps(settings),
        properties=pika.BasicProperties(
            content_type='application/json',
            delivery_mode=2
        )
    )
    print(f"✅ Published settings for vehicle {vehicle['id']}")

connection.close()
```

---

## 📚 Related Components

### Services
- `RabbitMQVehicleSettingsListenerService` - Listens to RabbitMQ and forwards to MQTT
- `MqttService.publishVehicleSettings()` - Publishes settings to MQTT

### DTOs
- `VehicleSettingsMessage` - Settings message structure

### Configuration
- `RabbitMQConfig` - RabbitMQ exchange, queue, and binding setup
- `MqttConfiguration` - MQTT outbound channel and handler

---

## 🔒 Security Considerations

1. **Validate Vehicle ID**
   - Ensure vehicle ID in routing key is valid
   - Check vehicle exists in database before publishing

2. **Verify License Plate**
   - Vehicle should verify license plate matches
   - Reject settings if mismatch detected

3. **Authentication**
   - RabbitMQ: Use username/password authentication
   - MQTT: Use TLS and authentication

4. **Authorization**
   - Only authorized services can publish to settings exchange
   - Vehicles can only subscribe to their own settings topic

---

## 💡 Best Practices

### For Backend Services

1. **Always include license plate** - Helps vehicle verify settings
2. **Use specific routing keys** - Target individual vehicles
3. **Set expiration on messages** - Avoid stale settings
4. **Monitor dead letter queue** - Check for failed deliveries

### For Vehicles

1. **Subscribe on connect** - Listen to `car/{vehicleId}/settings`
2. **Validate license plate** - Ensure settings are for correct vehicle
3. **Apply settings atomically** - All or nothing approach
4. **Acknowledge receipt** - Publish confirmation back to backend
5. **Handle retained messages** - Process on first connect

---

## 📈 Monitoring

### Metrics to Track

1. **RabbitMQ Metrics**
   - Messages published to exchange
   - Messages in queue
   - Consumer count
   - Failed deliveries (DLQ)

2. **MQTT Metrics**
   - Messages published
   - Active subscriptions
   - Retained message count
   - QoS 1 acknowledgments

3. **Application Metrics**
   - Settings processed count
   - Processing errors
   - Average processing time
   - MQTT publish failures

---

## 🎉 Summary

✅ **RabbitMQ to MQTT Bridge** - Seamless message forwarding  
✅ **Vehicle-Specific Routing** - Settings targeted to individual vehicles  
✅ **Retained Messages** - Vehicles get settings on reconnect  
✅ **License Plate Validation** - Additional security layer  
✅ **Comprehensive Logging** - Easy debugging and monitoring  

The vehicle settings flow provides a reliable, scalable way to update vehicle configuration in real-time! 🚗⚙️

