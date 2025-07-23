// TypeScript types for Server-Sent Events (SSE)

// Trip event types
export type TripEventType = 
  | 'created' 
  | 'updated' 
  | 'started' 
  | 'completed' 
  | 'seats_reduced' 
  | 'seats_restored';

// Trip status types
export type TripStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'NOT_COMPLETED';

// Connection mode types
export type ConnectionMode = 'ONLINE' | 'OFFLINE' | 'HYBRID';

// Vehicle data structure
export interface Vehicle {
  id: number;
  company_id: number;
  company_name: string;
  capacity: number;
  license_plate: string;
  driver: DriverSnapshot;
}

// Driver snapshot
export interface DriverSnapshot {
  name: string;
  phone: string;
}

// Location data structure
export interface Location {
  id: number;
  latitude: number;
  longitude: number;
  code?: string;
  google_place_name?: string;
  custom_name?: string;
  place_id?: string;
  created_at: string;
  updated_at: string;
}

// Route data structure
export interface Route {
  id: number;
  name?: string;
  distance_meters?: number;
  estimated_duration_seconds?: number;
  google_route_id?: string;
  origin_id: number;
  destination_id: number;
  route_price: number;
  city_route: boolean;
  created_at: string;
  updated_at: string;
  origin: Location;
  destination: Location;
  waypoints: RouteWaypoint[];
}

// Route waypoint
export interface RouteWaypoint {
  id: number;
  route_id: number;
  location_id: number;
  order: number;
  price: number;
  created_at: string;
  updated_at: string;
  location: Location;
}

// Trip waypoint
export interface TripWaypoint {
  id: number;
  trip_id: number;
  location_id: number;
  order: number;
  price?: number;
  is_passed: boolean;
  is_next: boolean;
  passed_timestamp?: number;
  remaining_time?: number;
  remaining_distance?: number;
  is_custom: boolean;
  created_at: string;
  updated_at: string;
  location: Location;
}

// Trip data structure
export interface Trip {
  id: number;
  route_id: number;
  vehicle_id: number;
  vehicle: Vehicle;
  status: TripStatus;
  departure_time: number;
  completion_time?: number;
  connection_mode: ConnectionMode;
  notes?: string;
  seats: number;
  remaining_time_to_destination?: number;
  remaining_distance_to_destination?: number;
  is_reversed: boolean;
  current_speed?: number;
  current_latitude?: number;
  current_longitude?: number;
  has_custom_waypoints: boolean;
  created_at: string;
  updated_at: string;
  route: Route;
  waypoints: TripWaypoint[];
}

// Trip event message structure
export interface TripEventMessage {
  event: TripEventType;
  data: Trip;
}

// Base SSE message structure
export interface SSEMessage {
  type: 'connected' | 'heartbeat' | 'trip_event';
  message?: string;
  timestamp?: string;
  filters?: string;
}

// SSE client status
export interface SSEClientStatus {
  connected_clients: number;
  status: 'active' | 'inactive';
  client_filters?: Record<string, any>;
}

// SSE event handler types
export type SSEEventHandler = (event: TripEventMessage) => void;
export type SSEConnectionHandler = (event: Event) => void;
export type SSEErrorHandler = (event: Event) => void;

// SSE connection class
export class SSEConnection {
  private eventSource: EventSource | null = null;
  private tripIds: number[] = [];

  constructor(private url: string) {}

  connect(tripIds?: number[]): void {
    this.disconnect();
    
    this.tripIds = tripIds || [];
    let fullUrl = this.url;
    
    if (this.tripIds.length > 0) {
      const tripIdsParam = this.tripIds.join(',');
      fullUrl += `?trip_ids=${tripIdsParam}`;
    }

    this.eventSource = new EventSource(fullUrl);
  }

  onOpen(handler: SSEConnectionHandler): void {
    if (this.eventSource) {
      this.eventSource.onopen = handler;
    }
  }

  onMessage(handler: SSEEventHandler): void {
    if (this.eventSource) {
      this.eventSource.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.event && data.data) {
            handler(data as TripEventMessage);
          }
        } catch (error) {
          console.error('Error parsing SSE message:', error);
        }
      };
    }
  }

  onError(handler: SSEErrorHandler): void {
    if (this.eventSource) {
      this.eventSource.onerror = handler;
    }
  }

  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  getTripIds(): number[] {
    return this.tripIds;
  }
}

// Utility types for specific event handling
export type TripCreatedEvent = TripEventMessage & { event: 'created' };
export type TripUpdatedEvent = TripEventMessage & { event: 'updated' };
export type TripStartedEvent = TripEventMessage & { event: 'started' };
export type TripCompletedEvent = TripEventMessage & { event: 'completed' };
export type SeatsReducedEvent = TripEventMessage & { event: 'seats_reduced' };
export type SeatsRestoredEvent = TripEventMessage & { event: 'seats_restored' };

// Type guards for specific events
export function isTripCreatedEvent(event: TripEventMessage): event is TripCreatedEvent {
  return event.event === 'created';
}

export function isTripUpdatedEvent(event: TripEventMessage): event is TripUpdatedEvent {
  return event.event === 'updated';
}

export function isSeatsReducedEvent(event: TripEventMessage): event is SeatsReducedEvent {
  return event.event === 'seats_reduced';
}

export function isSeatsRestoredEvent(event: TripEventMessage): event is SeatsRestoredEvent {
  return event.event === 'seats_restored';
}

// Example usage types
export interface TripListProps {
  displayedTripIds: number[];
  onTripUpdate?: (trip: Trip, eventType: TripEventType) => void;
}

export interface TripCardProps {
  trip: Trip;
  onSeatChange?: (tripId: number, newSeats: number) => void;
} 