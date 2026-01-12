# Trip Snapshot System

## Overview

The Trip Snapshot System provides real-time tracking of seat availability and booking status for each trip location. It validates bookings based on trip progression (SCHEDULED/IN_PROGRESS), blocks bookings from passed locations, and publishes capacity updates to RabbitMQ on every booking lifecycle event.

## Key Features

### 1. Location-Based Booking Validation

**SCHEDULED Status:**
- Only the origin location is bookable as pickup
- All waypoints and destination are upcoming

**IN_PROGRESS Status:**
- Origin is marked as PASSED and cannot be booked
- Only waypoints that have NOT been passed (`IsPassed=false`) are bookable
- System tracks which waypoint is CURRENT (`IsNext=true`)
- Destination cannot be used as pickup location

### 2. Real-Time Seat Tracking

The snapshot tracks seat availability at three levels:

#### Trip-Level Capacity
```json
{
  "totalSeats": 30,
  "availableSeats": 8,
  "occupiedSeats": 18,
  "pendingPaymentSeats": 4,
  "totalAmountPaid": 1800.00,
  "totalAmountPending": 400.00
}
```

#### Location-Level Seats
```json
{
  "locationId": "123",
  "type": "WAYPOINT",
  "order": 2,
  "status": "CURRENT",
  "seats": {
    "pickup": 6,
    "dropoff": 4,
    "pendingPayment": 2,
    "availableFromHere": 4,
    "totalAmountPaid": 600.00,
    "totalAmountPending": 200.00
  }
}
```

#### Summary Statistics
```json
{
  "totalTickets": 22,
  "paidTickets": 18,
  "pendingPayments": 4,
  "completedDropoffs": 18
}
```

### 3. Booking Lifecycle Integration

The snapshot updates automatically at three critical points:

#### A. Booking Created (Pending Payment)
```
pendingPaymentSeats +N
availableSeats -N
totalAmountPending +amount (trip level)
location.pendingPayment +N (for both pickup and dropoff)
location.totalAmountPending +amount (for both pickup and dropoff)
```

#### B. Payment Confirmed
```
pendingPaymentSeats -N
occupiedSeats +N
totalAmountPending -amount, totalAmountPaid +amount (trip level)
location.pendingPayment -N
location.pickup +N (at pickup location)
location.dropoff +N (at dropoff location)
location.totalAmountPending -amount, totalAmountPaid +amount (at both locations)
```

#### C. Booking Expired/Cancelled
```
pendingPaymentSeats -N
availableSeats +N
totalAmountPending -amount (trip level)
location.pendingPayment -N
location.totalAmountPending -amount (at both pickup and dropoff)
```

### 4. RabbitMQ Integration

**Fanout Exchange:** `bookingservice.trip.snapshot`

Published on every snapshot update with full trip status:
- Trip ID and status
- Complete capacity breakdown
- All location details with seat counts
- Summary statistics

**Event Types:**
- `INITIALIZED` - First booking on trip
- `BOOKING_CREATED` - Pending payment hold
- `PAYMENT_CONFIRMED` - Seat occupied
- `BOOKING_EXPIRED` - Seat released

## Architecture

### Components

1. **TripSnapshotService** - Business logic for snapshot lifecycle
   - `InitializeSnapshot()` - Create first snapshot
   - `OnBookingCreated()` - Handle pending bookings
   - `OnPaymentConfirmed()` - Handle payment completion
   - `OnBookingExpired()` - Handle cancellations
   - `ValidateBookableLocation()` - Check trip progression rules
   - `CheckSeatAvailability()` - Validate seat counts

2. **TripSnapshotRepository** - Database operations with transactions
   - Row-level locking with `SELECT FOR UPDATE`
   - JSONB columns for flexible schema
   - Single updateable record per trip

3. **BookingService Integration**
   - Pre-booking validation (location + seats)
   - Post-booking snapshot update
   - Payment confirmation handler

4. **BookingMonitor Integration**
   - Expired booking cleanup
   - Snapshot rollback on expiration

### Database Schema

