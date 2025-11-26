import dotenv from 'dotenv';
import path from 'path';

const envPath = path.resolve(process.cwd(), '.env');
dotenv.config({ path: envPath });

const TRIP_API_BASE_URL = process.env.TRIP_API_BASE_URL || '';

export interface ApiTripRoute {
  id: number;
  origin: {
    custom_name?: string;
    place_name?: string;
    latitude: number;
    longitude: number;
  };
  destination: {
    custom_name?: string;
    place_name?: string;
    latitude: number;
    longitude: number;
  };
  waypoints?: Array<{
    custom_name?: string;
    place_name?: string;
    latitude: number;
    longitude: number;
    passed?: boolean;
    passed_timestamp?: string;
    remaining_distance?: number;
    fare?: number;
  }>;
}

export interface ApiTripVehicle {
  id: number;
  company_id: number;
  company_name?: string;
  capacity: number;
  license_plate: string;
  driver?: {
    id: number;
    name: string;
    phone: string;
  };
}

export interface ApiTrip {
  id: number;
  route_id: number;
  vehicle_id: number;
  vehicle?: ApiTripVehicle;
  status: 'SCHEDULED' | 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  departure_time: number; // Unix timestamp
  completion_time?: number | null; // Unix timestamp
  connection_mode?: 'ONLINE' | 'OFFLINE';
  notes?: string | null;
  seats: number;
  price: number;
  remaining_time_to_destination?: number;
  remaining_distance_to_destination?: number;
  is_reversed?: boolean;
  current_speed?: number;
  current_latitude?: number;
  current_longitude?: number;
  has_custom_waypoints?: boolean;
  created_at: string; // ISO 8601
  updated_at: string; // ISO 8601
  route?: ApiTripRoute;
  waypoints?: Array<{
    custom_name?: string;
    place_name?: string;
    latitude: number;
    longitude: number;
    passed?: boolean;
    passed_timestamp?: string;
    remaining_distance?: number;
    fare?: number;
  }>;
}

export interface ApiTripsResponse {
  trips: ApiTrip[];
  total: number;
  limit: number;
  offset: number;
}

class TripApiClient {
  private baseUrl: string;
  private enabled: boolean;

  constructor() {
    this.baseUrl = TRIP_API_BASE_URL;
    this.enabled = !!TRIP_API_BASE_URL;
  }

  isEnabled(): boolean {
    return this.enabled;
  }

  private async fetchWithErrorHandling<T>(url: string): Promise<T> {
    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok) {
        if (response.status === 404) {
          return { trips: [], total: 0, limit: 20, offset: 0 } as T;
        }
        throw new Error(`API request failed: ${response.status} ${response.statusText}`);
      }

      return await response.json() as T;
    } catch (error) {
      console.error(`Error fetching from ${url}:`, error);
      throw error;
    }
  }

  async getTripsByCompany(
    companyId: string,
    options?: {
      tripId?: string;
      driverId?: string;
      vehicleId?: string;
      fromDate?: string;
      limit?: number;
      offset?: number;
    }
  ): Promise<ApiTripsResponse> {
    if (!this.enabled) {
      return { trips: [], total: 0, limit: options?.limit || 20, offset: options?.offset || 0 };
    }

    const params = new URLSearchParams();
    if (options?.tripId) params.append('trip_id', options.tripId);
    if (options?.driverId) params.append('driver_id', options.driverId);
    if (options?.vehicleId) params.append('vehicle_id', options.vehicleId);
    if (options?.fromDate) params.append('from_date', options.fromDate);
    if (options?.limit) params.append('limit', options.limit.toString());
    if (options?.offset) params.append('offset', options.offset.toString());

    const url = `${this.baseUrl}/internal/trips/company/${companyId}${params.toString() ? '?' + params.toString() : ''}`;
    return this.fetchWithErrorHandling<ApiTripsResponse>(url);
  }
}

export const tripApiClient = new TripApiClient();



