import { ApiBooking } from './bookingApiClient';
import { ApiTrip } from './tripApiClient';
import { pool } from '../db/connection';
import { logger } from '../utils/logger';

export interface GraphQLBooking {
  id: string;
  tripId: string;
  carId: string;
  driverId: string | null;
  customerId: string;
  customerName: string;
  phoneNumber: string;
  email: string;
  pickupLocation: {
    latitude: number;
    longitude: number;
    address: string | null;
    timestamp: string;
    bearing: number | null;
    speed: number | null;
  };
  dropoffLocation: {
    latitude: number;
    longitude: number;
    address: string | null;
    timestamp: string;
    bearing: number | null;
    speed: number | null;
  };
  numberOfTickets: number;
  fare: number;
  status: 'PENDING_PAYMENT' | 'PAID' | 'BOARDED' | 'CANCELLED' | 'EXPIRED';
  paymentMethod: 'MOMO' | 'CASH' | 'CARD' | 'TAP_TO_PAY' | null;
  scheduledTime: string;
  createdAt: string;
}

/**
 * Map API booking status to GraphQL BookingStatus
 */
function mapBookingStatus(apiStatus: string): 'PENDING_PAYMENT' | 'PAID' | 'BOARDED' | 'CANCELLED' | 'EXPIRED' {
  switch (apiStatus.toUpperCase()) {
    case 'USED':
      return 'BOARDED';
    case 'CONFIRMED':
      return 'PAID'; // CONFIRMED with payment COMPLETED maps to PAID
    case 'PENDING_PAYMENT':
      return 'PENDING_PAYMENT';
    case 'CANCELLED':
    case 'CANCELED':
      return 'CANCELLED';
    case 'EXPIRED':
      return 'EXPIRED';
    default:
      // If payment status is COMPLETED, consider it PAID
      return 'PAID';
  }
}

/**
 * Map payment method to GraphQL PaymentMethod
 */
function mapPaymentMethod(paymentMethod: string | null | undefined, paymentData: string | null | undefined): 'MOMO' | 'CASH' | 'CARD' | 'TAP_TO_PAY' | null {
  if (!paymentMethod) return null;

  // Check if payment_data contains NFC or payment_method is NFC
  const isNFC = paymentData?.toLowerCase().includes('nfc') || paymentMethod.toLowerCase().includes('nfc');
  if (isNFC) {
    return 'TAP_TO_PAY';
  }

  // Map other payment methods
  const method = paymentMethod.toUpperCase();
  if (method === 'CARD') {
    return 'CARD';
  }
  if (method === 'CASH') {
    return 'CASH';
  }

  // All other cases map to MOMO
  return 'MOMO';
}

/**
 * Get location coordinates from trip route by matching location ID or name
 */
function getLocationCoordinates(
  locationId: string,
  locationName: string | null,
  trip: ApiTrip
): { latitude: number; longitude: number } {
  if (!trip.route) {
    return { latitude: 0, longitude: 0 };
  }

  // Check origin
  if (trip.route.origin) {
    const origin = trip.route.origin as any;
    if (origin.id?.toString() === locationId || 
        origin.custom_name === locationName || 
        origin.google_place_name === locationName ||
        origin.place_name === locationName) {
      return {
        latitude: origin.latitude || 0,
        longitude: origin.longitude || 0,
      };
    }
  }

  // Check destination
  if (trip.route.destination) {
    const destination = trip.route.destination as any;
    if (destination.id?.toString() === locationId ||
        destination.custom_name === locationName ||
        destination.google_place_name === locationName ||
        destination.place_name === locationName) {
      return {
        latitude: destination.latitude || 0,
        longitude: destination.longitude || 0,
      };
    }
  }

  // Check waypoints - waypoints have location nested or direct properties
  if (trip.waypoints) {
    for (const waypoint of trip.waypoints) {
      const waypointLocation = (waypoint as any).location || waypoint;
      if (waypointLocation) {
        const location = waypointLocation as any;
        if (location.id?.toString() === locationId ||
            location.custom_name === locationName ||
            location.google_place_name === locationName ||
            location.place_name === locationName) {
          return {
            latitude: location.latitude || 0,
            longitude: location.longitude || 0,
          };
        }
      }
    }
  }

  // Default coordinates if no match
  return { latitude: 0, longitude: 0 };
}

