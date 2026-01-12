# Trip Snapshots Implementation Guide

## Overview

The admin aggregate service now subscribes to trip snapshots from the Booking Service via RabbitMQ's fanout exchange `bookingservice.trip.snapshot`. This enables real-time tracking of booking capacity and passenger information for each trip.

## Components Implemented

### 1. **Types** (`src/types.ts`)
Added TypeScript interfaces for the complete trip snapshot schema:
- `SnapshotSeats` - Seat information per location
- `SnapshotLocation` - Location details with seat tracking
- `SnapshotCapacity` - Overall trip capacity metrics
- `SnapshotSummary` - Booking summary
- `TripSnapshot` - Complete snapshot structure
- `TripSnapshotPublish` - Published event structure

### 2. **Repository** (`src/repositories/snapshots.ts`)
In-memory snapshot storage with the following operations:
- `upsertSnapshot(snapshot)` - Create or update a trip snapshot
- `getSnapshot(tripId)` - Retrieve a snapshot for a specific trip
- `getAllSnapshots()` - Get all stored snapshots
- `deleteSnapshot(tripId)` - Remove a snapshot
- `snapshotExists(tripId)` - Check if a snapshot exists
- `clearAllSnapshots()` - Clear all snapshots (for cleanup)

### 3. **RabbitMQ Setup** (`src/services/rabbitmq.ts`)
Extended message handlers to include trip snapshots:
- Added `onTripSnapshotUpdate` handler to `MessageHandlers` interface
- Declared `bookingservice.trip.snapshot` fanout exchange (configurable via `SNAPSHOT_EXCHANGE` env var)
- Created exclusive queue and bound it to the snapshot exchange
- Set up consumer with manual acknowledgment

### 4. **Event Handling** (`src/services/eventHandlers.ts`)
Implemented `handleTripSnapshotUpdate()` function that:
- Receives and parses trip snapshot messages
- Detects first-time snapshots (INITIALIZED events)
- Stores snapshots in the repository
- Publishes updates to GraphQL subscribers via pub/sub
- Logs snapshot events with relevant metrics

### 5. **Pub/Sub Integration** (`src/services/pubsub.ts`)
Added new subscription trigger:
- `TRIP_SNAPSHOT_UPDATED(tripId)` - Triggers for snapshot changes

### 6. **GraphQL Schema** (`src/graphql/schema.ts`)
Added new types:
```graphql
type SnapshotSeats {
  pickup: Int!
  dropoff: Int!
  pendingPayment: Int!
  availableFromHere: Int!
  totalAmountPaid: Float!
  totalAmountPending: Float!
}

type SnapshotLocation {
  locationId: ID!
  type: String!
  order: Int!
  status: String!
  seats: SnapshotSeats!
}

type SnapshotCapacity {
  totalSeats: Int!
  availableSeats: Int!
  occupiedSeats: Int!
  pendingPaymentSeats: Int!
  totalAmountPaid: Float!
  totalAmountPending: Float!
}

type SnapshotSummary {
  totalTickets: Int!
  paidTickets: Int!
  pendingPayments: Int!
  completedDropoffs: Int!
}

type TripSnapshot {
  tripId: ID!
  tripStatus: String!
  lastUpdated: String!
  capacity: SnapshotCapacity!
  locations: [SnapshotLocation!]!
  summary: SnapshotSummary!
}
```

### 7. **GraphQL Resolvers** (`src/graphql/schema.ts`)

#### Query
```graphql
getTripSnapshot(tripId: ID!): TripSnapshot
```
Retrieves the latest snapshot for a trip.

#### Subscription
```graphql
tripSnapshot(tripId: ID!): TripSnapshot
```
Subscribes to live updates for a trip's snapshot. Returns initial snapshot (if exists) followed by updates.

## Event Types

The snapshot consumer handles four event types:

1. **INITIALIZED** - First booking created for a trip
   - All seats available
   - Use case: Frontend initializes trip display

2. **BOOKING_CREATED** - New booking awaiting payment
   - `availableSeats` decreases
   - `pendingPaymentSeats` increases

3. **PAYMENT_CONFIRMED** - Payment processed
   - `pendingPaymentSeats` decreases
   - `occupiedSeats` increases

4. **BOOKING_EXPIRED** - Payment not received within 5 minutes
   - `pendingPaymentSeats` decreases
   - `availableSeats` increases (seats released)

## Usage Examples

### Get Current Snapshot
```graphql
query {
  getTripSnapshot(tripId: "123") {
    tripId
    tripStatus
    capacity {
      totalSeats
      availableSeats
      occupiedSeats
      pendingPaymentSeats
    }
    summary {
      totalTickets
      paidTickets
      pendingPayments
    }
  }
}
```

### Subscribe to Live Updates
```graphql
subscription {
  tripSnapshot(tripId: "123") {
    tripId
    tripStatus
    lastUpdated
    capacity {
      availableSeats
    }
    summary {
      totalTickets
      paidTickets
    }
  }
}
```

## Configuration

Set the `SNAPSHOT_EXCHANGE` environment variable to customize the exchange name:
```bash
SNAPSHOT_EXCHANGE=bookingservice.trip.snapshot  # Default
```

## Flow Diagram

```
Booking Service
    ↓
[AMQP Publisher]
    ↓
bookingservice.trip.snapshot (Fanout Exchange)
    ↓
[Admin Aggregate Queue] ← (exclusive, auto-delete)
    ↓
handleTripSnapshotUpdate()
    ↓
┌─────────────────────────────┐
│  Snapshot Repository (Store) │
│  Map<tripId, TripSnapshot>   │
└─────────────────────────────┘
    ↓
pubsub.publish()
    ↓
GraphQL Subscription Listeners
```

## Implementation Details

### First Snapshot Detection
When a snapshot is received for a trip:
1. Check if snapshot already exists in repository
2. If not, it's the first one (INITIALIZED event)
3. Store it and log appropriately

### Storage
Snapshots are stored in-memory using a `Map`. For production use with persistence, consider:
- PostgreSQL storage via `snapshotRepository`
- Redis cache for quick access
- TTL-based cleanup for old snapshots

### Manual Acknowledgment
Messages use manual acknowledgment (`auto-ack: false`):
- Successfully processed: `ch.ack()`
- Processing error: `ch.nack()` (message not requeued)

This ensures messages aren't lost and failed messages don't block the queue.

## Best Practices

1. **Idempotency** - Handle duplicate snapshot messages gracefully using `lastUpdated` timestamp
2. **Subscription Cleanup** - WebSocket connections automatically clean up when closed
3. **Error Handling** - Failures to process snapshots don't stop the consumer
4. **Real-time Updates** - Pub/sub ensures instant propagation to WebSocket subscribers

## Testing

### Test Snapshot Receipt
```bash
# Publish a test message to the fanout exchange
amqplib-publish --exchange bookingservice.trip.snapshot \
  --type fanout \
  --message '{"tripId":"123","tripStatus":"SCHEDULED",...}'
```

### Test GraphQL Query
```graphql
query TestSnapshot {
  getTripSnapshot(tripId: "123") {
    tripId
    capacity { availableSeats }
  }
}
```

### Test Subscription
Use Apollo Studio or GraphQL client to subscribe and watch for real-time updates as bookings are made on the trip.