```sql
CREATE TABLE trip_snapshots (
    id VARCHAR(255) PRIMARY KEY,
    trip_id INTEGER NOT NULL UNIQUE,
    trip_status VARCHAR(50) NOT NULL,
    last_updated TIMESTAMP NOT NULL,
    capacity JSONB NOT NULL,
    locations JSONB NOT NULL,
    summary JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

**JSONB Columns:**
- `capacity` - Trip-level seat counts
- `locations` - Array of location objects with seat details
- `summary` - Aggregate booking statistics

### Concurrency Control

**Database Transactions with Row Locking:**
```go
tx.QueryRowContext(ctx, "SELECT ... FOR UPDATE")
// Perform calculations
tx.ExecContext(ctx, "UPDATE ...")
tx.Commit()
```

Ensures atomic snapshot updates even with concurrent bookings.

## Configuration

### Environment Variables

```bash
# RabbitMQ Snapshot Exchange
SNAPSHOT_EXCHANGE=bookingservice.trip.snapshot

# Database (must support JSONB)
DATABASE_URL=postgres://user:pass@host/db?sslmode=disable
```

### Migration

Run the migration SQL:
```bash
psql -U postgres -d cavgobooks -f migrations/create_trip_snapshots.sql
```

## API Integration

### Booking Creation Flow

```
1. Validate BookingRequest
2. Fetch Trip details
3. Validate bookable location (snapshot service)
   ├─ SCHEDULED: only origin
   └─ IN_PROGRESS: unpassed waypoints
4. Check seat availability (snapshot service)
5. Calculate pricing
6. Create booking (status=PENDING)
7. Generate tickets
8. Create payment (status=PENDING)
9. Update snapshot (OnBookingCreated)
   └─ Publishes to RabbitMQ
10. Return BookingResponse
```

### Payment Confirmation Flow

```
1. Receive payment confirmation
2. Update payment status (COMPLETED)
3. Update booking status (CONFIRMED)
4. Update snapshot (OnPaymentConfirmed)
   └─ Publishes to RabbitMQ
5. Return updated BookingResponse
```

### Expiration Flow (Background Monitor)

```
1. Detect bookings pending >5 minutes
2. Cancel booking
3. Fail payment
4. Update snapshot (OnBookingExpired)
   └─ Publishes to RabbitMQ
```

## Snapshot Calculation Logic

### availableFromHere Formula

For each location in order:
```go
cumulativePickup += location.seats.pickup
cumulativeDropoff += location.seats.dropoff
currentPassengers = cumulativePickup - cumulativeDropoff

availableFromHere = totalSeats - currentPassengers - pendingPaymentSeats
```

This calculates how many seats can be booked starting from each location, accounting for:
- Passengers already on board
- Passengers who will drop off at/after this location
- Pending payments holding seats

### Location Status Determination

```
SCHEDULED:
  - Origin → CURRENT
  - All others → UPCOMING

IN_PROGRESS:
  - Origin → PASSED
  - Waypoints:
    - If IsPassed=true → PASSED
    - If IsNext=true → CURRENT
    - Else → UPCOMING
  - Destination → UPCOMING
```

## Console Logging

Every snapshot update logs detailed output:

```
========== TRIP SNAPSHOT [BOOKING_CREATED] ==========
TripID: 123 | Status: IN_PROGRESS | LastUpdated: 2025-12-28T...

Capacity:
  Total Seats:           30
  Available Seats:       7
  Occupied Seats:        18
  Pending Payment Seats: 5

Locations:
  [ORIGIN] 1 (Order: 0, Status: PASSED)
    Pickup: 12 | Dropoff: 0 | Pending: 1 | AvailableFromHere: 0
  [WAYPOINT] 2 (Order: 1, Status: CURRENT)
    Pickup: 6 | Dropoff: 4 | Pending: 3 | AvailableFromHere: 4
  [DESTINATION] 3 (Order: 2, Status: UPCOMING)
    Pickup: 0 | Dropoff: 14 | Pending: 1 | AvailableFromHere: 0

Summary:
  Total Tickets:       23
  Paid Tickets:        18
  Pending Payments:    5
  Completed Dropoffs:  18

