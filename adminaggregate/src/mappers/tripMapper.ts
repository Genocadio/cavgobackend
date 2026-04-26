import type { RemoteTrip, RemoteLocation, RemoteWaypoint, RemoteRoute, Trip, TripLocation, Destination, TripApiItem, TripApiLocation, TripApiWaypoint, TripApiRoute, TripServiceTrip, TripServiceWaypoint } from "../types";
import * as locationRepository from "../repositories/locations";
import * as assignmentRepository from "../repositories/assignments";

function ensureFiniteNumber(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new Error(`Trip mapper: ${field} must be a finite number`);
  }
  return value;
}

function ensureNonEmptyString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`Trip mapper: ${field} must be a non-empty string`);
  }
  return value;
}

class MissingTripLocationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MissingTripLocationError";
  }
}

async function requireTripLocation(locationId: string, context: string): Promise<TripLocation> {
  const existingLocation = await locationRepository.getTripLocationById(locationId);
  if (!existingLocation) {
    throw new MissingTripLocationError(`Trip mapper: ${context} location ${locationId} is missing from local storage`);
  }

  return existingLocation;
}

/**
 * Normalize destination indices to ensure:
 * - Ordering respects the incoming waypoint/destination order field when present
 * - No duplicates, and indices are sequential 0..n after sorting
 * - Route destination (if no order) is pushed to the end via a large sort key
 */
function normalizeDestinationIndices(destinations: Destination[]): Destination[] {
  if (destinations.length === 0) return destinations;

  // Separate waypoints (have order) from route destination (order: null)
  const waypoints = destinations.filter(d => d.order !== null);
  const routeDestination = destinations.find(d => d.order === null);

  // Sort waypoints by order
  const sortedWaypoints = waypoints.sort((a, b) => {
    const orderA = a.order ?? 0;
    const orderB = b.order ?? 0;
    return orderA - orderB;
  });

  // Build final array: waypoints first, then route destination (if exists)
  const finalDestinations = [...sortedWaypoints];
  if (routeDestination) {
    finalDestinations.push(routeDestination);
  }

  // Assign sequential indices
  return finalDestinations.map((dest, i) => ({
    ...dest,
    index: i,
  }));
}

function mapRemoteStatusToLocal(remoteStatus: string | null): "scheduled" | "in_progress" | "completed" | "cancelled" {
  if (!remoteStatus) {
    throw new Error("Trip mapper: remote status is required");
  }
  
  const status = remoteStatus.toUpperCase();
  switch (status) {
    case "SCHEDULED":
      return "scheduled";
    case "IN_PROGRESS":
      return "in_progress";
    case "COMPLETED":
      return "completed";
    case "CANCELLED":
    case "NOT_COMPLETED":
      return "cancelled";
    default:
      throw new Error(`Trip mapper: unsupported remote status '${remoteStatus}'`);
  }
}

function mapRemoteLocationToTripLocation(remoteLocation: RemoteLocation | null): TripLocation {
  if (!remoteLocation) {
    throw new Error("Trip mapper: remote location is required");
  }

  const address = remoteLocation.custom_name || remoteLocation.google_place_name;
  if (!address) {
    throw new Error(`Trip mapper: remote location ${remoteLocation.id} is missing address fields`);
  }

  return {
    id: ensureNonEmptyString(String(remoteLocation.id), "remoteLocation.id"),
    lat: ensureFiniteNumber(remoteLocation.latitude, "remoteLocation.latitude"),
    lng: ensureFiniteNumber(remoteLocation.longitude, "remoteLocation.longitude"),
    addres: address,
  };
}

async function mapRemoteWaypointToDestination(
  waypoint: RemoteWaypoint,
  index: number
): Promise<Destination> {
  if (!waypoint.location) {
    throw new Error(`Trip mapper: waypoint ${waypoint.id} is missing location`);
  }
  
  const location = await requireTripLocation(String(waypoint.location.id), `remote waypoint ${waypoint.id}`);
  
  return {
    id: ensureNonEmptyString(String(waypoint.location.id), "remote waypoint.location.id"), // Location ID for destination identity
    locationId: location.id, // Reference to trip_locations table
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    index: waypoint.order ?? index,
    fare: waypoint.price ?? 0,
    remainingDistance: waypoint.remaining_distance ?? null,
    isPassede: waypoint.is_passed ?? false,
    passedTime: waypoint.passed_timestamp ?? null,
  };
}

