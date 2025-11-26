import dotenv from 'dotenv';
import path from 'path';

const envPath = path.resolve(process.cwd(), '.env');
dotenv.config({ path: envPath });

const BOOKING_API_BASE_URL = process.env.BOOKING_API_BASE_URL || '';

export interface ApiBookingTicket {
  id: string;
  booking_id: string;
  ticket_number: string;
  qr_code: string;
  is_used: boolean;
  created_at: string;
  updated_at: string;
  pickup_location_name: string;
  dropoff_location_name: string;
  car_plate: string;
  car_company: string;
  pickup_time: string;
}

export interface ApiBookingPayment {
  id: string;
  booking_id: string;
  amount: number;
  payment_method: string;
  status: string;
  transaction_id: string;
  payment_data: string; // JSON string
  created_at: string;
  updated_at: string;
}

export interface ApiBooking {
  id: string;
  trip_id: number;
  user_id?: string;
  user_email?: string;
  user_phone: string;
  user_name: string;
  pickup_location_id: string;
  dropoff_location_id: string;
  pickup_location_name?: string; // From database or tickets
  dropoff_location_name?: string; // From database or tickets
  number_of_tickets: number;
  total_amount: number;
  status: string; // "CONFIRMED", "USED", "PENDING_PAYMENT", "CANCELLED", "EXPIRED", etc.
  booking_reference: string;
  created_at: string;
  updated_at: string;
  tickets?: ApiBookingTicket[];
  payment?: ApiBookingPayment;
}

class BookingApiClient {
  private baseUrl: string;
  private enabled: boolean;

  constructor() {
    this.baseUrl = BOOKING_API_BASE_URL;
    this.enabled = !!BOOKING_API_BASE_URL;
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
          return [] as T;
        }
        throw new Error(`API request failed: ${response.status} ${response.statusText}`);
      }

      return await response.json() as T;
    } catch (error) {
      console.error(`Error fetching from ${url}:`, error);
      throw error;
    }
  }

  async getBookingsByTrip(tripId: string): Promise<ApiBooking[]> {
    if (!this.enabled) {
      return [];
    }

    const url = `${this.baseUrl}/bookings/trip/${tripId}`;
    return this.fetchWithErrorHandling<ApiBooking[]>(url);
  }
}

export const bookingApiClient = new BookingApiClient();

