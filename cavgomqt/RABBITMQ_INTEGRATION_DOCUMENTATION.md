# RabbitMQ Integration Documentation

## Overview

This backend service publishes events to RabbitMQ fanout exchanges. Other services can bind their queues to these exchanges to consume real-time updates.

**Service**: cavgomqt (Cavgo MQTT & Naviga Integration Backend)

---

## Table of Contents

1. [Fanout Exchanges](#fanout-exchanges)
2. [Trip Updates Exchange](#trip-updates-exchange-cavgomqttripupdates)
3. [Location Updates Exchange](#location-updates-exchange-cavgomqtlocationupdates)
4. [How to Consume](#how-to-consume)
5. [Data Structures](#data-structures)
6. [Error Handling](#error-handling)
7. [Examples](#examples)

---

## Fanout Exchanges

This backend service publishes to **exactly 2 fanout exchanges**:

| Exchange Name | Purpose | Event Type | When Published |
|---------------|---------|-----------|-----------------|
| `cavgomqt.trip.updates` | Trip lifecycle events from Naviga API | NavigaTripUpdateEvent | After successful Naviga API calls |
| `cavgomqt.location.updates` | GPS location batches from MQTT | NavigaLocationUpdateEvent | After MQTT location batch decoding, before Naviga send |

**Key Point**: Both exchanges are **fanout** type, meaning:
- Messages broadcast to all bound queues
- No routing key filtering applied
- All subscribed services receive all events
- No message loss if queue is not bound at publish time

---

## Trip Updates Exchange: `cavgomqt.trip.updates`

### Exchange Details

```yaml
Exchange Name: cavgomqt.trip.updates
Type: Fanout
Durable: true
Auto-delete: false
Routing Key: "" (empty - ignored for fanout)
```

### When Events Are Published

Trip update events are published **after successful Naviga API calls**:

1. **Trip Creation** - After `POST /api/trips` succeeds
2. **GPS Batch Update** - After `POST /api/gps` succeeds
3. **Trip Deletion** - After `DELETE /api/trips/{id}` succeeds

### Event Structure: `NavigaTripUpdateEvent`

```json
{
  "eventType": "updates",
  "trip": {
    "id": 123,
    "carId": "17",
    "status": "CREATED|ACTIVE|COMPLETED|DELETED",
    "createdAt": "2025-12-27T10:00:00Z",
    "completedAt": null,
    "waypointProgresses": [
      {
        "waypointIndex": 0,
        "waypointId": "wp-002",
        "waypointName": "Office",
        "latitude": 49.37816,
        "longitude": 9.088095,
        "state": "APPROACHING|ARRIVED|DONE",
        "arrivedAt": null,
        "remainingDistance": 1450.2,
        "remainingTime": 115.8
      },
      {
        "waypointIndex": 1,
        "waypointId": null,
        "waypointName": null,
        "latitude": 49.368903,
        "longitude": 9.108073,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 3420.5,
        "remainingTime": 275.3
      }
    ],
    "currentLocation": {
      "carId": "17",
      "latitude": 49.390750,
      "longitude": 9.083050,
      "speed": 18.0,
      "heading": 47.0,
      "timestamp": "2025-12-27T10:00:02Z"
    }
  },
  "timestamp": "2025-12-27T10:00:00.500Z",
  "source": "naviga-trip-create|naviga-gps-batch|naviga-trip-delete"
}
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `eventType` | String | Always `"updates"` |
| `trip.id` | Long | Unique trip identifier |
| `trip.carId` | String | Vehicle identifier (e.g., "17") |
| `trip.status` | String | Trip state: `CREATED`, `ACTIVE`, `COMPLETED`, or `DELETED` |
| `trip.createdAt` | ISO 8601 | When trip was created in Naviga |
| `trip.completedAt` | ISO 8601 (nullable) | When trip completed (null if not completed) |
| `trip.waypointProgresses` | Array (nullable) | List of waypoint progress tracking data |
| `trip.waypointProgresses[].waypointIndex` | Integer | Index of waypoint in trip (0-based) |
| `trip.waypointProgresses[].waypointId` | String (nullable) | Waypoint identifier |
| `trip.waypointProgresses[].waypointName` | String (nullable) | Waypoint name/label |
| `trip.waypointProgresses[].latitude` | Double | Waypoint latitude |
| `trip.waypointProgresses[].longitude` | Double | Waypoint longitude |
| `trip.waypointProgresses[].state` | String | Waypoint state: `APPROACHING`, `ARRIVED`, or `DONE` |
| `trip.waypointProgresses[].arrivedAt` | ISO 8601 (nullable) | When vehicle arrived at waypoint |
| `trip.waypointProgresses[].remainingDistance` | Double | Remaining distance to waypoint in meters |
| `trip.waypointProgresses[].remainingTime` | Double | Remaining time to waypoint in seconds |
| `trip.currentLocation` | Object (nullable) | Current vehicle location (map-matched/snapped coordinates) |
| `trip.currentLocation.carId` | String | Vehicle identifier |
| `trip.currentLocation.latitude` | Double | Current latitude (snapped to route) |
| `trip.currentLocation.longitude` | Double | Current longitude (snapped to route) |
| `trip.currentLocation.speed` | Double | Current speed in m/s |
| `trip.currentLocation.heading` | Double (nullable) | Current heading in degrees (0-360) |
| `trip.currentLocation.timestamp` | ISO 8601 | Timestamp of location update |
| `timestamp` | ISO 8601 | When this event was published |
| `source` | String | Source of event: `naviga-trip-create`, `naviga-gps-batch`, or `naviga-trip-delete` |

### Event Examples

#### 1. Trip Created Event

```json
{
  "eventType": "updates",
  "trip": {
    "id": 123,
    "carId": "17",
    "status": "CREATED",
    "createdAt": "2025-12-27T10:00:00Z",
    "completedAt": null,
    "waypointProgresses": [
      {
        "waypointIndex": 0,
        "waypointId": "wp-002",
        "waypointName": "Office",
        "latitude": 49.37816,
        "longitude": 9.088095,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 1500.5,
        "remainingTime": 120.3
      },
      {
        "waypointIndex": 1,
        "waypointId": null,
        "waypointName": null,
        "latitude": 49.368903,
        "longitude": 9.108073,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 3500.2,
        "remainingTime": 280.1
      }
    ],
    "currentLocation": null
  },
  "timestamp": "2025-12-27T10:00:00.500Z",
  "source": "naviga-trip-create"
}
```

**When**: Immediately after successful trip creation via MQTT or RabbitMQ
**Action**: Other services can initialize trip tracking, notify dashboards, etc.
**Note**: `currentLocation` is null until first GPS update is sent

#### 2. GPS Batch Update Event

```json
{
  "eventType": "updates",
  "trip": {
    "id": 123,
    "carId": "17",
    "status": "ACTIVE",
    "createdAt": "2025-12-27T10:00:00Z",
    "completedAt": null,
    "waypointProgresses": [
      {
        "waypointIndex": 0,
        "waypointId": "wp-002",
        "waypointName": "Office",
        "latitude": 49.37816,
        "longitude": 9.088095,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 1450.2,
        "remainingTime": 115.8
      },
      {
        "waypointIndex": 1,
        "waypointId": null,
        "waypointName": null,
        "latitude": 49.368903,
        "longitude": 9.108073,
        "state": "APPROACHING",
        "arrivedAt": null,
        "remainingDistance": 3420.5,
        "remainingTime": 275.3
      }
    ],
    "currentLocation": {
      "carId": "17",
      "latitude": 49.390750,
      "longitude": 9.083050,
      "speed": 18.0,
      "heading": 47.0,
      "timestamp": "2025-12-27T10:05:30Z"
    }
  },
  "timestamp": "2025-12-27T10:05:30.200Z",
  "source": "naviga-gps-batch"
}
```

**When**: After successful GPS batch sent to Naviga (every 10s when moving, 60s when stationary)
**Action**: Update vehicle location on dashboard, calculate ETA, check waypoint progress
**Note**: `currentLocation` contains map-matched (snapped) coordinates, not raw GPS

#### 3. Trip Completed Event

```json
{
  "eventType": "updates",
  "trip": {
    "id": 123,
    "carId": "17",
    "status": "COMPLETED",
    "createdAt": "2025-12-27T10:00:00Z",
    "completedAt": "2025-12-27T10:30:00Z",
    "waypointProgresses": [
      {
        "waypointIndex": 0,
        "waypointId": "wp-002",
        "waypointName": "Office",
        "latitude": 49.37816,
        "longitude": 9.088095,
        "state": "DONE",
        "arrivedAt": "2025-12-27T10:15:00Z",
        "remainingDistance": 0.0,
        "remainingTime": 0.0
      },
      {
        "waypointIndex": 1,
        "waypointId": null,
        "waypointName": null,
        "latitude": 49.368903,
        "longitude": 9.108073,
        "state": "ARRIVED",
        "arrivedAt": "2025-12-27T10:30:00Z",
        "remainingDistance": 0.0,
        "remainingTime": 0.0
      }
    ],
    "currentLocation": {
      "carId": "17",
      "latitude": 49.368903,
      "longitude": 9.108073,
      "speed": 0.0,
      "heading": 47.0,
      "timestamp": "2025-12-27T10:30:00Z"
    }
  },
  "timestamp": "2025-12-27T10:30:00.800Z",
  "source": "naviga-gps-batch"
}
```

**When**: When final waypoint is reached in Naviga (detected in GPS response)
**Action**: Complete trip, notify driver, update analytics, trigger notifications
**Note**: All waypoints have state `DONE` or final waypoint has state `ARRIVED`

#### 4. Trip Deleted Event

```json
{
  "eventType": "updates",
  "trip": {
    "id": 123,
    "carId": "17",
    "status": "DELETED",
    "createdAt": "2025-12-27T10:00:00Z",
    "completedAt": null,
    "waypointProgresses": null,
    "currentLocation": null
  },
  "timestamp": "2025-12-27T10:15:00.100Z",
  "source": "naviga-trip-delete"
}
```

**When**: When trip is cancelled or deleted from Naviga
**Action**: Cancel trip notifications, cleanup UI, stop tracking vehicle
**Note**: `waypointProgresses` and `currentLocation` are typically null for deletion events

---

## Location Updates Exchange: `cavgomqt.location.updates`

### Exchange Details

```yaml
Exchange Name: cavgomqt.location.updates
Type: Fanout
Durable: true
Auto-delete: false
Routing Key: "" (empty - ignored for fanout)
```

### When Events Are Published

Location update events are published **after MQTT location batch is decoded**, **before** sending to Naviga API:

- GPS location batch received on MQTT topic `vehicles/{vehicleId}/location/batch`
- Protobuf payload decoded
- Event published to fanout exchange
- Then: GPS updates sent to Naviga API

### Event Structure: `NavigaLocationUpdateEvent`

```json
{
  "eventType": "updates",
  "carId": "17",
  "locations": [
    {
      "carId": "17",
      "latitude": 49.390674,
      "longitude": 9.082976,
      "speed": 16.5,
      "heading": 45.0,
      "accuracy": 10.0,
      "timestamp": "2025-12-27T10:00:00Z"
    },
    {
      "carId": "17",
      "latitude": 49.390700,
      "longitude": 9.083000,
      "speed": 17.2,
      "heading": 46.0,
      "accuracy": 10.5,
      "timestamp": "2025-12-27T10:00:01Z"
    }
  ],
  "timestamp": "2025-12-27T10:00:01.500Z",
  "source": "location-batch"
}
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `eventType` | String | Always `"updates"` |
| `carId` | String | Vehicle identifier (e.g., "17") |
| `locations` | Array | List of individual location updates |
| `locations[].carId` | String | Vehicle identifier |
| `locations[].latitude` | Double | GPS latitude coordinate |
| `locations[].longitude` | Double | GPS longitude coordinate |
| `locations[].speed` | Double | Speed in meters per second (m/s) |
| `locations[].heading` | Double (nullable) | Heading/bearing in degrees (0-360) |
| `locations[].accuracy` | Double (nullable) | GPS accuracy in meters |
| `locations[].timestamp` | ISO 8601 | When location was captured |
| `timestamp` | ISO 8601 | When event was published to RabbitMQ |
| `source` | String | Always `"location-batch"` |

### Event Example

```json
{
  "eventType": "updates",
  "carId": "17",
  "locations": [
    {
      "carId": "17",
      "latitude": 49.390674,
      "longitude": 9.082976,
      "speed": 16.5,
      "heading": 45.0,
      "accuracy": 10.0,
      "timestamp": "2025-12-27T10:00:00Z"
    },
    {
      "carId": "17",
      "latitude": 49.390700,
      "longitude": 9.083000,
      "speed": 17.2,
      "heading": 46.0,
      "accuracy": 10.5,
      "timestamp": "2025-12-27T10:00:01Z"
    },
    {
      "carId": "17",
      "latitude": 49.390750,
      "longitude": 9.083050,
      "speed": 18.0,
      "heading": 47.0,
      "accuracy": 10.2,
      "timestamp": "2025-12-27T10:00:02Z"
    }
  ],
  "timestamp": "2025-12-27T10:00:02.300Z",
  "source": "location-batch"
}
```

**When**: Every 10 seconds when vehicle is moving, every 60 seconds when stationary
**Action**: Real-time location tracking, map updates, analytics, geofencing checks

---

## How to Consume

### Spring Boot Example

```java
@Service
@RequiredArgsConstructor
public class MyRabbitMQConsumer {

    private static final Logger logger = LoggerFactory.getLogger(MyRabbitMQConsumer.class);

    @RabbitListener(queues = "my-trip-updates-queue")
    public void handleTripUpdates(NavigaTripUpdateEvent event) {
        logger.info("Received trip update: tripId={}, status={}, source={}",
                event.getTrip().getId(),
                event.getTrip().getStatus(),
                event.getSource());
        
        // Handle based on source
        switch (event.getSource()) {
            case "naviga-trip-create":
                handleTripCreated(event);
                break;
            case "naviga-gps-batch":
                handleTripUpdate(event);
                break;
            case "naviga-trip-delete":
                handleTripDeleted(event);
                break;
        }
    }

    private void handleTripCreated(NavigaTripUpdateEvent event) {
        // Trip created - initialize tracking
        logger.info("Trip created: {} waypoints", 
            event.getTrip().getWaypointProgresses() != null ? 
            event.getTrip().getWaypointProgresses().size() : 0);
    }

    private void handleTripUpdate(NavigaTripUpdateEvent event) {
        // Trip update - check waypoint progress
        if (event.getTrip().getWaypointProgresses() != null) {
            for (NavigaTripUpdateEvent.WaypointProgressDto wp : event.getTrip().getWaypointProgresses()) {
                logger.info("  Waypoint {}: {} - {}m, {}s remaining",
                    wp.getWaypointIndex(),
                    wp.getState(),
                    wp.getRemainingDistance(),
                    wp.getRemainingTime());
            }
        }
        
        // Update current location on map
        if (event.getTrip().getCurrentLocation() != null) {
            NavigaTripUpdateEvent.CurrentLocationDto loc = event.getTrip().getCurrentLocation();
            logger.info("  Current location: ({}, {}) @ {} m/s",
                loc.getLatitude(),
                loc.getLongitude(),
                loc.getSpeed());
        }
    }

    private void handleTripDeleted(NavigaTripUpdateEvent event) {
        // Trip deleted - cleanup
        logger.info("Trip deleted: {}", event.getTrip().getId());
    }

    @RabbitListener(queues = "my-location-updates-queue")
    public void handleLocationUpdates(NavigaLocationUpdateEvent event) {
        logger.info("Received {} locations for carId={}",
                event.getLocations().size(),
                event.getCarId());
        
        // Process location batch
        event.getLocations().forEach(location -> {
            logger.info("  Location: lat={}, lng={}, speed={} m/s",
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getSpeed());
        });
    }
}
```

### RabbitMQ Configuration

```java
@Configuration
public class RabbitMQConsumerConfig {

    // Trip Updates Exchange & Queue
    @Bean
    public FanoutExchange tripUpdatesExchange() {
        return new FanoutExchange("cavgomqt.trip.updates", true, false);
    }

    @Bean
    public Queue myTripUpdatesQueue() {
        return new Queue("my-trip-updates-queue", true, false, false);
    }

    @Bean
    public Binding tripUpdatesBinding(Queue myTripUpdatesQueue, FanoutExchange tripUpdatesExchange) {
        return BindingBuilder.bind(myTripUpdatesQueue)
                .to(tripUpdatesExchange);
    }

    // Location Updates Exchange & Queue
    @Bean
    public FanoutExchange locationUpdatesExchange() {
        return new FanoutExchange("cavgomqt.location.updates", true, false);
    }

    @Bean
    public Queue myLocationUpdatesQueue() {
        return new Queue("my-location-updates-queue", true, false, false);
    }

    @Bean
    public Binding locationUpdatesBinding(Queue myLocationUpdatesQueue, FanoutExchange locationUpdatesExchange) {
        return BindingBuilder.bind(myLocationUpdatesQueue)
                .to(locationUpdatesExchange);
    }
}
```

### Direct RabbitMQ Consumer (Without Spring)

```python
# Python example using pika
import pika
import json

def callback(ch, method, properties, body):
    event = json.loads(body)
    print(f"Received event: {event['source']}")
    
    if event['source'] == 'naviga-trip-create':
        print(f"Trip created: {event['trip']['id']}")
    elif event['source'] == 'naviga-gps-batch':
        print(f"GPS update for trip {event['trip']['id']}")

connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()

# Declare exchange (idempotent)
channel.exchange_declare(exchange='cavgomqt.trip.updates', exchange_type='fanout', durable=True)

# Declare queue
result = channel.queue_declare(queue='my-trip-updates-queue', durable=True)
queue_name = result.method.queue

# Bind queue to exchange
channel.queue_bind(exchange='cavgomqt.trip.updates', queue=queue_name)

# Consume
channel.basic_consume(queue=queue_name, on_message_callback=callback, auto_ack=True)
print('Waiting for messages...')
channel.start_consuming()
```

---

## Data Structures

### NavigaTripUpdateEvent

```java
public class NavigaTripUpdateEvent {
    private String eventType;           // Always "updates"
    private NavigaTripDto trip;         // Trip data
    private Instant timestamp;          // Event publication time
    private String source;              // naviga-trip-create | naviga-gps-batch | naviga-trip-delete
}

public static class NavigaTripDto {
    private Long id;                    // Trip ID
    private String carId;               // Vehicle ID
    private String status;              // CREATED | ACTIVE | COMPLETED | DELETED
    private Instant createdAt;          // Trip creation time
    private Instant completedAt;        // Completion time (nullable)
    private List<WaypointProgressDto> waypointProgresses;  // Waypoint progress (nullable)
    private CurrentLocationDto currentLocation;  // Current location (nullable)
}

public static class WaypointProgressDto {
    private Integer waypointIndex;      // Waypoint index (0-based)
    private String waypointId;          // Waypoint ID (nullable)
    private String waypointName;        // Waypoint name (nullable)
    private Double latitude;            // Waypoint latitude
    private Double longitude;           // Waypoint longitude
    private String state;               // APPROACHING | ARRIVED | DONE
    private Instant arrivedAt;          // Arrival time (nullable)
    private Double remainingDistance;   // Remaining distance in meters
    private Double remainingTime;       // Remaining time in seconds
}

public static class CurrentLocationDto {
    private String carId;               // Vehicle ID
    private Double latitude;            // Current latitude (map-matched)
    private Double longitude;           // Current longitude (map-matched)
    private Double speed;               // Speed in m/s
    private Double heading;             // Heading in degrees (nullable)
    private Instant timestamp;          // Location timestamp
}
```

### NavigaLocationUpdateEvent

```java
public class NavigaLocationUpdateEvent {
    private String eventType;           // Always "updates"
    private String carId;               // Vehicle ID
    private List<NavigaLocationDto> locations;  // Location batch
    private Instant timestamp;          // Event publication time
    private String source;              // Always "location-batch"
}

public static class NavigaLocationDto {
    private String carId;               // Vehicle ID
    private Double latitude;            // Latitude coordinate
    private Double longitude;           // Longitude coordinate
    private Double speed;               // Speed in m/s
    private Double heading;             // Heading in degrees (nullable)
    private Double accuracy;            // Accuracy in meters (nullable)
    private String timestamp;           // ISO 8601 timestamp
}
```

---

## Error Handling

### What If RabbitMQ Is Down?

- **Publishing**: This backend logs warnings but continues processing (non-blocking)
- **Consuming**: Your service should handle connection failures gracefully
  - Implement connection retry logic
  - Use RabbitMQ client auto-reconnect features
  - Queue messages locally if needed
  - Alert on connection loss

### Message Ordering

- **Trip Updates**: May arrive out of order due to fanout nature
  - Always use `source` field to determine event type
  - Use `timestamp` field to detect message age
  - Check `trip.status` transitions for consistency

- **Location Updates**: Locations within a batch are **chronologically sorted** by timestamp
  - Process locations in order (they are ordered by vehicle)
  - Timestamps increase monotonically within a batch

### Duplicate Messages

- **Not guaranteed**: Events may be duplicated due to network issues
- **Recommended**: Implement idempotent processing using `tripId` or `timestamp`

---

## Examples

### Use Case 1: Dashboard Service

**Goal**: Show real-time vehicle location and trip status

```java
@RabbitListener(queues = "dashboard-trip-queue")
public void updateDashboard(NavigaTripUpdateEvent event) {
    Trip trip = tripService.findById(event.getTrip().getId());
    
    if (event.getSource().equals("naviga-gps-batch")) {
        // Update vehicle location
        trip.setStatus(event.getTrip().getStatus());
        trip.setLastUpdate(event.getTimestamp());
        
        // Update current location on map
        if (event.getTrip().getCurrentLocation() != null) {
            NavigaTripUpdateEvent.CurrentLocationDto location = event.getTrip().getCurrentLocation();
            mapService.updateVehicleLocation(
                event.getTrip().getCarId(),
                location.getLatitude(),
                location.getLongitude(),
                location.getSpeed(),
                location.getHeading()
            );
        }
        
        // Update waypoint progress
        if (event.getTrip().getWaypointProgresses() != null) {
            for (NavigaTripUpdateEvent.WaypointProgressDto wp : event.getTrip().getWaypointProgresses()) {
                dashboardService.updateWaypointProgress(
                    trip.getId(),
                    wp.getWaypointIndex(),
                    wp.getState(),
                    wp.getRemainingDistance(),
                    wp.getRemainingTime()
                );
            }
        }
        
        tripService.save(trip);
        
        // Notify WebSocket clients
        websocketService.broadcastTripUpdate(trip);
    }
}

@RabbitListener(queues = "dashboard-location-queue")
public void updateVehicleLocation(NavigaLocationUpdateEvent event) {
    // Get latest location
    NavigaLocationUpdateEvent.NavigaLocationDto lastLocation = 
        event.getLocations().get(event.getLocations().size() - 1);
    
    // Update map
    mapService.updateVehicleLocation(
        event.getCarId(),
        lastLocation.getLatitude(),
        lastLocation.getLongitude(),
        lastLocation.getSpeed()
    );
    
    // Notify WebSocket clients
    websocketService.broadcastLocationUpdate(event.getCarId(), lastLocation);
}
```

### Use Case 2: Analytics Service

**Goal**: Track trip completion metrics

```java
@RabbitListener(queues = "analytics-queue")
public void trackTripMetrics(NavigaTripUpdateEvent event) {
    if ("COMPLETED".equals(event.getTrip().getStatus())) {
        long duration = Duration.between(
            event.getTrip().getCreatedAt(),
            event.getTrip().getCompletedAt()
        ).getSeconds();
        
        metricsService.recordTripCompletion(
            event.getTrip().getId(),
            event.getTrip().getCarId(),
            duration
        );
        
        logger.info("Trip {} completed in {} seconds",
            event.getTrip().getId(), duration);
    }
}
```

### Use Case 3: Geofencing Service

**Goal**: Check vehicle location against geofences

```java
@RabbitListener(queues = "geofence-queue")
public void checkGeofences(NavigaLocationUpdateEvent event) {
    for (NavigaLocationUpdateEvent.NavigaLocationDto location : event.getLocations()) {
        List<Geofence> geofences = geofenceService.findNearby(
            location.getLatitude(),
            location.getLongitude()
        );
        
        for (Geofence geofence : geofences) {
            if (geofenceService.isInside(location, geofence)) {
                notificationService.sendGeofenceAlert(
                    event.getCarId(),
                    geofence.getName()
                );
            }
        }
    }
}
```

---

## Troubleshooting

### Queue Not Receiving Messages

**Problem**: Queue is bound but not receiving messages

**Solution**:
1. Verify exchange name: `cavgomqt.trip.updates` or `cavgomqt.location.updates`
2. Verify queue binding exists in RabbitMQ Admin UI
3. Check that exchange is **durable** and **not auto-delete**
4. Ensure consumer is started before messages are published

### Message Deserialization Errors

**Problem**: `com.fasterxml.jackson.databind.JsonMappingException`

**Solution**:
1. Verify your DTO classes match the event structure
2. Check for missing `@JsonProperty` annotations
3. Ensure field names match exactly (case-sensitive)
4. Update your library versions if events change

### Events Arriving Out of Order

**Problem**: Receiving GPS updates before trip creation event

**Solution**:
1. This is normal with fanout exchanges
2. Always check `trip.status` to determine current state
3. Use `timestamp` field to order events
4. Implement state machine to handle all transition paths

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-27 | Initial RabbitMQ documentation for fanout exchanges |

---

## Support

For questions or issues:
1. Check logs in the backend service for publishing errors
2. Verify RabbitMQ connectivity: `rabbitmq-diagnostics status`
3. Check queue bindings: RabbitMQ Admin UI → Exchanges
4. Review event timestamps to diagnose ordering issues

---

## Related Documentation

- [TRIP_API_DOCUMENTATION.md](TRIP_API_DOCUMENTATION.md) - Naviga API integration details
- [MQTT_LOCATION_DOCUMENTATION.md](MQTT_LOCATION_DOCUMENTATION.md) - MQTT location batch format
- [TRIPS_FANOUT_MESSAGE_STRUCTURE.md](TRIPS_FANOUT_MESSAGE_STRUCTURE.md) - RabbitMQ fanout message format
