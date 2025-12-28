# RabbitMQ Trip Events Documentation

## Overview

The Trip Service publishes real-time trip lifecycle events to a RabbitMQ fanout exchange. Other services can subscribe to these events to stay synchronized with trip state changes.

## Exchange Details

- **Exchange Name:** `tripservice.trips.updates`
- **Exchange Type:** Fanout (broadcasts to all bound queues)
- **Durability:** Yes (persists across broker restarts)

## Consuming Events

To consume trip events:

1. **Declare the fanout exchange** (idempotent):
   ```
   exchange_declare(
     exchange='tripservice.trips.updates',
     exchange_type='fanout',
     durable=true
   )
   ```

2. **Declare your own queue** (with a unique name for your service):
   ```
   queue_declare(
     queue='your-service.trips-queue',
     durable=true
   )
   ```

3. **Bind your queue to the exchange**:
   ```
   queue_bind(
     exchange='tripservice.trips.updates',
     queue='your-service.trips-queue',
     routing_key='' // empty for fanout
   )
   ```

4. **Consume messages** from your queue

## Events Published

### 1. Trip Created

**Event Type:** `created`

**When:** A new trip is created and stored in the database

**Message Structure:**
```json
{
  "event": "created",
  "data": {
    "id": 445,
    "route_id": 10,
    "vehicle_id": 4,
    "vehicle": {
      "id": 4,
      "company_id": 1,
      "company_name": "TransportCo",
      "capacity": 8,
      "license_plate": "XYZ-123",
      "driver": {
        "id": 101,
        "name": "John Doe",
        "phone": "+250788123456"
      }
    },
    "status": "SCHEDULED",
    "departure_time": 1766790461,
    "connection_mode": "DIRECT",
    "price": 5000.00,
    "notes": "Airport run",
    "seats": 8,
    "is_reversed": false,
    "has_custom_waypoints": false,
    "created_at": 1766790461,
    "updated_at": 1766790461,
    "route": {
      "id": 10,
      "origin": "Kigali Downtown",
      "destination": "Kigali Airport",
      "route_price": 5000.00,
      "distance": 18.5,
      "duration": 1200,
      "city_route": false,
      "waypoints": null
    },
    "waypoints": [
      {
        "id": 1001,
        "trip_id": 445,
        "location_id": 20,
        "location_name": "Passthrough Stop",
        "order": 1,
        "price": null,
        "is_custom": false,
        "is_pass_through": true,
        "remaining_distance": 0.0,
        "remaining_time": 0,
        "is_passed": false,
        "passed_timestamp": null
      }
    ]
  }
}
```

### 2. Trip Cancelled

**Event Type:** `cancelled`

**When:** An active trip (SCHEDULED or IN_PROGRESS) is cancelled

**Message Structure:** Same as "created" event, but with updated `status` = `"CANCELLED"` and `updated_at` timestamp

```json
{
  "event": "cancelled",
  "data": {
    // ... same Trip object structure
    "status": "CANCELLED",
    "updated_at": 1766790500
  }
}
```

## Data Types & Field Reference

### Trip Object

| Field | Type | Description |
|-------|------|-------------|
| `id` | int64 | Unique trip identifier |
| `route_id` | int64 | Route reference |
| `vehicle_id` | int64 | Vehicle assignment |
| `vehicle` | Vehicle | Vehicle snapshot (see below) |
| `status` | string | SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED |
| `departure_time` | int64 | Unix timestamp (seconds) |
| `connection_mode` | string | DIRECT, SHUTTLE |
| `price` | float64 | Trip fare in currency units |
| `notes` | string | Optional trip notes |
| `seats` | int | Available seats |
| `is_reversed` | bool | Whether trip is reverse route |
| `has_custom_waypoints` | bool | Whether custom waypoints override route |
| `created_at` | int64 | Unix timestamp (seconds) |
| `updated_at` | int64 | Unix timestamp (seconds) |
| `route` | Route | Route details (waypoints array will be null in events) |
| `waypoints` | []TripWaypoint | Array of waypoints for this trip |

### Vehicle Object

| Field | Type | Description |
|-------|------|-------------|
| `id` | int64 | Vehicle ID |
| `company_id` | int64 | Operating company |
| `company_name` | string | Company display name |
| `capacity` | int | Passenger capacity |
| `license_plate` | string | Vehicle registration |
| `driver` | DriverSnapshot | Driver info |

### TripWaypoint Object

| Field | Type | Description |
|-------|------|-------------|
| `id` | int64 | Waypoint ID |
| `trip_id` | int64 | Trip reference |
| `location_id` | int64 | Location reference |
| `location_name` | string | Location display name |
| `order` | int | Sequence (1 = first stop, etc.) |
| `price` | float64 | Price at this stop (null for passthrough) |
| `is_custom` | bool | Custom waypoint vs route waypoint |
| `is_pass_through` | bool | Passthrough (not a full stop) |
| `remaining_distance` | float64 | Distance to destination in meters |
| `remaining_time` | int64 | Time to destination in seconds |
| `is_passed` | bool | Has been completed |
| `passed_timestamp` | int64 | When waypoint was passed (unix timestamp, null if not yet) |

## Example: Node.js Consumer

