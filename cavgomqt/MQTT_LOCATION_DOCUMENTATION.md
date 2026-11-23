# MQTT Location Consumer Documentation

This document describes how to subscribe to and decode the vehicle location stream published by the Android client.

## 📡 MQTT Topic Structure

All location data is published to a unified batch topic:

```
vehicles/{carId}/location/batch
```

- **{carId}**: The unique ID of the vehicle (string).
- **QoS**: 1 (At least once delivery).

The Android client also publishes a JSON fallback channel while signed in:

```
users/{userId}/location/update
```

- Payload: `{ "userId", "vehicleId"?, "latitude", "longitude", "speed" (m/s), "bearing"?, "accuracy"?, "timestamp" }`
- The backend consumes this topic and forwards it to the same vehicle-location fanout when `vehicleId` is present.

## 🔗 Channel map (driver GPS / trip data)

| Channel | Producer → consumer | Payload / units |
| --- | --- | --- |
| `vehicles/{vehicleId}/location/batch` | Android → cavgomqt | Protobuf `LocationBatch`; speed **m/s**, timestamp **ms** |
| `users/{userId}/location/update` | Android → cavgomqt | JSON; speed **m/s**, timestamp **ms**, requires `vehicleId` for car updates |
| `vehicle.location.updates.fanout` (RabbitMQ) | cavgomqt → cavgomain + adminaggregate | `VehicleLocationUpdateMessage`; `current_speed` **km/h** |
| `cavgomqt.location.updates` (RabbitMQ) | cavgomqt → adminaggregate | `NavigaLocationUpdateEvent`; raw GPS speed **m/s**, converted to km/h by the consumer |
| `cavgomqt.trip.updates` (RabbitMQ) | cavgomqt → cavgotrips + adminaggregate | `NavigaTripUpdateEvent`; `currentLocation.speed` **m/s**, converted to km/h by consumers |
| `car/{vehicleId}/trip` | cavgomqt → Android | Trip lifecycle event; Android uses it to trigger a state reload |
| `car/{vehicleId}/trip/updates` | cavgomqt → Android | Flat `NavigaTripDto`; waypoint progress + `currentLocation.speed` **m/s** (Android converts for UI) |

`trip/{tripId}/booking*`, `car/{vehicleId}/ping`, and `car/{vehicleId}/settings` are configured but are not part of the driver location/speed path.

## 📦 Payload Format (Protobuf)

The payload is a binary Protobuf message. To decode it, you will need the following schema definition.

### .proto Definition

If you are using a generator (like protoc), use this schema:

```protobuf
syntax = "proto3";

message LocationPoint {
  double lat = 1;         // Latitude
  double lng = 2;         // Longitude
  float speed = 3;        // Speed in m/s
  optional float bearing = 4;      // Heading (0-360)
  optional float accuracy = 5;     // Confidence interval (meters)
  int64 timestamp = 6;    // Unix time (ms)
}

message LocationBatch {
  string vehicleId = 1;
  string plate = 2;       // Vehicle license plate
  repeated LocationPoint points = 3;
}
```

## 🔄 Reporting Logic

The client uses an **Adaptive Batching** strategy. A single message arriving on the topic may contain one or multiple points.

- **Real-time Tracking**: When the vehicle is moving (> 1m/s), a batch is sent every **10 seconds**.
- **Heartbeat**: When the vehicle is stationary, flushes are throttled to **60 seconds**; the client still holds a 5-minute heartbeat constant for connection keepalive tuning.
- **Durable Replay**: If the device was offline, the next message may contain **many points** (the entire history of the offline period).

## 🛠️ Consumer Implementation (Java/Spring)

