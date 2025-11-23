import { ApiTrip } from './tripApiClient';
import { apiClient } from './apiClient';
import { mapVehicleToCar } from './dataMapper';
import { mapWorkerToDriver } from './dataMapper';
import { isDriverless } from './dataMapper';
import { pool } from '../db/connection';
import { logger } from '../utils/logger';

export interface GraphQLTrip {
  id: string;
  vehicle_id: string; // Used by GraphQL resolver
  driver_id: string;  // Used by GraphQL resolver
  vehicle?: any; // Original vehicle object from ApiTrip (for resolver)
  companyId?: string; // Company ID (for resolver)
  car: any; // Will be resolved by GraphQL resolver
  driver: any; // Will be resolved by GraphQL resolver
  startTime: string;
  endTime: string | null;
  origin: {
    placename: string;
    latitude: number;
    longitude: number;
    passed: boolean;
    passedTimestamp: string | null;
    remainingDistance: number | null;
    fare: number | null;
  };
  destination: {
    placename: string;
    latitude: number;
    longitude: number;
    passed: boolean;
    passedTimestamp: string | null;
    remainingDistance: number | null;
    fare: number | null;
  };
  waypoints: Array<{
    placename: string;
    latitude: number;
    longitude: number;
    passed: boolean;
    passedTimestamp: number | null; // Unix timestamp (number) for waypoints
    remainingDistance: number | null;
    fare: number | null;
  }>;
  currentLocation: {
    latitude: number;
    longitude: number;
    address: string | null;
    timestamp: string;
    bearing: number | null;
    speed: number | null;
  } | null;
  distance: number | null;
  status: 'SCHEDULED' | 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  departureTime: string | null;
  remainingSeats: number;
  bookings: any[]; // Will be resolved by GraphQL resolver
  totalRevenue: number;
}

/**
 * Maps API trip status to GraphQL TripStatus
 */
function mapTripStatus(
  apiStatus: 'SCHEDULED' | 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
): 'SCHEDULED' | 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' {
  return apiStatus;
}

/**
 * Converts Unix timestamp to ISO string
 */
function timestampToISO(timestamp: number | null | undefined): string | null {
  if (timestamp == null || timestamp === 0) return null;
  return new Date(timestamp * 1000).toISOString();
}

/**
 * Gets location name, preferring custom_name over place_name/google_place_name
 * Handles both direct location objects and nested location structures
 */
function getLocationName(location: { 
  custom_name?: string; 
  place_name?: string;
  google_place_name?: string;
  location?: {
    custom_name?: string;
    google_place_name?: string;
  };
}): string {
  // If location has a nested location object (from waypoint structure)
  if (location.location) {
    return location.location.custom_name || location.location.google_place_name || 'Unknown Location';
  }
  // Direct location object
  return location.custom_name || location.google_place_name || location.place_name || 'Unknown Location';
}

/**
 * Maps API trip waypoint to GraphQL TripLocation
 * Waypoint structure: { location: { latitude, longitude, custom_name, google_place_name }, is_passed, passed_timestamp, remaining_distance, price }
 */
function mapWaypoint(waypoint: any): {
  placename: string;
  latitude: number;
  longitude: number;
  passed: boolean;
  passedTimestamp: number | null;
  remainingDistance: number | null;
  fare: number | null;
} {
  // Use waypoint.location for location data
  const location = waypoint.location || waypoint;
  
  // Get coordinates from location object
  const latitude = location.latitude != null ? location.latitude : 0;
  const longitude = location.longitude != null ? location.longitude : 0;
  
  // Get placename, preferring custom_name over google_place_name
  const placename = location.custom_name || location.google_place_name || 'Unknown Location';
  
  return {
    placename,
    latitude,
    longitude,
    passed: waypoint.is_passed || false,
    passedTimestamp: waypoint.passed_timestamp != null ? waypoint.passed_timestamp : null, // Keep as Unix timestamp (number)
    remainingDistance: waypoint.remaining_distance != null ? waypoint.remaining_distance : null,
    fare: waypoint.price != null ? waypoint.price : null, // Use price field
  };
}

