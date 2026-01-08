# Auto-Initialize Trip Snapshots Implementation

## Overview

When a trip is created in the admin aggregate service, an initial snapshot is automatically created with all seats available (zero bookings). As booking snapshots are received from the Booking Service via RabbitMQ, these initial snapshots are updated with real booking data.

## What Was Added

### 1. **Snapshot Initialization Function** (`repositories/snapshots.ts`)

Added `createInitialSnapshot(trip, carCapacity)` function that:
- Creates a snapshot when a trip is first created
- Sets all seats as available (no bookings yet)
- Initializes locations from trip's origin and destinations
- Sets summary with all zeros (no bookings)
- Returns the created snapshot

**Initial Snapshot Structure:**
```typescript
{
  tripId: "123",
  tripStatus: "SCHEDULED",
  lastUpdated: "2026-01-07T...",
  capacity: {
    totalSeats: 4,           // From car capacity
    availableSeats: 4,       // All available initially
    occupiedSeats: 0,
    pendingPaymentSeats: 0
  },
  locations: [
    { locationId: "origin", type: "ORIGIN", order: 0, ... },
    { locationId: "dest1", type: "WAYPOINT", order: 1, ... },
    { locationId: "dest2", type: "DESTINATION", order: 2, ... }
  ],
  summary: {
    totalTickets: 0,
    paidTickets: 0,
    pendingPayments: 0,
    completedDropoffs: 0
  }
}
```

### 2. **Automatic Snapshot Creation on Trip Creation**

Updated three locations where trips are created:

#### a. **Trip Event Handler** (`handleTripEvent`)
When a trip event is received and a new trip is created:
```typescript
if (!existing) {
  await tripRepository.createTrip(localTrip);
  // Create initial snapshot with all seats available
  await snapshotRepository.createInitialSnapshot(localTrip, localTrip.carDriver.car.capacity);
}
```

#### b. **Trip Service Event Handler** (`handleTripServiceEvent`)
Two creation points:
- When a TripService "created" event is received
- When a status event (completed/cancelled) is received before the trip exists

Both automatically create initial snapshots.

#### c. **Sync Service** (`syncService.ts`)
During initial sync from the main API, all new trips get initial snapshots:
```typescript
if (!existing) {
  await tripRepository.createTrip(localTrip);
  await snapshotRepository.createInitialSnapshot(localTrip, localTrip.carDriver.car.capacity);
}
```

## Data Flow

```
Trip Created Event
       ↓
┌──────────────────────────┐
│ handleTripEvent()        │
│ handleTripServiceEvent() │
│ syncService.syncTrips()  │
└──────────┬───────────────┘
           ↓
   Create Trip in DB
           ↓
   createInitialSnapshot()
       ↓
┌─────────────────────────┐
│ Initial Snapshot:       │
│ - All seats available   │
│ - All locations setup   │
│ - Zero bookings         │
└─────────────────────────┘
           ↓
   Stored in Repository
       ↓
   (Awaits RabbitMQ updates)
       ↓
┌─────────────────────────┐
│ Booking Service sends   │
│ snapshot with real data │
└──────┬──────────────────┘
       ↓
handleTripSnapshotUpdate()
       ↓
upsertSnapshot()
  (replaces initial snapshot)
```

## Locations Structure

Initial snapshots include all trip locations:

1. **ORIGIN** - First location (pickup point)
   - `order: 0`
   - `seats.availableFromHere: carCapacity`

2. **WAYPOINT** - Intermediate locations
   - `order: 1, 2, 3...`
   - `seats.availableFromHere: carCapacity`

3. **DESTINATION** - Final location (last destination)
   - `order: destinationCount`
   - `seats.availableFromHere: carCapacity`

All locations start with:
- `status: "UPCOMING"`
- `seats.pickup: 0`
- `seats.dropoff: 0`
- `seats.pendingPayment: 0`

## Behavior

### When Trip is Created
1. Trip is stored in database
2. Initial snapshot is automatically created
3. Snapshot is immediately available via `getTripSnapshot(tripId)` query
4. Snapshot has correct seat count from car capacity
5. All seats shown as available

### When Booking Service Sends Updates
1. `handleTripSnapshotUpdate()` receives the snapshot
2. `upsertSnapshot()` **replaces** the initial snapshot
3. Snapshot now shows real booking data
4. Subscribers receive updated data via `tripSnapshot` subscription
5. Locations, capacity, and summary reflect actual bookings

### Trip Status Tracking
Initial snapshot's `tripStatus` matches the trip's current status:
- `SCHEDULED` - Default for new trips
- `IN_PROGRESS` - If trip started
- `COMPLETED` - If trip already completed
- `CANCELLED` - If trip already cancelled

Status is updated when booking service snapshots are received.

## Example Usage

### Create a Trip
```
POST /graphql
{
  trip {
    id: "123"
    status: "scheduled"
    carDriver {
      car { capacity: 4 }
    }
    destinations: [...]
  }
}
```

**Result:** Initial snapshot automatically created

### Query Immediate Snapshot
```graphql
query {
  getTripSnapshot(tripId: "123") {
    tripStatus      # "SCHEDULED"
    capacity {
      totalSeats         # 4
      availableSeats     # 4
      occupiedSeats      # 0
      pendingPaymentSeats # 0
    }
  }
}
```

**Result:** Returns initial snapshot with all seats available

### Later: Booking Service Sends Real Data
```
RabbitMQ Message:
{
  tripId: "123"
  capacity {
    totalSeats: 4
    availableSeats: 1
    occupiedSeats: 2
    pendingPaymentSeats: 1
  }
}
```

**Result:** Snapshot updated, subscribers notified

## Benefits

✅ **Immediate Availability** - Snapshots available as soon as trip is created  
✅ **Correct Capacity** - Seat count matches actual vehicle  
✅ **Smooth Transition** - Initial placeholder → real data from booking service  
✅ **No Race Conditions** - Initial snapshot always exists before bookings arrive  
✅ **Complete Locations** - All trip locations pre-populated  
✅ **Consistent** - Applied across all trip creation paths (events, sync)  

## Testing

```graphql
# Create trip and immediately query snapshot
query {
  getTripSnapshot(tripId: "123") {
    capacity { availableSeats }  # Should be 4 (car capacity)
  }
}

# Subscribe to updates
subscription {
  tripSnapshot(tripId: "123") {
    capacity { availableSeats }  # Initially 4, then updates as bookings arrive
  }
}
```