async function mapRouteDestinationToDestination(
  route: RemoteRoute,
  waypointCount: number
): Promise<Destination> {
  if (!route.destination) {
    throw new Error(`Trip mapper: route ${route.id} is missing destination`);
  }
  
  const location = await requireTripLocation(String(route.destination.id), `remote route ${route.id} destination`);
  
  return {
    id: String(route.destination.id), // For final destination, id = locationId (no separate waypoint)
    locationId: String(route.destination.id), // Same as id for non-waypoint destinations
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    order: null, // Final destination has no meaningful order value; index is assigned by normalizeDestinationIndices
    index: waypointCount, // Will be normalized
    fare: route.route_price ?? 0,
    remainingDistance: null, // Final destination has no remaining distance
    isPassede: false,
    passedTime: null,
  };
}

/**
 * Validate that all required trip locations are present and valid
 * Throws error if any critical location is missing or invalid
 */
function validateTripLocations(
  origin: TripLocation,
  destinations: Destination[],
  routeDestinationId: string | undefined,
  context: string
): void {
  if (!origin) {
    throw new Error(`Trip validation failed for ${context}: origin is required`);
  }
  
  if (!origin.id || !Number.isFinite(origin.lat) || !Number.isFinite(origin.lng) || !origin.addres?.trim()) {
    throw new Error(`Trip validation failed for ${context}: origin has invalid data (id: ${origin.id}, lat: ${origin.lat}, lng: ${origin.lng}, address: "${origin.addres}")`);
  }
  
  if (!destinations || destinations.length === 0) {
    throw new Error(`Trip validation failed for ${context}: at least one destination is required`);
  }
  
  // Count waypoints vs route destinations
  const waypointCount = destinations.filter(d => d.order !== null).length;
  const routeDestinationCount = destinations.filter(d => d.order === null).length;
  
  // STRICT VALIDATION: Must have exactly 1 route destination
  if (routeDestinationCount !== 1) {
    console.error(JSON.stringify({
      level: "ERROR",
      event: "ROUTE_DESTINATION_COUNT_INVALID",
      context,
      routeDestinationCount,
      expectedCount: 1,
      destinations: destinations.map(d => ({
        id: d.id,
        locationId: d.locationId,
        index: d.index,
        order: d.order,
        addres: d.addres,
      })),
    }));
    throw new Error(`Trip validation failed for ${context}: must have exactly 1 route destination, found ${routeDestinationCount}`);
  }
  
  // STRICT VALIDATION: Total destinations must be waypoints + 1
  const expectedTotal = waypointCount + 1;
  if (destinations.length !== expectedTotal) {
    console.error(JSON.stringify({
      level: "ERROR",
      event: "DESTINATION_COUNT_MISMATCH",
      context,
      waypointCount,
      routeDestinationCount,
      actualTotal: destinations.length,
      expectedTotal,
      destinations: destinations.map(d => ({
        id: d.id,
        locationId: d.locationId,
        index: d.index,
        order: d.order,
        addres: d.addres,
      })),
    }));
    throw new Error(`Trip validation failed for ${context}: destination count mismatch. Expected ${expectedTotal} (${waypointCount} waypoints + 1 route), got ${destinations.length}`);
  }
  
  // Validate each destination
  for (let i = 0; i < destinations.length; i++) {
    const dest = destinations[i];
    if (!dest || !dest.id || !Number.isFinite(dest.lat) || !Number.isFinite(dest.lng) || !dest.addres?.trim()) {
      throw new Error(`Trip validation failed for ${context}: destination at index ${i} has invalid data (id: ${dest?.id}, lat: ${dest?.lat}, lng: ${dest?.lng}, address: "${dest?.addres}")`);
    }
  }
  
  // Verify last destination matches route destination if provided
  if (routeDestinationId) {
    const lastDest = destinations[destinations.length - 1];
    if (!lastDest || (lastDest.id !== routeDestinationId && lastDest.locationId !== routeDestinationId)) {
      console.error(JSON.stringify({
        level: "ERROR",
        event: "ROUTE_DESTINATION_MISMATCH",
        context,
        routeDestinationId,
        lastDestinationId: lastDest?.id,
        lastDestinationLocationId: lastDest?.locationId,
        allDestinations: destinations.map(d => ({
          id: d.id,
          locationId: d.locationId,
          index: d.index,
          addres: d.addres,
        })),
      }));
      throw new Error(`Trip validation failed for ${context}: last destination (${lastDest?.id}) does not match route destination (${routeDestinationId})`);
    }
  }
  
  // STRICT VALIDATION: Verify waypoint ordering is sequential starting from 0
  for (let i = 0; i < destinations.length; i++) {
    const dest = destinations[i];
    if (!dest || dest.index !== i) {
      console.error(JSON.stringify({
        level: "ERROR",
        event: "DESTINATION_INDEX_INVALID",
        context,
        destinationIndex: i,
        actualIndex: dest?.index,
        expectedIndex: i,
        destination: {
          id: dest?.id,
          locationId: dest?.locationId,
          addres: dest?.addres,
          order: dest?.order,
        },
      }));
      throw new Error(`Trip validation failed for ${context}: destination at position ${i} has incorrect index ${dest?.index}, expected ${i}`);
    }
  }
  
  // STRICT VALIDATION: Verify route destination is last
  const routeDest = destinations.find(d => d.order === null);
  const routeDestIndex = destinations.indexOf(routeDest!);
  if (routeDestIndex !== destinations.length - 1) {
    console.error(JSON.stringify({
      level: "ERROR",
      event: "ROUTE_DESTINATION_NOT_LAST",
      context,
      routeDestinationIndex: routeDestIndex,
      totalDestinations: destinations.length,
      routeDestination: {
        id: routeDest?.id,
        locationId: routeDest?.locationId,
        addres: routeDest?.addres,
      },
    }));
    throw new Error(`Trip validation failed for ${context}: route destination must be last (index ${destinations.length - 1}), found at index ${routeDestIndex}`);
  }
}

