# Trip Snapshots Exchange Integration Guide

## Overview

The Booking Service publishes real-time trip snapshot updates to RabbitMQ on the `trip_snapshots` fanout exchange. This allows other microservices, frontends, and monitoring systems to stay synchronized with the current booking state of all trips.

---

## Exchange Details

| Property | Value |
|----------|-------|
| **Exchange Name** | `bookingservice.trip.snapshot` (configurable via `SNAPSHOT_EXCHANGE`) |
| **Exchange Type** | Fanout |
| **Durable** | Yes |
| **Auto-Delete** | No |
| **Message Format** | JSON |
| **Content-Type** | `application/json` |

---

## Subscription Setup

### Creating a Queue

```go
// Declare the fanout exchange (default: bookingservice.trip.snapshot)
ch.ExchangeDeclare(
  "bookingservice.trip.snapshot",    // exchange name
    "fanout",            // exchange type
    true,                // durable
    false,               // auto-delete
    false,               // internal
    false,               // no-wait
    nil,                 // arguments
)

// Create a unique queue for your service
ch.QueueDeclare(
    "my-service-snapshots-queue",  // queue name
    true,                          // durable
    false,                         // exclusive
    false,                         // auto-delete
    false,                         // no-wait
    nil,                           // arguments
)

// Bind queue to exchange (fanout has empty routing key)
ch.QueueBind(
  "my-service-snapshots-queue",  // queue name
  "",                            // routing key (empty for fanout)
  "bookingservice.trip.snapshot",// exchange name
    false,                         // no-wait
    nil,                           // arguments
)
```

### Consuming Messages

```go
msgs, err := ch.Consume(
    "my-service-snapshots-queue",  // queue name
    "my-consumer",                 // consumer tag
    false,                         // auto-ack (set to false for manual acknowledgment)
    false,                         // exclusive
    false,                         // no-local
    false,                         // no-wait
    nil,                           // arguments
)

for msg := range msgs {
    var snapshot TripSnapshotPublish
    err := json.Unmarshal(msg.Body, &snapshot)
    if err != nil {
        log.Printf("Failed to unmarshal snapshot: %v", err)
        msg.Nack(false, true)  // requeue on error
        continue
    }
    
    // Process snapshot...
    processSnapshot(&snapshot)
    
    msg.Ack(false)  // acknowledge successful processing
}
```

---

## Message Schema

### Top-Level Message Structure

```json
{
  "tripId": "123",
  "tripStatus": "SCHEDULED",
  "lastUpdated": "2026-01-07T14:30:45Z",
  "capacity": { ... },
  "locations": [ ... ],
  "summary": { ... }
}
```

### Complete Example Message

```json
{
  "tripId": "456",
  "tripStatus": "SCHEDULED",
  "lastUpdated": "2026-01-07T14:30:45Z",
  "capacity": {
    "totalSeats": 4,
    "availableSeats": 2,
    "occupiedSeats": 1,
    "pendingPaymentSeats": 1
  },
  "locations": [
    {
      "locationId": "69",
      "type": "ORIGIN",
      "order": 0,
      "status": "UPCOMING",
      "seats": {
        "pickup": 2,
        "dropoff": 0,
        "pendingPayment": 1,
        "availableFromHere": 2
      }
    },
    {
      "locationId": "70",
      "type": "WAYPOINT",
      "order": 1,
      "status": "UPCOMING",
      "seats": {
        "pickup": 1,
        "dropoff": 0,
        "pendingPayment": 0,
        "availableFromHere": 2
      }
    },
    {
      "locationId": "71",
      "type": "DESTINATION",
      "order": 2,
      "status": "UPCOMING",
      "seats": {
        "pickup": 0,
        "dropoff": 2,
        "pendingPayment": 0,
        "availableFromHere": 2
      }
    }
  ],
  "summary": {
    "totalTickets": 3,
    "paidTickets": 1,
    "pendingPayments": 1,
    "completedDropoffs": 0
  }
}
```

---

## Field Descriptions

### Capacity Object

| Field | Type | Description |
|-------|------|-------------|
| `totalSeats` | int | Total number of seats in the trip |
| `availableSeats` | int | Seats available for new bookings |
| `occupiedSeats` | int | Seats with confirmed payments |
| `pendingPaymentSeats` | int | Seats held pending payment confirmation |

**Relationship:** `totalSeats = availableSeats + occupiedSeats + pendingPaymentSeats`

### Location Object

