# Session-Based SSE Implementation Guide

## Overview

This implementation uses temporary UUIDs (sessions) to manage SSE subscriptions for paginated trip data. Each page fetch returns a session UUID that can be used to connect to SSE events for that specific trip range.

## How It Works

### 1. **Page Fetch with Session**
When you fetch trips, you get a session UUID only for new sessions:

```bash
# First page (creates new session)
GET /trips?limit=10&offset=0

Response:
{
  "trips": [...],
  "total": 100,
  "limit": 10,
  "offset": 0,
  "sse_uuid": "a1b2c3d4e5f6g7h8"  // Only returned for new sessions
}

# Second page (updates existing session)
GET /trips?limit=10&offset=10&session_uuid=a1b2c3d4e5f6g7h8

Response:
{
  "trips": [...],
  "total": 100,
  "limit": 10,
  "offset": 10
  // No sse_uuid returned when updating existing session
}
```

### 2. **SSE Connection with Session**
Connect to SSE using the session UUID:

```javascript
const eventSource = new EventSource(`/events/${sessionUUID}`);
```

### 3. **Progressive Subscription**
As user scrolls to more pages, the session accumulates trip IDs:

```bash
# Second page (updates existing session)
GET /trips?limit=10&offset=10&session_uuid=a1b2c3d4e5f6g7h8

Response:
{
  "trips": [...],
  "total": 100,
  "limit": 10,
  "offset": 10,
  "sse_uuid": "a1b2c3d4e5f6g7h8"  // Same session
}
```

## SSE Events Documentation

### **Event Types and Structures**

All SSE events follow this format:
```
event: {event_type}
data: {JSON_data}
```

### **1. Connection Events**

#### **Connected Event**
Sent when SSE connection is established.

```javascript
event: connected
data: {
  "type": "connected",
  "message": "SSE connection established",
  "client_id": "client_1752994156954573005",
  "session_uuid": "71c0acd81209189811d09e9aba152e40",
  "trip_count": 15
}
```

#### **Heartbeat Event**
Sent every 30 seconds to keep connection alive.

```javascript
event: heartbeat
data: {
  "type": "heartbeat",
  "timestamp": "2025-07-20T06:49:46Z"
}
```

### **2. Trip Lifecycle Events**

#### **Trip Created Event**
Sent when a new trip is created.

```javascript
event: created
data: {
  "event": "created",
  "data": {
    "id": 25,
    "route_id": 5,
    "vehicle_id": 12,
    "vehicle": {
      "id": 12,
      "company_id": 3,
      "company_name": "City Transport",
      "capacity": 45,
      "license_plate": "ABC123",
      "driver": {
        "name": "John Doe",
        "phone": "+1234567890"
      }
    },
    "status": "SCHEDULED",
    "departure_time": 1752993960,
    "completion_time": null,
    "connection_mode": "ONLINE",
    "notes": "Regular route",
    "seats": 45,
    "remaining_time_to_destination": null,
    "remaining_distance_to_destination": null,
    "is_reversed": false,
    "current_speed": null,
    "current_latitude": null,
    "current_longitude": null,
    "has_custom_waypoints": false,
    "created_at": "2025-07-20T06:49:20Z",
    "updated_at": "2025-07-20T06:49:20Z"
  }
}
```

#### **Trip Started Event**
Sent when a trip status changes to "IN_PROGRESS".

```javascript
event: started
data: {
  "event": "started",
  "data": {
    "id": 25,
    "status": "IN_PROGRESS",
    "updated_at": "2025-07-20T06:50:00Z",
    // ... other trip fields
  }
}
```

#### **Trip Updated Event**
Sent when any trip field is updated (progress, location, etc.).

```javascript
event: updated
data: {
  "event": "updated",
  "data": {
    "id": 25,
    "status": "IN_PROGRESS",
    "remaining_time_to_destination": 1800,
    "remaining_distance_to_destination": 5000.5,
    "current_speed": 45.2,
    "current_latitude": 40.7128,
    "current_longitude": -74.0060,
    "updated_at": "2025-07-20T06:51:00Z",
    // ... other trip fields
  }
}
```

#### **Trip Completed Event**
Sent when a trip status changes to "COMPLETED".

```javascript
event: completed
data: {
  "event": "completed",
  "data": {
    "id": 25,
    "status": "COMPLETED",
    "completion_time": 1752994560,
    "updated_at": "2025-07-20T06:52:00Z",
    // ... other trip fields
  }
}
```

### **3. Seat Management Events**

#### **Seats Reduced Event**
Sent when seats are reduced due to booking.

```javascript
event: seats_reduced
data: {
  "event": "seats_reduced",
  "data": {
    "id": 25,
    "seats": 44,  // Reduced from 45
    "updated_at": "2025-07-20T06:53:00Z",
    // ... other trip fields
  }
}
```

#### **Seats Restored Event**
Sent when seats are restored due to failed payment.