/**
 * Ensures route destination is included in destinations list
 * If missing, adds it as the final destination
 */
async function ensureRouteDestinationIncluded(
  destinations: Destination[],
  routeDestinationId: string,
  routeDestination: any,
  context: string
): Promise<Destination[]> {
  // Check if route destination is already included
  const routeDestExists = destinations.some(d => 
    d.id === routeDestinationId || d.locationId === routeDestinationId
  );
  
  if (!routeDestExists) {
    console.warn(JSON.stringify({
      level: "WARN",
      event: "ROUTE_DESTINATION_MISSING",
      context,
      routeDestinationId,
      currentDestinations: destinations.map(d => ({
        id: d.id,
        locationId: d.locationId,
        index: d.index,
        addres: d.addres,
      })),
    }));
    
    const routeDestLocation = await requireTripLocation(routeDestinationId, `${context} route.destination`);
    
    const newDest: Destination = {
      id: routeDestinationId,
      locationId: routeDestinationId,
      lat: routeDestLocation.lat,
      lng: routeDestLocation.lng,
      addres: routeDestLocation.addres,
      order: null, // Final destination has no order
      index: destinations.length, // Will be normalized
      fare: 0,
      remainingDistance: null,
      isPassede: false,
      passedTime: null,
    };
    
    destinations.push(newDest);
    console.log(JSON.stringify({
      level: "INFO",
      event: "ROUTE_DESTINATION_ADDED",
      context,
      routeDestinationId,
      newDestination: {
        id: newDest.id,
        index: newDest.index,
        addres: newDest.addres,
      },
    }));
  }
  
  return destinations;
}