| Field | Type | Description |
|-------|------|-------------|
| `locationId` | string | Unique identifier for the location |
| `type` | string | Type: `ORIGIN`, `WAYPOINT`, or `DESTINATION` |
| `order` | int | Order in the route sequence |
| `status` | string | `UPCOMING`, `CURRENT`, or `PASSED` |

### Seats Object (per Location)

| Field | Type | Description |
|-------|------|-------------|
| `pickup` | int | Number of passengers picking up at this location |
| `dropoff` | int | Number of passengers dropping off at this location |
| `pendingPayment` | int | Seats held pending payment from this location |
| `availableFromHere` | int | Seats still available for pickup from this location onwards |

### Summary Object

| Field | Type | Description |
|-------|------|-------------|
| `totalTickets` | int | Total tickets booked (pending + paid) |
| `paidTickets` | int | Tickets with confirmed payment |
| `pendingPayments` | int | Tickets pending payment |
| `completedDropoffs` | int | Passengers who have been dropped off |

---

## Event Types

Snapshots are published with the following event triggers:

### 1. **INITIALIZED**
- **When:** First booking is created for a trip
- **Capacity:** All seats available
- **Use Case:** Frontend initializes trip display

### 2. **BOOKING_CREATED**
- **When:** New booking created, awaiting payment
- **Changes:**
  - `availableSeats` decreases
  - `pendingPaymentSeats` increases
  - `totalTickets` increases
  - `pendingPayments` increases
- **Use Case:** Update available seat count in real-time

### 3. **PAYMENT_CONFIRMED**
- **When:** Payment processed successfully
- **Changes:**
  - `pendingPaymentSeats` decreases
  - `occupiedSeats` increases
  - `paidTickets` increases
  - `pendingPayments` decreases
- **Use Case:** Confirm seat booking, send confirmation email

### 4. **BOOKING_EXPIRED**
- **When:** Payment not received within 5 minutes (auto-cancelled)
- **Changes:**
  - `pendingPaymentSeats` decreases
  - `availableSeats` increases (seats released)
  - `totalTickets` decreases
  - `pendingPayments` decreases
- **Use Case:** Release seats back to pool, notify user

---

## Publishing Frequency

Snapshots are published **immediately** after each event:

- Creation of a new booking (BOOKING_CREATED)
- Payment confirmation (PAYMENT_CONFIRMED)
- Booking cancellation/expiration (BOOKING_EXPIRED)
- Trip initialization (INITIALIZED)

**Latency:** < 100ms from event trigger to message in exchange

---

## Consumer Implementation Patterns

### Pattern 1: Real-Time Seat Display (Frontend)

```javascript
// Connect to WebSocket backed by RabbitMQ consumer
ws.onmessage = (event) => {
  const snapshot = JSON.parse(event.data);
  
  // Update UI with latest seat counts
  document.getElementById('available-seats').textContent = 
    snapshot.capacity.availableSeats;
  
  // Update location status badges
  snapshot.locations.forEach(loc => {
    updateLocationDisplay(loc);
  });
};
```

### Pattern 2: Analytics & Reporting

```python
def process_snapshot(snapshot):
    """Store snapshot for analytics"""
    trip_id = snapshot['tripId']
    
    # Log booking metrics
    analytics.log('trip.capacity', {
        'trip_id': trip_id,
        'available': snapshot['capacity']['availableSeats'],
        'occupied': snapshot['capacity']['occupiedSeats'],
        'pending': snapshot['capacity']['pendingPaymentSeats'],
        'timestamp': snapshot['lastUpdated']
    })
```

### Pattern 3: Trip Monitoring Service

```go
func monitorTripSnapshot(snapshot *TripSnapshotPublish) {
    tripID := snapshot.TripID
    
    // Check if trip is overbookable risk
    if snapshot.Capacity.PendingPaymentSeats > snapshot.Capacity.AvailableSeats {
        alert.SendAlert("High pending payment ratio for trip " + tripID)
    }
    
    // Check for passenger flow issues
    for _, location := range snapshot.Locations {
        if location.Status == "CURRENT" && location.Seats.Pickup > 0 {
            monitor.LogPickupEvent(tripID, location.LocationID)
        }
    }
}
```

### Pattern 4: Payment Monitoring

```python
def monitor_payments(snapshot):
    """Alert if too many pending payments"""
    pending_ratio = (snapshot['summary']['pendingPayments'] / 
                     snapshot['summary']['totalTickets'])
    
    if pending_ratio > 0.5:
        send_notification(
            f"Trip {snapshot['tripId']}: "
            f"{snapshot['summary']['pendingPayments']} payments pending"
        )
```