/**
 * Map API booking to GraphQL Booking
 */
export async function mapBookingToGraphQL(
  booking: ApiBooking,
  trip: ApiTrip
): Promise<GraphQLBooking> {
  // Get carId and driverId from database
  const client = await pool.connect();
  let carId = '';
  let driverId: string | null = null;

  try {
    const tripResult = await client.query(
      'SELECT vehicle_id, driver_id FROM trips WHERE id = $1',
      [booking.trip_id.toString()]
    );

    if (tripResult.rows.length > 0) {
      carId = tripResult.rows[0].vehicle_id;
      driverId = tripResult.rows[0].driver_id;
    }
  } catch (error) {
    logger.error('Error getting car/driver for booking', {
      bookingId: booking.id,
      tripId: booking.trip_id,
      error,
    });
  } finally {
    client.release();
  }

  // Determine location names: use ticket names if tickets exist and payment is COMPLETED
  // Otherwise, keep null/empty for bookings without tickets
  let pickupLocationName: string | null = null;
  let dropoffLocationName: string | null = null;
  
  if (booking.payment?.status === 'COMPLETED' && booking.tickets && booking.tickets.length > 0) {
    // For paid bookings with tickets, use ticket location names
    pickupLocationName = booking.tickets[0].pickup_location_name || null;
    dropoffLocationName = booking.tickets[0].dropoff_location_name || null;
  }
  // For bookings without tickets or unpaid, keep location names as null/empty

  // Get location coordinates from trip route (always try to get coordinates even if name is null)
  const pickupCoords = getLocationCoordinates(
    booking.pickup_location_id,
    pickupLocationName,
    trip
  );
  const dropoffCoords = getLocationCoordinates(
    booking.dropoff_location_id,
    dropoffLocationName,
    trip
  );

  // Map status - if payment status is COMPLETED, use PAID, otherwise map from booking status
  let bookingStatus: 'PENDING_PAYMENT' | 'PAID' | 'BOARDED' | 'CANCELLED' | 'EXPIRED';
  if (booking.payment?.status === 'COMPLETED') {
    bookingStatus = booking.status === 'USED' ? 'BOARDED' : 'PAID';
  } else {
    bookingStatus = mapBookingStatus(booking.status);
  }

  // Map payment method
  const paymentMethod = mapPaymentMethod(
    booking.payment?.payment_method,
    booking.payment?.payment_data
  );

  // Use trip departure_time or booking created_at for scheduledTime
  const scheduledTime = trip.departure_time
    ? new Date(trip.departure_time * 1000).toISOString()
    : booking.created_at;

  return {
    id: booking.id,
    tripId: booking.trip_id.toString(),
    carId,
    driverId,
    customerId: booking.user_phone, // Use phone as customer ID
    customerName: booking.user_name,
    phoneNumber: booking.user_phone,
    email: booking.user_email || '', // Use email from API if available
    pickupLocation: {
      latitude: pickupCoords.latitude,
      longitude: pickupCoords.longitude,
      address: pickupLocationName, // Use ticket location name or null
      timestamp: booking.created_at,
      bearing: null,
      speed: null,
    },
    dropoffLocation: {
      latitude: dropoffCoords.latitude,
      longitude: dropoffCoords.longitude,
      address: dropoffLocationName, // Use ticket location name or null
      timestamp: booking.created_at,
      bearing: null,
      speed: null,
    },
    numberOfTickets: booking.number_of_tickets,
    fare: booking.total_amount,
    status: bookingStatus,
    paymentMethod,
    scheduledTime,
    createdAt: booking.created_at,
  };
}