```javascript
event: seats_restored
data: {
  "event": "seats_restored",
  "data": {
    "id": 25,
    "seats": 45,  // Restored from 44
    "updated_at": "2025-07-20T06:54:00Z",
    // ... other trip fields
  }
}
```

### **4. Waypoint Events**

#### **Waypoint Passed Event**
Sent when a waypoint is marked as passed.

```javascript
event: waypoint_passed
data: {
  "event": "waypoint_passed",
  "data": {
    "id": 25,
    "waypoint_id": 123,
    "waypoint_order": 2,
    "passed_timestamp": 1752994560,
    "updated_at": "2025-07-20T06:55:00Z",
    // ... other trip fields
  }
}
```

### **5. Custom Events**

#### **Session Update Event**
Sent when session trip IDs are modified.

```javascript
event: session_updated
data: {
  "event": "session_updated",
  "data": {
    "session_uuid": "71c0acd81209189811d09e9aba152e40",
    "action": "add",  // or "remove"
    "trip_ids": [26, 27, 28],
    "total_trips_in_session": 18,
    "timestamp": "2025-07-20T06:56:00Z"
  }
}
```

## Client-Side Event Handling

### **Basic Event Handling**

```javascript
const eventSource = new EventSource(`/events/${sessionUUID}`);

// Handle connection events
eventSource.addEventListener('connected', (event) => {
  const data = JSON.parse(event.data);
  console.log('Connected to SSE:', data);
});

// Handle heartbeat
eventSource.addEventListener('heartbeat', (event) => {
  const data = JSON.parse(event.data);
  console.log('Heartbeat received:', data.timestamp);
});

// Handle trip events
eventSource.addEventListener('created', (event) => {
  const data = JSON.parse(event.data);
  console.log('New trip created:', data.data);
});

eventSource.addEventListener('updated', (event) => {
  const data = JSON.parse(event.data);
  console.log('Trip updated:', data.data);
});

eventSource.addEventListener('completed', (event) => {
  const data = JSON.parse(event.data);
  console.log('Trip completed:', data.data);
});

// Handle seat events
eventSource.addEventListener('seats_reduced', (event) => {
  const data = JSON.parse(event.data);
  console.log('Seats reduced:', data.data);
});

eventSource.addEventListener('seats_restored', (event) => {
  const data = JSON.parse(event.data);
  console.log('Seats restored:', data.data);
});

// Handle errors
eventSource.onerror = (error) => {
  console.error('SSE connection error:', error);
};
```

### **React Hook with Event Handling**

```typescript
import { useState, useEffect, useCallback } from 'react';

interface Trip {
  id: number;
  status: string;
  seats: number;
  // ... other fields
}

interface UseSessionSSEReturn {
  trips: Map<number, Trip>;
  sessionUUID: string | null;
  isConnected: boolean;
  fetchPage: (page: number) => Promise<void>;
  connectSSE: () => void;
  addTripIds: (tripIds: number[]) => Promise<void>;
  removeTripIds: (tripIds: number[]) => Promise<void>;
}

export function useSessionSSE(): UseSessionSSEReturn {
  const [trips, setTrips] = useState<Map<number, Trip>>(new Map());
  const [sessionUUID, setSessionUUID] = useState<string | null>(null);
  const [isConnected, setIsConnected] = useState(false);
  const [eventSource, setEventSource] = useState<EventSource | null>(null);

  const handleTripEvent = useCallback((event: MessageEvent) => {
    const data = JSON.parse(event.data);
    const trip = data.data;
    
    setTrips(prev => {
      const newTrips = new Map(prev);
      newTrips.set(trip.id, trip);
      return newTrips;
    });
    
    console.log(`Trip ${trip.id} ${data.event}:`, trip);
  }, []);

  const connectSSE = useCallback(() => {
    if (!sessionUUID) return;
    
    if (eventSource) {
      eventSource.close();
    }
    
    const newEventSource = new EventSource(`/events/${sessionUUID}`);
    
    newEventSource.addEventListener('connected', (event) => {
      const data = JSON.parse(event.data);
      setIsConnected(true);
      console.log('SSE connected:', data);
    });
    
    // Handle all trip events
    newEventSource.addEventListener('created', handleTripEvent);
    newEventSource.addEventListener('updated', handleTripEvent);
    newEventSource.addEventListener('completed', handleTripEvent);
    newEventSource.addEventListener('started', handleTripEvent);
    newEventSource.addEventListener('seats_reduced', handleTripEvent);
    newEventSource.addEventListener('seats_restored', handleTripEvent);
    
    newEventSource.addEventListener('heartbeat', (event) => {
      const data = JSON.parse(event.data);
      console.log('Heartbeat:', data.timestamp);
    });
    
    newEventSource.onerror = () => {
      setIsConnected(false);
      console.error('SSE connection error');
    };
    
    setEventSource(newEventSource);
  }, [sessionUUID, handleTripEvent]);

  // ... rest of the hook implementation
}
```

