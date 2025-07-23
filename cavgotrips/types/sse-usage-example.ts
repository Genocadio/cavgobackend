// Simple usage example for SSE TypeScript types
import { 
  TripEventMessage, 
  Trip, 
  SSEConnection,
  isSeatsReducedEvent,
  isSeatsRestoredEvent
} from './sse-types';

// Example 1: Basic SSE connection
export class TripEventManager {
  private sse: SSEConnection;

  constructor() {
    this.sse = new SSEConnection('/events/trips');
  }

  subscribeToTrips(tripIds: number[]): void {
    this.sse.connect(tripIds);
    
    this.sse.onOpen(() => {
      console.log(`Connected to SSE for ${tripIds.length} trips`);
    });

    this.sse.onMessage((event: TripEventMessage) => {
      this.handleTripEvent(event);
    });

    this.sse.onError(() => {
      console.error('SSE connection error');
    });
  }

  private handleTripEvent(event: TripEventMessage): void {
    const { event: eventType, data: trip } = event;
    
    switch (eventType) {
      case 'seats_reduced':
        this.updateTripSeats(trip.id, trip.seats);
        break;
      case 'seats_restored':
        this.updateTripSeats(trip.id, trip.seats);
        break;
      case 'updated':
        this.updateTrip(trip);
        break;
      case 'created':
        this.addTrip(trip);
        break;
      case 'started':
        this.startTrip(trip);
        break;
      case 'completed':
        this.completeTrip(trip);
        break;
    }
  }

  private updateTripSeats(tripId: number, seats: number): void {
    console.log(`Trip ${tripId} seats updated to: ${seats}`);
    // Update UI logic here
  }

  private updateTrip(trip: Trip): void {
    console.log(`Trip ${trip.id} updated`);
    // Update UI logic here
  }

  private addTrip(trip: Trip): void {
    console.log(`New trip created: ${trip.id}`);
    // Add to UI logic here
  }

  private startTrip(trip: Trip): void {
    console.log(`Trip ${trip.id} started`);
    // Update UI logic here
  }

  private completeTrip(trip: Trip): void {
    console.log(`Trip ${trip.id} completed`);
    // Update UI logic here
  }

  disconnect(): void {
    this.sse.disconnect();
  }
}

// Example 2: Type-safe event handling with type guards
export function handleTripEvent(event: TripEventMessage): void {
  if (isSeatsReducedEvent(event)) {
    console.log(`Seats reduced for trip ${event.data.id}: ${event.data.seats} available`);
    // Handle seats reduced
  } else if (isSeatsRestoredEvent(event)) {
    console.log(`Seats restored for trip ${event.data.id}: ${event.data.seats} available`);
    // Handle seats restored
  } else {
    console.log(`Trip ${event.data.id} event: ${event.event}`);
    // Handle other events
  }
}

// Example 3: Event type mapping
export const EVENT_TYPE_LABELS: Record<string, string> = {
  created: 'Trip Created',
  updated: 'Trip Updated',
  started: 'Trip Started',
  completed: 'Trip Completed',
  seats_reduced: 'Seats Reduced',
  seats_restored: 'Seats Restored'
};

// Example 4: Status color mapping
export const STATUS_COLORS: Record<string, string> = {
  SCHEDULED: '#ffc107',
  IN_PROGRESS: '#17a2b8',
  COMPLETED: '#28a745',
  NOT_COMPLETED: '#dc3545'
};

// Example 5: Utility function
export function formatTripEvent(event: TripEventMessage): string {
  const { event: eventType, data: trip } = event;
  const label = EVENT_TYPE_LABELS[eventType] || eventType;
  
  switch (eventType) {
    case 'seats_reduced':
    case 'seats_restored':
      return `${label}: Trip ${trip.id} - ${trip.seats} seats available`;
    case 'created':
      return `${label}: Trip ${trip.id} - ${trip.vehicle.license_plate}`;
    case 'updated':
      return `${label}: Trip ${trip.id} - Status: ${trip.status}`;
    default:
      return `${label}: Trip ${trip.id}`;
  }
}

// Example 6: API response types
export interface SSEStatusResponse {
  connected_clients: number;
  status: 'active' | 'inactive';
  client_filters?: Record<string, any>;
}

// Example 7: Error handling
export interface SSEError {
  message: string;
  code?: string;
  timestamp: string;
}

export function handleSSEError(error: Event): SSEError {
  return {
    message: 'SSE connection error',
    code: 'SSE_ERROR',
    timestamp: new Date().toISOString()
  };
} 