export async function mapRemoteTripToLocalTrip(
  remoteTrip: RemoteTrip,
  vehicleId: string,
  driverId: string | null
): Promise<Trip> {
  // Log full received structure
  console.log(JSON.stringify({
    level: "INFO",
    event: "REMOTE_TRIP_MAPPING_START",
    source: "RABBITMQ",
    tripId: remoteTrip.id,
    receivedStructure: {
      id: remoteTrip.id,
      route_id: remoteTrip.route_id,
      vehicle_id: remoteTrip.vehicle_id,
      status: remoteTrip.status,
      created_at: remoteTrip.created_at,
      updated_at: remoteTrip.updated_at,
      route: remoteTrip.route ? {
        id: remoteTrip.route.id,
        origin_id: remoteTrip.route.origin_id,
        destination_id: remoteTrip.route.destination_id,
        distance_meters: remoteTrip.route.distance_meters,
        origin: remoteTrip.route.origin ? {
          id: remoteTrip.route.origin.id,
          latitude: remoteTrip.route.origin.latitude,
          longitude: remoteTrip.route.origin.longitude,
          custom_name: remoteTrip.route.origin.custom_name,
          google_place_name: remoteTrip.route.origin.google_place_name,
        } : null,
        destination: remoteTrip.route.destination ? {
          id: remoteTrip.route.destination.id,
          latitude: remoteTrip.route.destination.latitude,
          longitude: remoteTrip.route.destination.longitude,
          custom_name: remoteTrip.route.destination.custom_name,
          google_place_name: remoteTrip.route.destination.google_place_name,
        } : null,
      } : null,
      waypoints: remoteTrip.waypoints ? remoteTrip.waypoints.map(wp => ({
        id: wp.id,
        location_id: wp.location_id,
        order: wp.order,
        location: wp.location ? {
          id: wp.location.id,
          latitude: wp.location.latitude,
          longitude: wp.location.longitude,
          custom_name: wp.location.custom_name,
          google_place_name: wp.location.google_place_name,
        } : null,
      })) : null,
    },
    mappingContext: {
      vehicleId,
      driverId,
      timestamp: Date.now(),
    },
  }));

  if (!remoteTrip.route) {
    throw new Error(`Trip mapper: remote trip ${remoteTrip.id} is missing route`);
  }
  if (!remoteTrip.route.origin) {
    throw new Error(`Trip mapper: remote trip ${remoteTrip.id} is missing route.origin`);
  }

  // Map origin
  const originLocation = await requireTripLocation(String(remoteTrip.route.origin.id), `remote trip ${remoteTrip.id} route.origin`);
  
  // Map waypoints to destinations
  let waypointDestinations: Destination[] = [];
  if (remoteTrip.waypoints && remoteTrip.waypoints.length > 0) {
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "MAPPING_WAYPOINTS",
      tripId: remoteTrip.id,
      waypointsCount: remoteTrip.waypoints.length,
      waypoints: remoteTrip.waypoints.map((wp, idx) => ({
        index: idx,
        id: wp.id,
        locationId: wp.location_id,
        order: wp.order,
        hasLocation: !!wp.location,
        location: wp.location ? {
          id: wp.location.id,
          latitude: wp.location.latitude,
          longitude: wp.location.longitude,
        } : null,
      })),
    }));
    
    for (let i = 0; i < remoteTrip.waypoints.length; i++) {
      const waypoint = remoteTrip.waypoints[i];
      if (waypoint) {
        const dest = await mapRemoteWaypointToDestination(waypoint, i);
        waypointDestinations.push(dest);
        console.log(JSON.stringify({
          level: "DEBUG",
          event: "WAYPOINT_MAPPED",
          tripId: remoteTrip.id,
          waypointIndex: i,
          destination: {
            id: dest.id,
            index: dest.index,
            lat: dest.lat,
            lng: dest.lng,
          },
        }));
      }
    }
  } else {
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "NO_WAYPOINTS",
      tripId: remoteTrip.id,
      waypoints: remoteTrip.waypoints,
    }));
  }
  
  // Add route destination as last destination
  console.log(JSON.stringify({
    level: "DEBUG",
    event: "MAPPING_ROUTE_DESTINATION",
    tripId: remoteTrip.id,
    route: {
      hasDestination: !!remoteTrip.route.destination,
      destination: remoteTrip.route.destination ? {
        id: remoteTrip.route.destination.id,
        latitude: remoteTrip.route.destination.latitude,
        longitude: remoteTrip.route.destination.longitude,
      } : null,
    },
  }));

  // Ensure route destination is included
  if (remoteTrip.route.destination) {
    waypointDestinations = await ensureRouteDestinationIncluded(
      waypointDestinations,
      remoteTrip.route.destination.id.toString(),
      remoteTrip.route.destination,
      `remote trip ${remoteTrip.id}`
    );
  }
  
  // Get or create driver-car assignment
  const assignmentId = await assignmentRepository.ensureDriverCarAssignment(driverId, vehicleId);
  const assignment = await assignmentRepository.getDriverCarAssignmentById(assignmentId);
  if (!assignment) {
    throw new Error(`Failed to get driver-car assignment ${assignmentId}`);
  }
  
  // Parse timestamps
  if (!remoteTrip.created_at || !remoteTrip.updated_at) {
    throw new Error(`Trip mapper: remote trip ${remoteTrip.id} missing created_at/updated_at`);
  }
  const createdAt = new Date(remoteTrip.created_at).getTime();
  const updatedAt = new Date(remoteTrip.updated_at).getTime();
  if (!Number.isFinite(createdAt) || !Number.isFinite(updatedAt)) {
    throw new Error(`Trip mapper: remote trip ${remoteTrip.id} has invalid created_at/updated_at`);
  }
  
  // Normalize destination indices to ensure they are sequential without duplicates
  const normalizedDestinations = normalizeDestinationIndices(waypointDestinations);
  
  // Validate all locations before proceeding
  validateTripLocations(
    originLocation,
    normalizedDestinations,
    remoteTrip.route.destination?.id?.toString(),
    `remote trip ${remoteTrip.id}`
  );
  
  const totalDistance = ensureFiniteNumber(remoteTrip.route.distance_meters, `remote trip ${remoteTrip.id} route.distance_meters`);

  const result = {
    id: String(remoteTrip.id),
    carDriver: assignment,
    origin: originLocation,
    destinations: normalizedDestinations,
    status: mapRemoteStatusToLocal(remoteTrip.status),
    totalDistance,
    createdAt,
    updatedAt,
  };

  // Log parsed mapping results
  console.log(JSON.stringify({
    level: "INFO",
    event: "REMOTE_TRIP_MAPPING_COMPLETE",
    source: "RABBITMQ",
    tripId: remoteTrip.id,
    parsedResults: {
      id: result.id,
      origin: {
        id: result.origin.id,
        addres: result.origin.addres,
        lat: result.origin.lat,
        lng: result.origin.lng,
      },
      destinations: result.destinations.map(d => ({
        id: d.id,
        locationId: d.locationId,
        index: d.index,
        addres: d.addres,
        lat: d.lat,
        lng: d.lng,
        order: d.order,
        fare: d.fare,
        isPassede: d.isPassede,
      })),
      status: result.status,
      totalDistance: result.totalDistance,
      createdAt: result.createdAt,
      updatedAt: result.updatedAt,
      carDriver: {
        vehicleId: assignment.car.id,
        driverId: assignment.driver?.id || null,
      },
      destinationsCount: result.destinations.length,
    },
    mappingMetrics: {
      processingTimeMs: Date.now() - (new Date().getTime()),
      waypointsProcessed: remoteTrip.waypoints?.length || 0,
      routeDestinationAdded: !remoteTrip.waypoints?.some(wp => wp.location?.id === remoteTrip.route?.destination?.id),
    },
  }));

  return result;
}

