# MQTT Location Consumer Documentation

This document describes how to subscribe to and decode the vehicle location stream published by the Android client.

## 📡 MQTT Topic Structure

All location data is published to a unified batch topic:

```
vehicles/{carId}/location/batch
```

- **{carId}**: The unique ID of the vehicle (string).
- **QoS**: 1 (At least once delivery).

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
- **Heartbeat**: When the vehicle is stationary, a batch (likely containing 1 point) is sent every **5 minutes**.
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