/**
 * Maps API trip to GraphQL Trip
 * Note: car and driver will be resolved by GraphQL resolvers
 */
export async function mapTripToGraphQL(trip: ApiTrip): Promise<GraphQLTrip> {
  logger.debug('Mapping trip to GraphQL', {
    tripId: trip.id,
    vehicleId: trip.vehicle_id,
    status: trip.status,
    driverId: trip.vehicle?.driver?.id,
    driverName: trip.vehicle?.driver?.name,
    isDriverless: trip.vehicle?.driver ? isDriverless(trip.vehicle.driver) : true,
    hasRoute: !!trip.route,
    hasOrigin: !!trip.route?.origin,
    hasDestination: !!trip.route?.destination,
  });
  
  // Ensure origin and destination have valid coordinates (required by GraphQL)
  // Origin and destination MUST come from trip.route.origin and trip.route.destination
  const origin = trip.route?.origin || { latitude: 0, longitude: 0, custom_name: undefined, google_place_name: undefined };
  const destination = trip.route?.destination || { latitude: 0, longitude: 0, custom_name: undefined, google_place_name: undefined };
  
  // Validate coordinates are numbers (not null/undefined/NaN)
  if (typeof origin.latitude !== 'number' || isNaN(origin.latitude)) origin.latitude = 0;
  if (typeof origin.longitude !== 'number' || isNaN(origin.longitude)) origin.longitude = 0;
  if (typeof destination.latitude !== 'number' || isNaN(destination.latitude)) destination.latitude = 0;
  if (typeof destination.longitude !== 'number' || isNaN(destination.longitude)) destination.longitude = 0;
  
  // Log if origin or destination is missing from route
  if (!trip.route?.origin) {
    logger.warn('Trip missing origin in route', {
      tripId: trip.id,
      hasRoute: !!trip.route,
    });
  }
  if (!trip.route?.destination) {
    logger.warn('Trip missing destination in route', {
      tripId: trip.id,
      hasRoute: !!trip.route,
    });
  }

  // Map waypoints - use trip.waypoints array, sort by order field, then map
  const rawWaypoints = trip.waypoints || [];
  const waypoints = rawWaypoints
    .filter((wp: any) => wp != null && wp.location != null) // Filter out null/undefined waypoints and those without location
    .sort((a: any, b: any) => (a.order || 0) - (b.order || 0)) // Sort by order field
    .map(mapWaypoint);

  // Map current location if available
  // Note: Reverse geocoding disabled for performance - address will be empty string (handled by frontend)
  // Note: It's normal for trips to not have current location (null is valid)
  let currentLocation: GraphQLTrip['currentLocation'] = null;
  if (trip.current_latitude !== undefined && trip.current_longitude !== undefined) {
    // Validate coordinates are valid numbers (not null, undefined, or NaN)
    const lat = trip.current_latitude;
    const lng = trip.current_longitude;
    
    if (typeof lat === 'number' && !isNaN(lat) && typeof lng === 'number' && !isNaN(lng)) {
      // Reverse geocoding disabled - return empty string for address (frontend will handle it)
      currentLocation = {
        latitude: lat,
        longitude: lng,
        address: '', // Empty string - reverse geocoding disabled for performance
        timestamp: trip.updated_at || new Date().toISOString(),
        bearing: null,
        speed: trip.current_speed || null,
      };
    }
    // If coordinates are invalid (null, undefined, NaN), currentLocation remains null
    // This is expected behavior - no logging needed
  }

  // Determine if origin/destination are passed
  // Live trips (SCHEDULED, IN_PROGRESS): origin not passed, destination not passed
  // Completed trips (COMPLETED, CANCELLED, STARTED, etc): origin passed, destination passed if COMPLETED
  const originPassed = trip.status !== 'SCHEDULED' && trip.status !== 'IN_PROGRESS';
  const destinationPassed = trip.status === 'COMPLETED';

  // Get driver_id from trip.vehicle.driver or from database
  let driverId = '';
  
  // Check if this is a driverless trip (name is empty/null)
  if (trip.vehicle?.driver && isDriverless(trip.vehicle.driver)) {
    // Trip is driverless - set driver_id to empty string
    logger.debug('Trip is driverless', {
      tripId: trip.id.toString(),
      driverId: trip.vehicle.driver.id,
      driverName: trip.vehicle.driver.name,
    });
    driverId = '';
  } else if (trip.vehicle?.driver?.id) {
    driverId = trip.vehicle.driver.id.toString();
  }
  
  // If no driver_id from trip, try to get from database
  if (!driverId) {
    try {
      const client = await pool.connect();
      try {
        const result = await client.query(
          'SELECT driver_id FROM trips WHERE id = $1',
          [trip.id.toString()]
        );
        driverId = result.rows.length > 0 ? result.rows[0].driver_id : '';
      } finally {
        client.release();
      }
    } catch (error) {
      logger.debug('Error getting driver_id from database:', error);
    }
  }

  // Build origin TripLocation - ALWAYS create this object (required by GraphQL)
  const originLocation = {
    placename: getLocationName(origin) || 'Unknown Origin',
    latitude: origin.latitude != null ? origin.latitude : 0,
    longitude: origin.longitude != null ? origin.longitude : 0,
    passed: originPassed,
    passedTimestamp: originPassed ? timestampToISO(trip.departure_time) : null,
    remainingDistance: trip.remaining_distance_to_destination != null ? trip.remaining_distance_to_destination / 1000 : null, // Convert meters to kilometers
    fare: null,
  };
  
  // Build destination TripLocation - ALWAYS create this object (required by GraphQL)
  const destinationLocation = {
    placename: getLocationName(destination) || 'Unknown Destination',
    latitude: destination.latitude != null ? destination.latitude : 0,
    longitude: destination.longitude != null ? destination.longitude : 0,
    passed: destinationPassed,
    passedTimestamp: destinationPassed ? timestampToISO(trip.completion_time) : null,
    remainingDistance: destinationPassed ? null : (trip.remaining_distance_to_destination != null ? trip.remaining_distance_to_destination / 1000 : null), // Convert meters to kilometers
    fare: trip.price != null ? trip.price : null,
  };
  
  const mappedTrip = {
    id: trip.id.toString(),
    vehicle_id: trip.vehicle_id.toString(), // Preserve for resolver
    driver_id: driverId, // Preserve for resolver
    vehicle: trip.vehicle, // Preserve original vehicle object for resolver
    companyId: trip.vehicle?.company_id?.toString() || '', // Preserve companyId for resolver
    car: null, // Will be resolved by GraphQL resolver
    driver: null, // Will be resolved by GraphQL resolver
    startTime: timestampToISO(trip.departure_time) || new Date().toISOString(),
    endTime: timestampToISO(trip.completion_time),
    origin: originLocation, // ALWAYS present (required by GraphQL schema)
    destination: destinationLocation, // ALWAYS present (required by GraphQL schema)
    waypoints,
    currentLocation,
    distance: (trip.route as any)?.distance_meters != null ? (trip.route as any).distance_meters / 1000 : null, // Convert meters to kilometers
    status: mapTripStatus(trip.status),
    departureTime: timestampToISO(trip.departure_time),
    remainingSeats: trip.seats || 0,
    bookings: [], // No booking data available
    totalRevenue: trip.price || 0,
  };
  
  logger.debug('Trip mapped successfully', {
    tripId: mappedTrip.id,
    vehicleId: mappedTrip.vehicle_id,
    driverId: mappedTrip.driver_id || 'driverless',
    status: mappedTrip.status,
    hasOrigin: !!mappedTrip.origin,
    hasDestination: !!mappedTrip.destination,
    originPlacename: mappedTrip.origin.placename,
    destinationPlacename: mappedTrip.destination.placename,
    waypointCount: mappedTrip.waypoints.length,
    distance: mappedTrip.distance,
  });
  
  return mappedTrip;
}