function mapTripApiLocationToTripLocation(apiLocation: TripApiLocation | null): TripLocation {
  if (!apiLocation) {
    throw new Error("Trip mapper: trip API location is required");
  }

  const address = apiLocation.custom_name || apiLocation.google_place_name;
  if (!address) {
    throw new Error(`Trip mapper: trip API location ${apiLocation.id} is missing address fields`);
  }

  return {
    id: ensureNonEmptyString(String(apiLocation.id), "apiLocation.id"),
    lat: ensureFiniteNumber(apiLocation.latitude, "apiLocation.latitude"),
    lng: ensureFiniteNumber(apiLocation.longitude, "apiLocation.longitude"),
    addres: address,
  };
}

async function mapTripApiWaypointToDestination(
  waypoint: TripApiWaypoint,
  index: number
): Promise<Destination> {
  if (!waypoint.location) {
    throw new Error(`Trip mapper: trip API waypoint ${waypoint.id} is missing location`);
  }
  
  const location = await requireTripLocation(String(waypoint.location.id), `trip API waypoint ${waypoint.id}`);
  
  return {
    id: String(waypoint.location.id), // Location ID for destination identity
    locationId: location.id, // Reference to trip_locations table
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    order: waypoint.order ?? index,
    index: waypoint.order ?? index,
    fare: waypoint.price ?? 0,
    remainingDistance: waypoint.remaining_distance ?? null,
    isPassede: waypoint.is_passed ?? false,
    passedTime: waypoint.passed_timestamp ?? null,
  };
}