## 🔄 Adding or Removing Trip IDs from a Session (Frontend Guide)

You can dynamically update which trips your session is monitoring by sending a POST request to the session endpoint.

### **API Endpoint**

```
POST /events/{session_uuid}
Content-Type: application/json
```

### **Request Body**

| Field      | Type     | Description                                 |
|------------|----------|---------------------------------------------|
| `action`   | string   | `"add"` to monitor new trips, `"remove"` to stop monitoring trips |
| `trip_ids` | int[]    | Array of trip IDs to add or remove          |

---

### **Add Trip IDs Example**

```js
// Add trips 101, 102, 103 to the current session
await fetch(`/events/${sessionUUID}`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    action: 'add',
    trip_ids: [101, 102, 103]
  })
});
```

### **Remove Trip IDs Example**

```js
// Remove trips 101 and 102 from the current session
await fetch(`/events/${sessionUUID}`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    action: 'remove',
    trip_ids: [101, 102]
  })
});
```

---

### **Response**

- If the session is valid and updated:
    ```json
    {
      "success": true,
      "message": "Successfully added 3 trip IDs to session",
      "session_uuid": "your-session-uuid",
      "action": "add",
      "trip_ids": [101, 102, 103]
    }
    ```
- If the session is expired or invalid, a new session is created and returned:
    ```json
    {
      "success": true,
      "message": "Created new session with 3 trip IDs",
      "session_uuid": "new-session-uuid",
      "action": "add",
      "trip_ids": [101, 102, 103]
    }
    ```

**Important:**  
If the response contains a new `session_uuid`, update your frontend to use this new UUID for all future requests and SSE connections.

---

### **Best Practices**

- **Always check the response:** If a new `session_uuid` is returned, update your local state and reconnect your SSE stream.
- **You can add or remove trip IDs at any time**—the server will update your session and filter events accordingly.
- **If you receive a new session UUID,** reconnect your SSE stream using the new UUID.

---

### **Typical Usage in a React Hook**

```js
const addTripIds = async (tripIds) => {
  const response = await fetch(`/events/${sessionUUID}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'add', trip_ids: tripIds })
  });
  const data = await response.json();
  if (data.session_uuid && data.session_uuid !== sessionUUID) {
    setSessionUUID(data.session_uuid);
    connectSSE(data.session_uuid);
  }
};

const removeTripIds = async (tripIds) => {
  const response = await fetch(`/events/${sessionUUID}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'remove', trip_ids: tripIds })
  });
  const data = await response.json();
  if (data.session_uuid && data.session_uuid !== sessionUUID) {
    setSessionUUID(data.session_uuid);
    connectSSE(data.session_uuid);
  }
};
```

**This allows your frontend to dynamically monitor or stop monitoring any set of trips in real time, with robust session management!**

## Event Filtering

### **Session-Based Filtering**
- ✅ **Only relevant events** - Clients only receive events for trips in their session
- ✅ **Automatic filtering** - Server filters events based on session trip IDs
- ✅ **Efficient delivery** - No unnecessary events sent to clients

### **Event Delivery Statistics**
Server logs show delivery statistics:
```
[SSE] 📊 Event 'seats_reduced' for trip 10 - Total clients: 2, Delivered: 1, Filtered: 1, Failed: 0
```

## Error Handling

### **Connection Errors**
```javascript
eventSource.onerror = (error) => {
  console.error('SSE connection failed:', error);
  // Reconnect logic
  setTimeout(() => {
    connectSSE();
  }, 5000);
};
```

### **Session Expiration**
```javascript
eventSource.addEventListener('error', (event) => {
  if (eventSource.readyState === EventSource.CLOSED) {
    console.log('Session expired, re-fetching trips...');
    fetchTrips().then(data => {
      connectSSE(data.sse_uuid);
    });
  }
});
```

## Performance Considerations

### **Event Frequency**
- ✅ **Real-time updates** - Events sent immediately when trips change
- ✅ **Efficient filtering** - Only relevant events sent to each client
- ✅ **Connection pooling** - Multiple clients can share same session

### **Memory Management**
- ✅ **Automatic cleanup** - Expired sessions removed every 5 minutes
- ✅ **Connection limits** - Server handles multiple concurrent connections
- ✅ **Event buffering** - Events buffered if client temporarily disconnected 

## API Endpoints

### **Fetch Trips with Session**
```
GET /trips?limit=10&offset=0
GET /trips?limit=10&offset=10&session_uuid={uuid}
```

### **Session UUID Behavior**
- ✅ **New sessions**: `sse_uuid` returned in response
- ✅ **Existing sessions**: No `sse_uuid` returned (session continues)
- ✅ **Expired sessions**: New session created, `sse_uuid` returned
- ✅ **No session**: New session created, `sse_uuid` returned

### **SSE Connection**
```
GET /events/{uuid}
``` 