---

## Best Practices

### 1. **Idempotency**
Handle duplicate messages gracefully. Messages might be delivered multiple times.

```go
// Check if snapshot was already processed
lastProcessed := cache.Get("snapshot:" + snapshot.TripID)
if lastProcessed == snapshot.LastUpdated {
    return  // Skip duplicate
}
```

### 2. **Manual Acknowledgment**
Use manual acknowledgment to ensure messages aren't lost:

```go
// Set auto-ack to false
msgs, _ := ch.Consume(queueName, "", false, ...)

for msg := range msgs {
    if err := processSnapshot(msg.Body); err != nil {
        msg.Nack(false, true)  // Requeue on error
    } else {
        msg.Ack(false)  // Acknowledge success
    }
}
```

### 3. **Rate Limiting**
For heavy consumers, consider batching snapshots:

```go
const batchSize = 10
var batch []TripSnapshot

for snapshot := range snapshotChannel {
    batch = append(batch, snapshot)
    if len(batch) >= batchSize {
        processBatch(batch)
        batch = batch[:0]
    }
}
```

### 4. **Error Handling**
Log parsing errors but don't fail the consumer:

```go
var snapshot TripSnapshotPublish
if err := json.Unmarshal(msg.Body, &snapshot); err != nil {
    log.Printf("Failed to parse snapshot: %v, body: %s", 
        err, string(msg.Body))
    msg.Ack(false)  // Still acknowledge to avoid blocking
    return
}
```

### 5. **Timestamp Handling**
Always use `lastUpdated` for ordering, not message receive time:

```go
// Correct: Use snapshot timestamp
if snapshot.LastUpdated > lastSeenTime {
    processSnapshot(snapshot)
    lastSeenTime = snapshot.LastUpdated
}

// Incorrect: Don't rely on message arrival order
```

---

## Monitoring & Alerting

### Key Metrics to Track

1. **Message Throughput**
   - Messages per minute
   - Peak during booking rush hours

2. **Consumer Lag**
   - Time from publish to processing
   - Target: < 1 second

3. **Error Rate**
   - Parse errors
   - Processing failures
   - Target: < 0.1%

4. **Queue Depth**
   - Messages awaiting processing
   - Alert if > 1000 messages

### Example Prometheus Metrics

```go
snapshotMessagesReceived := prometheus.NewCounterVec(
    prometheus.CounterOpts{
        Name: "trip_snapshots_received_total",
        Help: "Total trip snapshots received",
    },
    []string{"trip_id", "event_type"},
)

snapshotProcessingDuration := prometheus.NewHistogramVec(
    prometheus.HistogramOpts{
        Name: "trip_snapshot_processing_seconds",
        Help: "Time to process snapshot",
    },
    []string{"trip_id"},
)
```

---

## Troubleshooting

### Messages Not Arriving

1. Check exchange exists:
   ```bash
   rabbitmqctl list_exchanges | grep trip_snapshots
   ```

2. Check queue is bound to exchange:
   ```bash
   rabbitmqctl list_bindings | grep trip_snapshots
   ```

3. Verify consumer is connected:
   ```bash
   rabbitmqctl list_consumers
   ```

### High Latency

1. Check message processing time
2. Monitor RabbitMQ queue depth
3. Scale consumers horizontally if needed

### Message Loss

1. Ensure queue is durable: `durable: true`
2. Use manual acknowledgment: `auto-ack: false`
3. Monitor unacknowledged messages

---

## Migration Checklist

When integrating with trip snapshots:

- [ ] Create queue with durable flag enabled
- [ ] Bind queue to `trip_snapshots` fanout exchange
- [ ] Implement consumer with manual acknowledgment
- [ ] Add error handling for malformed messages
- [ ] Implement idempotency checks
- [ ] Set up monitoring/alerting
- [ ] Test with high booking volume
- [ ] Document consumer implementation
- [ ] Set up graceful shutdown for consumer
- [ ] Monitor for message lag

---

## Support & Questions

For issues or questions regarding snapshot integration:

1. Check logs: `[TripSnapshotService]` and `[RabbitMQPublisher]` prefixes
2. Review BookingService flow for event timing
3. Check RabbitMQ management console: `http://rabbitmq:15672`

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-01-07 | Initial documentation |