async function mapTripApiRouteDestinationToDestination(
  route: TripApiRoute,
  waypointCount: number
): Promise<Destination> {
  if (!route.destination) {
    throw new Error(`Trip mapper: trip API route ${route.id} is missing destination`);
  }
  
  const location = await requireTripLocation(String(route.destination.id), `trip API route ${route.id} destination`);
  
  return {
    id: String(route.destination.id), // For final destination, id = locationId
    locationId: String(route.destination.id), // Same as id for non-waypoint destinations
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    order: null, // Final destination has no meaningful order value; index is assigned by normalizeDestinationIndices
    index: waypointCount, // Will be normalized
    fare: route.route_price ?? 0,
    remainingDistance: null, // Final destination has no remaining distance
    isPassede: false,
    passedTime: null,
  };
}

export async function mapTripApiItemToLocalTrip(
  tripApiItem: TripApiItem,
  vehicleId: string,
  driverId: string | null
): Promise<Trip> {
  // Log full received structure
  console.log(JSON.stringify({
    level: "INFO",
    event: "TRIP_API_MAPPING_START",
    source: "API_FETCH",
    tripId: tripApiItem.id,
    receivedStructure: {
      id: tripApiItem.id,
      route_id: tripApiItem.route_id,
      status: tripApiItem.status,
      created_at: tripApiItem.created_at,
      updated_at: tripApiItem.updated_at,
      route: tripApiItem.route ? {
        id: tripApiItem.route.id,
        origin_id: tripApiItem.route.origin_id,
        destination_id: tripApiItem.route.destination_id,
        distance_meters: tripApiItem.route.distance_meters,
        origin: tripApiItem.route.origin ? {
          id: tripApiItem.route.origin.id,
          latitude: tripApiItem.route.origin.latitude,
          longitude: tripApiItem.route.origin.longitude,
          custom_name: tripApiItem.route.origin.custom_name,
          google_place_name: tripApiItem.route.origin.google_place_name,
        } : null,
        destination: tripApiItem.route.destination ? {
          id: tripApiItem.route.destination.id,
          latitude: tripApiItem.route.destination.latitude,
          longitude: tripApiItem.route.destination.longitude,
          custom_name: tripApiItem.route.destination.custom_name,
          google_place_name: tripApiItem.route.destination.google_place_name,
        } : null,
      } : null,
      waypoints: tripApiItem.waypoints ? tripApiItem.waypoints.map(wp => ({
        id: wp.id,
        location_id: wp.location_id,
        order: wp.order,
        location: wp.location ? {
          id: wp.location.id,
          latitude: wp.location.latitude,
          longitude: wp.location.longitude,
          custom_name: wp.location.custom_name,
          google_place_name: wp.location.google_place_name,
        } : null,
      })) : null,
    },
    mappingContext: {
      timestamp: Date.now(),
    },
  }));

  if (!tripApiItem.route) {
    throw new Error(`Trip mapper: trip API item ${tripApiItem.id} is missing route`);
  }
  if (!tripApiItem.route.origin) {
    throw new Error(`Trip mapper: trip API item ${tripApiItem.id} is missing route.origin`);
  }

  // Map origin
  const originLocation = await requireTripLocation(String(tripApiItem.route.origin.id), `trip API item ${tripApiItem.id} route.origin`);
  
  // Map waypoints to destinations
  let waypointDestinations: Destination[] = [];
  if (tripApiItem.waypoints && tripApiItem.waypoints.length > 0) {
    for (let i = 0; i < tripApiItem.waypoints.length; i++) {
      const waypoint = tripApiItem.waypoints[i];
      if (waypoint) {
        const dest = await mapTripApiWaypointToDestination(waypoint, i);
        waypointDestinations.push(dest);
      }
    }
  }
  
  // Ensure route destination is included
  if (tripApiItem.route.destination) {
    waypointDestinations = await ensureRouteDestinationIncluded(
      waypointDestinations,
      tripApiItem.route.destination.id.toString(),
      tripApiItem.route.destination,
      `trip API item ${tripApiItem.id}`
    );
  }
  
  // Get or create driver-car assignment
  const assignmentId = await assignmentRepository.ensureDriverCarAssignment(driverId, vehicleId);
  const assignment = await assignmentRepository.getDriverCarAssignmentById(assignmentId);
  if (!assignment) {
    throw new Error(`Failed to get driver-car assignment ${assignmentId}`);
  }
  
  // Parse timestamps
  if (!tripApiItem.created_at || !tripApiItem.updated_at) {
    throw new Error(`Trip mapper: trip API item ${tripApiItem.id} missing created_at/updated_at`);
  }
  const createdAt = new Date(tripApiItem.created_at).getTime();
  const updatedAt = new Date(tripApiItem.updated_at).getTime();
  if (!Number.isFinite(createdAt) || !Number.isFinite(updatedAt)) {
    throw new Error(`Trip mapper: trip API item ${tripApiItem.id} has invalid created_at/updated_at`);
  }
  
  // Normalize destination indices to ensure they are sequential without duplicates
  const normalizedDestinations = normalizeDestinationIndices(waypointDestinations);
  
  // Validate all locations before proceeding
  validateTripLocations(
    originLocation,
    normalizedDestinations,
    tripApiItem.route.destination?.id?.toString(),
    `trip API item ${tripApiItem.id}`
  );
  
  const totalDistance = ensureFiniteNumber(tripApiItem.route.distance_meters, `trip API item ${tripApiItem.id} route.distance_meters`);

  const result = {
    id: String(tripApiItem.id),
    carDriver: assignment,
    origin: originLocation,
    destinations: normalizedDestinations,
    status: mapRemoteStatusToLocal(tripApiItem.status),
    totalDistance,
    createdAt,
    updatedAt,
  };

  // Log parsed mapping results
  console.log(JSON.stringify({
    level: "INFO",
    event: "TRIP_API_MAPPING_COMPLETE",
    source: "API_FETCH",
    tripId: tripApiItem.id,
    parsedResults: {
      id: result.id,
      origin: {
        id: result.origin.id,
        addres: result.origin.addres,
        lat: result.origin.lat,
        lng: result.origin.lng,
      },
      destinations: result.destinations.map(d => ({
        id: d.id,
        locationId: d.locationId,
        index: d.index,
        addres: d.addres,
        lat: d.lat,
        lng: d.lng,
        order: d.order,
        fare: d.fare,
        isPassede: d.isPassede,
      })),
      status: result.status,
      totalDistance: result.totalDistance,
      createdAt: result.createdAt,
      updatedAt: result.updatedAt,
      carDriver: {
        vehicleId: assignment.car.id,
        driverId: assignment.driver?.id || null,
      },
      destinationsCount: result.destinations.length,
    },
    mappingMetrics: {
      processingTimeMs: Date.now() - (new Date().getTime()),
      waypointsProcessed: tripApiItem.waypoints?.length || 0,
      routeDestinationAdded: !tripApiItem.waypoints?.some(wp => wp.location?.id === tripApiItem.route?.destination?.id),
    },
  }));

  return result;
}