```javascript
const amqp = require('amqplib/callback_api');

const EXCHANGE = 'tripservice.trips.updates';
const QUEUE = 'my-service.trips-queue';

amqp.connect('amqp://user:password@localhost/', function(err, conn) {
  if (err) throw err;

  conn.createChannel(function(err, ch) {
    if (err) throw err;

    // Declare exchange
    ch.assertExchange(EXCHANGE, 'fanout', { durable: true });

    // Declare queue
    ch.assertQueue(QUEUE, { durable: true }, function(err, q) {
      if (err) throw err;

      // Bind queue to exchange
      ch.bindQueue(q.queue, EXCHANGE, '');

      // Consume messages
      ch.consume(q.queue, function(msg) {
        if (msg) {
          const event = JSON.parse(msg.content.toString());
          
          console.log(`Trip ${event.data.id} - Event: ${event.event}`);
          console.log(`Status: ${event.data.status}`);
          console.log(`Vehicle: ${event.data.vehicle.license_plate}`);
          
          // Handle based on event type
          if (event.event === 'created') {
            console.log('New trip created:', event.data);
            // Update your local trip records
          } else if (event.event === 'cancelled') {
            console.log('Trip cancelled:', event.data);
            // Mark trip as cancelled in your system
          }
          
          ch.ack(msg);
        }
      }, { noAck: false });
    });
  });
});
```

## Example: Python Consumer

```python
import pika
import json

EXCHANGE = 'tripservice.trips.updates'
QUEUE = 'my-service.trips-queue'

connection = pika.BlockingConnection(
    pika.ConnectionParameters('localhost')
)
channel = connection.channel()

# Declare exchange
channel.exchange_declare(
    exchange=EXCHANGE,
    exchange_type='fanout',
    durable=True
)

# Declare queue
channel.queue_declare(queue=QUEUE, durable=True)

# Bind queue to exchange
channel.queue_bind(exchange=EXCHANGE, queue=QUEUE)

def callback(ch, method, properties, body):
    event = json.loads(body)
    print(f"Trip {event['data']['id']} - Event: {event['event']}")
    print(f"Status: {event['data']['status']}")
    
    if event['event'] == 'created':
        print('New trip created:', event['data'])
        # Process trip creation
    elif event['event'] == 'cancelled':
        print('Trip cancelled:', event['data'])
        # Process trip cancellation
    
    ch.basic_ack(delivery_tag=method.delivery_tag)

channel.basic_consume(queue=QUEUE, on_message_callback=callback)

print('Waiting for messages...')
channel.start_consuming()
```

## Example: Go Consumer

```go
package main

import (
	"encoding/json"
	"fmt"
	"log"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	EXCHANGE = "tripservice.trips.updates"
	QUEUE    = "my-service.trips-queue"
)

type TripEvent struct {
	Event string      `json:"event"`
	Data  interface{} `json:"data"`
}

func main() {
	conn, err := amqp.Dial("amqp://user:password@localhost/")
	if err != nil {
		log.Fatal(err)
	}
	defer conn.Close()

	ch, err := conn.Channel()
	if err != nil {
		log.Fatal(err)
	}
	defer ch.Close()

	// Declare exchange
	err = ch.ExchangeDeclare(EXCHANGE, "fanout", true, false, false, false, nil)
	if err != nil {
		log.Fatal(err)
	}

	// Declare queue
	q, err := ch.QueueDeclare(QUEUE, true, false, false, false, nil)
	if err != nil {
		log.Fatal(err)
	}

	// Bind queue to exchange
	err = ch.QueueBind(q.Name, "", EXCHANGE, false, nil)
	if err != nil {
		log.Fatal(err)
	}

	// Consume messages
	msgs, err := ch.Consume(q.Name, "", false, false, false, false, nil)
	if err != nil {
		log.Fatal(err)
	}

	log.Println("Waiting for trip events...")

	for d := range msgs {
		var event TripEvent
		if err := json.Unmarshal(d.Body, &event); err != nil {
			log.Printf("Error decoding: %v", err)
			d.Nack(false, true)
			continue
		}

		fmt.Printf("Trip Event - Type: %s, Data: %+v\n", event.Event, event.Data)

		// Process based on event type
		if event.Event == "created" {
			// Handle trip creation
		} else if event.Event == "cancelled" {
			// Handle trip cancellation
		}

		d.Ack(false)
	}
}
```

## Timestamp Format

All timestamps in trip events are **Unix epoch seconds** (seconds since 1970-01-01 UTC). 

- Type: `int64`
- Example: `1766790461` = 2025-12-26 23:07:41 UTC

To convert to human-readable format:
- **JavaScript:** `new Date(timestamp * 1000).toISOString()`
- **Python:** `datetime.fromtimestamp(timestamp, tz=timezone.utc)`
- **Go:** `time.Unix(timestamp, 0).UTC()`

## Error Handling

- Messages are **not retried automatically** by the exchange—it's your queue's responsibility
- Use **durable queues** and **manual ACKs** to avoid message loss
- **Nack and requeue** failed messages rather than dropping them
- Monitor your queue depth for processing lag indicators

## Best Practices

1. **Idempotent Processing:** Trip IDs may be redelivered. Use `trip.id` + `event` type as deduplication key
2. **Handle Missing Fields:** Optional fields (e.g., `passed_timestamp`) may be null
3. **Version Your Queue Names:** Use `service-name.trips-queue` pattern to avoid conflicts
4. **Log All Events:** Keep audit trail of received events for debugging
5. **Set Reasonable TTLs:** Configure queue message TTL to prevent stale events accumulating
6. **Dead Letter Queues:** Bind a DLQ to catch poison messages

## Contact

For issues or questions about trip events, contact the Trip Service team.