The implementation is located in:
- **Service**: [MqttLocationListenerService.java](file:///Users/pro/Dev/Cavgo/backend/cavgobackend/cavgomqt/src/main/java/com/nexxserve/cavgomqt/service/MqttLocationListenerService.java)
- **Configuration**: [MqttConfiguration.java](file:///Users/pro/Dev/Cavgo/backend/cavgobackend/cavgomqt/src/main/java/com/nexxserve/cavgomqt/config/MqttConfiguration.java)
- **Protobuf Schema**: [location.proto](file:///Users/pro/Dev/Cavgo/backend/cavgobackend/cavgomqt/src/main/proto/location.proto)

### How It Works

1. **MQTT Subscription**: The application subscribes to `vehicles/+/location/batch` with QoS 1
2. **Binary Payload Reception**: Messages are received as binary (byte array) payloads
3. **Protobuf Decoding**: `LocationBatch.parseFrom(payload)` decodes the binary data
4. **Data Processing**: Each `LocationPoint` in the batch is logged with:
   - Latitude & Longitude
   - Speed (m/s)
   - Bearing (degrees, optional)
   - Accuracy (meters, optional)
   - Timestamp (Unix milliseconds)

### Example Java Code

```java
@Service
public class MqttLocationListenerService {
    
    public void processLocationBatch(String topic, byte[] payload) {
        try {
            // Decode Protobuf message
            LocationBatch batch = LocationBatch.parseFrom(payload);
            
            logger.info("Vehicle ID: {}", batch.getVehicleId());
            logger.info("License Plate: {}", batch.getPlate());
            logger.info("Number of Points: {}", batch.getPointsCount());
            
            // Process each location point
            for (LocationPoint point : batch.getPointsList()) {
                logger.info("Lat: {}, Lng: {}, Speed: {} m/s, Timestamp: {}",
                    point.getLat(),
                    point.getLng(),
                    point.getSpeed(),
                    point.getTimestamp()
                );
            }
        } catch (InvalidProtocolBufferException e) {
            logger.error("Failed to decode Protobuf: {}", e.getMessage());
        }
    }
}
```

## 🛠️ Consumer Implementation (Python Example)

If you are using Python with paho-mqtt and protobuf:

```python
import paho.mqtt.client as mqtt
# Assuming you generated location_pb2 from the .proto above
import location_pb2 

def on_message(client, userdata, msg):
    batch = location_pb2.LocationBatch()
    batch.ParseFromString(msg.payload)
    
    print(f"Vehicle: {batch.plate} ({batch.vehicleId})")
    for point in batch.points:
        print(f"  - Lat: {point.lat}, Lng: {point.lng}, Time: {point.timestamp}")

client = mqtt.Client()
client.on_message = on_message
client.connect("broker.hivemq.com", 1883)
client.subscribe("vehicles/+/location/batch")
client.loop_forever()
```

## ⚠️ Important Notes

- **Empty Points**: If a batch arrives with an empty `points` list, it should be treated as a connection heartbeat only.
- **Ordering**: Points within a batch are always ordered by `timestamp` ascending.
- **QoS 1**: Ensures at-least-once delivery - duplicates may occur but are unlikely.
- **Offline Support**: The client queues location data locally during offline periods and sends them in batches when reconnected.

## 📊 Message Flow

```
Android Client (Kotlin)
  ↓ (Collects GPS data)
Room Database (Local queue)
  ↓ (Batches every 10s when moving, 5min when stationary)
MQTT Publish → vehicles/{carId}/location/batch
  ↓ (Binary Protobuf payload, QoS 1)
Backend Service (Java/Spring)
  ↓ (Subscribes to vehicles/+/location/batch)
MqttLocationListenerService
  ↓ (Decodes Protobuf)
Process & Log Location Data
```

## 🔮 Future Enhancements

The current implementation logs all location data. Potential next steps:

1. **Forward to Navigation API**: Send location updates to Naviga service for real-time routing
2. **Persist to Database**: Store location history for analytics and playback
3. **Publish to RabbitMQ**: Forward to other microservices
4. **Real-time Metrics**: Calculate speed, distance, route adherence
5. **Geofencing**: Trigger alerts when vehicles enter/exit specific areas