async function mapTripServiceWaypointToDestination(
  waypoint: TripServiceWaypoint,
  tripId: string
): Promise<Destination> {
  if (waypoint.id == null) {
    throw new Error(`Trip mapper: trip service waypoint id is required for trip ${tripId}`);
  }
  if (waypoint.location_id == null) {
    throw new Error(`Trip mapper: trip service waypoint ${waypoint.id} missing location_id`);
  }

  const location: TripLocation = {
    id: String(waypoint.location_id),
    lat: 0,
    lng: 0,
    addres: waypoint.location_name,
  };
  
  // Check if location exists, if not we'll need to skip or create minimal
  const existingLocation = await locationRepository.getTripLocationById(location.id);
  if (existingLocation) {
    location.lat = existingLocation.lat;
    location.lng = existingLocation.lng;
    // If the waypoint didn't include a name/address, prefer the stored one
    if (!location.addres) {
      location.addres = existingLocation.addres;
    }
  } else if (waypoint.location) {
    throw new MissingTripLocationError(
      `Trip mapper: trip service waypoint ${waypoint.id} exists remotely but is missing from local locations`
    );
  } else {
    throw new Error(
      `Trip mapper: trip service waypoint ${waypoint.id} cannot be converted (location not found and no nested location data)`
    );
  }

  if (!Number.isFinite(location.lat) || !Number.isFinite(location.lng)) {
    throw new Error(`Trip mapper: trip service waypoint ${waypoint.id} has invalid coordinates`);
  }
  location.addres = ensureNonEmptyString(location.addres, `trip service waypoint ${waypoint.id} address`);

  if (typeof waypoint.order !== "number" || !Number.isFinite(waypoint.order) || waypoint.order < 1) {
    throw new Error(`Trip mapper: trip service waypoint ${waypoint.id} has invalid order`);
  }

  return {
    id: String(waypoint.location_id), // Location ID for destination identity
    locationId: String(waypoint.location_id), // Reference to trip_locations table
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    order: waypoint.order, // Preserve original order for validation
    index: waypoint.order - 1, // Convert 1-based to 0-based for DB indexing (will be normalized)
    fare: waypoint.price ?? 0,
    remainingDistance: waypoint.remaining_distance,
    isPassede: waypoint.is_passed,
    passedTime: waypoint.remaining_time,
  };
}