Full JSON:
{ ... complete snapshot ... }
==========================================
```

## Error Handling

The snapshot system is designed to be non-blocking:
- Snapshot errors during booking don't fail the booking
- Errors are logged but allow the transaction to proceed
- RabbitMQ publish failures are logged but don't block

This ensures core booking functionality remains available even if snapshot tracking has issues.

## Testing Scenarios

### 1. First Booking on Trip
- Initializes snapshot with trip.Seats as totalSeats
- Sets all locations to UPCOMING (except origin=CURRENT if SCHEDULED)

### 2. Booking from Passed Location
- Validation fails with clear error message
- Booking is rejected before any database writes

### 3. Insufficient Seats
- Checks both overall and location-specific availability
- Rejects if either constraint violated

### 4. Concurrent Bookings
- Database transaction with row lock prevents race conditions
- Each booking sees consistent snapshot state

### 5. Payment Confirmation
- Converts pending seats to occupied
- Updates pickup/dropoff counters
- Publishes updated availability

### 6. Booking Expiration
- Releases held seats back to available pool
- Decrements pending payment counters
- Maintains integrity of occupied seat counts

## Booking Price Calculation

The system uses intelligent price calculation based on pickup and dropoff locations, ensuring accurate pricing for all route segments.

### Pricing Structure

**Trip Route Example:** A → B (waypoint, $50) → C (waypoint, $80) → D (destination, $100)

- **Origin (A):** Free pickup (price = $0)
- **Waypoint B:** Cumulative price from origin = $50
- **Waypoint C:** Cumulative price from origin = $80
- **Destination (D):** Full trip price = $100

### Calculation Rules

The price per ticket is calculated based on the segment traveled:

#### 1. Origin to Waypoint
**Example:** A → B with 5 tickets
```
Price = waypoint_B.price × tickets
      = $50 × 5
      = $250
```

#### 2. Origin to Destination
**Example:** A → D with 5 tickets
```
Price = trip.Route.RoutePrice × tickets
      = $100 × 5
      = $500
```

#### 3. Waypoint to Waypoint
**Example:** B → C with 5 tickets
```
Price = (waypoint_C.price - waypoint_B.price) × tickets
      = ($80 - $50) × 5
      = $30 × 5
      = $150
```

#### 4. Waypoint to Destination
**Example:** C → D with 5 tickets
```
Price = (trip.Route.RoutePrice - waypoint_C.price) × tickets
      = ($100 - $80) × 5
      = $20 × 5
      = $100
```

### Implementation

The `BookingService.CreateBooking()` method implements this logic:

```go
// Determine if locations are origin/destination
isPickupOrigin := (pickupLocationID == originID)
isDropoffDestination := (dropoffLocationID == destinationID)

// Calculate price based on segment type
if isPickupOrigin && isDropoffDestination {
    // A → D: Use full route price
    pricePerTicket = trip.Route.RoutePrice
} else if isPickupOrigin && !isDropoffDestination {
    // A → B or A → C: Use waypoint price
    pricePerTicket = dropoffWaypointPrice
} else if !isPickupOrigin && isDropoffDestination {
    // B → D or C → D: Destination price - pickup waypoint price
    pricePerTicket = trip.Route.RoutePrice - pickupWaypointPrice
} else {
    // B → C: Dropoff waypoint price - pickup waypoint price
    pricePerTicket = dropoffWaypointPrice - pickupWaypointPrice
}

totalAmount = pricePerTicket × numberOfTickets
```

### Financial Tracking in Snapshots

The snapshot system tracks financial data at two levels:

**Trip-Level Financial Tracking:**
- `totalAmountPaid`: Sum of all confirmed payment amounts
- `totalAmountPending`: Sum of all pending payment amounts

**Location-Level Financial Tracking:**
- Each location tracks amounts for bookings involving that location
- Both pickup and dropoff locations accumulate amounts
- Amounts move from pending to paid when payment is confirmed

**Example:** A booking from B → C for $150:
- Location B: `totalAmountPending += $150`
- Location C: `totalAmountPending += $150`
- Trip: `totalAmountPending += $150`

When payment is confirmed:
- Location B: `totalAmountPending -= $150`, `totalAmountPaid += $150`
- Location C: `totalAmountPending -= $150`, `totalAmountPaid += $150`
- Trip: `totalAmountPending -= $150`, `totalAmountPaid += $150`

### Validation

The system validates:
1. **Location Order:** Pickup must be before dropoff in route sequence
2. **Seat Availability:** Sufficient seats must be available
3. **Location Bookability:** Based on trip status (SCHEDULED/IN_PROGRESS)
4. **Price Calculation:** Always results in non-negative amount

## Future Enhancements

1. **Historical Snapshots** - Version snapshots for audit trail
2. **Snapshot Queries** - API endpoints to fetch current snapshot
3. **Real-time Updates** - WebSocket push to frontend
4. **Capacity Alerts** - Notify when trip nearing full
5. **Analytics** - Track booking patterns per location
6. **Revenue Analytics** - Real-time revenue tracking per location and trip