export async function mapTripServiceTripToLocalTrip(
  tripServiceTrip: TripServiceTrip
): Promise<Trip> {
  const vehicleId = String(tripServiceTrip.vehicle_id);
  const driverId = tripServiceTrip.vehicle.driver ? String(tripServiceTrip.vehicle.driver.id) : null;
  
  // Build origin using nested route.origin; if unavailable, fail fast.
  const routeOriginAny: any = tripServiceTrip.route && (tripServiceTrip.route as any).origin;
  if (!routeOriginAny || typeof routeOriginAny !== "object") {
    throw new Error(`Trip mapper: trip service trip ${tripServiceTrip.id} is missing structured route.origin`);
  }

  const originId = ensureNonEmptyString(String(routeOriginAny.id), `trip service trip ${tripServiceTrip.id} route.origin.id`);

  const originLocation = await requireTripLocation(originId, `trip service trip ${tripServiceTrip.id} route.origin`);
  
  // Map waypoints to destinations
  let destinations: Destination[] = [];
  for (const waypoint of tripServiceTrip.waypoints) {
    const dest = await mapTripServiceWaypointToDestination(waypoint, String(tripServiceTrip.id));
    destinations.push(dest);
  }

  // Sort by incoming order to maintain correct sequence
  destinations.sort((a, b) => {
    const orderA = a.order ?? a.index;
    const orderB = b.order ?? b.index;
    return orderA - orderB;
  });

  // Ensure route destination is included
  const routeAny: any = tripServiceTrip.route;
  if (routeAny && routeAny.destination) {
    destinations = await ensureRouteDestinationIncluded(
      destinations,
      routeAny.destination.id.toString(),
      routeAny.destination,
      `trip service trip ${tripServiceTrip.id}`
    );
  } else {
    throw new Error(`Trip mapper: trip service trip ${tripServiceTrip.id} route.destination is required`);
  }
  
  // Get or create driver-car assignment
  const assignmentId = await assignmentRepository.ensureDriverCarAssignment(driverId, vehicleId);
  const assignment = await assignmentRepository.getDriverCarAssignmentById(assignmentId);
  if (!assignment) {
    throw new Error(`Failed to get driver-car assignment for vehicle ${vehicleId}`);
  }
  
  if (typeof tripServiceTrip.created_at !== "number" || !Number.isFinite(tripServiceTrip.created_at)) {
    throw new Error(`Trip mapper: trip service trip ${tripServiceTrip.id} has invalid created_at`);
  }
  if (typeof tripServiceTrip.updated_at !== "number" || !Number.isFinite(tripServiceTrip.updated_at)) {
    throw new Error(`Trip mapper: trip service trip ${tripServiceTrip.id} has invalid updated_at`);
  }
  const createdAtMs = tripServiceTrip.created_at * 1000;
  const updatedAtMs = tripServiceTrip.updated_at * 1000;

  // Normalize destination indices to ensure they are sequential without duplicates
  const normalizedDestinations = normalizeDestinationIndices(destinations);
  
  // Validate all locations before proceeding
  validateTripLocations(
    originLocation,
    normalizedDestinations,
    routeAny?.destination?.id?.toString(),
    `trip service trip ${tripServiceTrip.id}`
  );

  return {
    id: String(tripServiceTrip.id),
    carDriver: assignment,
    origin: originLocation,
    destinations: normalizedDestinations,
    status: mapRemoteStatusToLocal(tripServiceTrip.status),
    totalDistance: tripServiceTrip.route.distance,
    createdAt: createdAtMs,
    updatedAt: updatedAtMs,
  };
}


