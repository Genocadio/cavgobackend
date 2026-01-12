import type { RemoteTrip, RemoteLocation, RemoteWaypoint, RemoteRoute, Trip, TripLocation, Destination, TripApiItem, TripApiLocation, TripApiWaypoint, TripApiRoute, TripServiceTrip, TripServiceWaypoint } from "../types";
import * as locationRepository from "../repositories/locations";
import * as assignmentRepository from "../repositories/assignments";

/**
 * Normalize destination indices to ensure:
 * - Ordering respects the incoming waypoint/destination order field when present
 * - No duplicates, and indices are sequential 0..n after sorting
 * - Route destination (if no order) is pushed to the end via a large sort key
 */
function normalizeDestinationIndices(destinations: Destination[]): Destination[] {
  if (destinations.length === 0) return destinations;

  const sorted = [...destinations].sort((a, b) => {
    const keyA = a.order ?? a.index ?? Number.MAX_SAFE_INTEGER;
    const keyB = b.order ?? b.index ?? Number.MAX_SAFE_INTEGER;
    return keyA - keyB;
  });

  return sorted.map((dest, i) => ({
    ...dest,
    index: i,
  }));
}

function mapRemoteStatusToLocal(remoteStatus: string | null): "scheduled" | "in_progress" | "completed" | "cancelled" {
  if (!remoteStatus) return "scheduled";
  
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
      return "scheduled";
  }
}

function mapRemoteLocationToTripLocation(remoteLocation: RemoteLocation | null): TripLocation | null {
  if (!remoteLocation) return null;
  
  const address = remoteLocation.custom_name || remoteLocation.google_place_name || "";
  
  return {
    id: String(remoteLocation.id),
    lat: remoteLocation.latitude,
    lng: remoteLocation.longitude,
    addres: address,
  };
}

async function mapRemoteWaypointToDestination(
  waypoint: RemoteWaypoint,
  index: number
): Promise<Destination | null> {
  if (!waypoint.location) {
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "WAYPOINT_MAPPING_FAILED",
      reason: "no_location",
      waypoint: {
        id: waypoint.id,
        locationId: waypoint.location_id,
        order: waypoint.order,
      },
    }));
    return null;
  }
  
  const location = mapRemoteLocationToTripLocation(waypoint.location);
  if (!location) {
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "WAYPOINT_MAPPING_FAILED",
      reason: "location_mapping_failed",
      waypoint: {
        id: waypoint.id,
        locationId: waypoint.location_id,
        location: waypoint.location,
      },
    }));
    return null;
  }
  
  // Upsert location
  await locationRepository.upsertTripLocation(location);
  
  return {
    id: String(waypoint.id || `waypoint-${index}`), // Waypoint ID for destination identity
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
): Promise<Destination | null> {
  if (!route.destination) {
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "ROUTE_DESTINATION_MAPPING_FAILED",
      reason: "no_destination",
      route: {
        id: route.id,
        hasOrigin: !!route.origin,
      },
    }));
    return null;
  }
  
  const location = mapRemoteLocationToTripLocation(route.destination);
  if (!location) {
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "ROUTE_DESTINATION_MAPPING_FAILED",
      reason: "location_mapping_failed",
      route: {
        id: route.id,
        destination: route.destination,
      },
    }));
    return null;
  }
  
  // Upsert location
  await locationRepository.upsertTripLocation(location);
  
  return {
    id: String(route.destination.id), // For final destination, id = locationId (no separate waypoint)
    locationId: String(route.destination.id), // Same as id for non-waypoint destinations
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    order: Number.MAX_SAFE_INTEGER, // Ensure final destination sorts last
    index: waypointCount, // Will be normalized
    fare: route.route_price ?? 0,
    remainingDistance: null, // Final destination has no remaining distance
    isPassede: false,
    passedTime: null,
  };
}

export async function mapRemoteTripToLocalTrip(
  remoteTrip: RemoteTrip,
  vehicleId: string,
  driverId: string | null
): Promise<Trip> {
  // Map origin
  const originLocation = remoteTrip.route?.origin
    ? mapRemoteLocationToTripLocation(remoteTrip.route.origin)
    : null;
  
  if (!originLocation) {
    throw new Error("Trip must have an origin location");
  }
  
  // Upsert origin location
  await locationRepository.upsertTripLocation(originLocation);
  
  // Map waypoints to destinations
  const waypointDestinations: Destination[] = [];
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
        if (dest) {
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
        } else {
          console.log(JSON.stringify({
            level: "WARN",
            event: "WAYPOINT_SKIPPED",
            tripId: remoteTrip.id,
            waypointIndex: i,
            reason: "mapping_returned_null",
            waypoint: {
              id: waypoint.id,
              locationId: waypoint.location_id,
              hasLocation: !!waypoint.location,
            },
          }));
        }
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
  if (remoteTrip.route) {
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
    
    const routeDest = await mapRouteDestinationToDestination(remoteTrip.route, waypointDestinations.length);
    if (routeDest) {
      waypointDestinations.push(routeDest);
      console.log(JSON.stringify({
        level: "DEBUG",
        event: "ROUTE_DESTINATION_MAPPED",
        tripId: remoteTrip.id,
        destination: {
          id: routeDest.id,
          index: routeDest.index,
          lat: routeDest.lat,
          lng: routeDest.lng,
        },
      }));
    } else {
      console.log(JSON.stringify({
        level: "WARN",
        event: "ROUTE_DESTINATION_SKIPPED",
        tripId: remoteTrip.id,
        reason: "mapping_returned_null",
        route: {
          hasDestination: !!remoteTrip.route.destination,
        },
      }));
    }
  } else {
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "NO_ROUTE",
      tripId: remoteTrip.id,
    }));
  }
  
  // Get or create driver-car assignment
  const assignmentId = await assignmentRepository.ensureDriverCarAssignment(driverId, vehicleId);
  const assignment = await assignmentRepository.getDriverCarAssignmentById(assignmentId);
  if (!assignment) {
    throw new Error(`Failed to get driver-car assignment ${assignmentId}`);
  }
  
  // Parse timestamps
  const createdAt = remoteTrip.created_at
    ? new Date(remoteTrip.created_at).getTime()
    : Date.now();
  const updatedAt = remoteTrip.updated_at
    ? new Date(remoteTrip.updated_at).getTime()
    : Date.now();
  
  // Normalize destination indices to ensure they are sequential without duplicates
  const normalizedDestinations = normalizeDestinationIndices(waypointDestinations);
  
  return {
    id: String(remoteTrip.id),
    carDriver: assignment,
    origin: originLocation,
    destinations: normalizedDestinations,
    status: mapRemoteStatusToLocal(remoteTrip.status),
    totalDistance: remoteTrip.route?.distance_meters ?? 0,
    createdAt,
    updatedAt,
  };
}

function mapTripApiLocationToTripLocation(apiLocation: TripApiLocation | null): TripLocation | null {
  if (!apiLocation) return null;
  
  const address = apiLocation.custom_name || apiLocation.google_place_name || "";
  
  return {
    id: String(apiLocation.id),
    lat: apiLocation.latitude,
    lng: apiLocation.longitude,
    addres: address,
  };
}

async function mapTripApiWaypointToDestination(
  waypoint: TripApiWaypoint,
  index: number
): Promise<Destination | null> {
  if (!waypoint.location) return null;
  
  const location = mapTripApiLocationToTripLocation(waypoint.location);
  if (!location) return null;
  
  // Upsert location
  await locationRepository.upsertTripLocation(location);
  
  return {
    id: String(waypoint.id), // Waypoint ID for destination identity
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
): Promise<Destination | null> {
  if (!route.destination) return null;
  
  const location = mapTripApiLocationToTripLocation(route.destination);
  if (!location) return null;
  
  // Upsert location
  await locationRepository.upsertTripLocation(location);
  
  return {
    id: String(route.destination.id), // For final destination, id = locationId
    locationId: String(route.destination.id), // Same as id for non-waypoint destinations
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    order: Number.MAX_SAFE_INTEGER,
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
  // Map origin
  const originLocation = tripApiItem.route?.origin
    ? mapTripApiLocationToTripLocation(tripApiItem.route.origin)
    : null;
  
  if (!originLocation) {
    throw new Error("Trip must have an origin location");
  }
  
  // Upsert origin location
  await locationRepository.upsertTripLocation(originLocation);
  
  // Map waypoints to destinations
  const waypointDestinations: Destination[] = [];
  if (tripApiItem.waypoints && tripApiItem.waypoints.length > 0) {
    for (let i = 0; i < tripApiItem.waypoints.length; i++) {
      const waypoint = tripApiItem.waypoints[i];
      if (waypoint) {
        const dest = await mapTripApiWaypointToDestination(waypoint, i);
        if (dest) {
          waypointDestinations.push(dest);
        }
      }
    }
  }
  
  // Add route destination as last destination
  if (tripApiItem.route) {
    const routeDest = await mapTripApiRouteDestinationToDestination(tripApiItem.route, waypointDestinations.length);
    if (routeDest) {
      waypointDestinations.push(routeDest);
    }
  }
  
  // Get or create driver-car assignment
  const assignmentId = await assignmentRepository.ensureDriverCarAssignment(driverId, vehicleId);
  const assignment = await assignmentRepository.getDriverCarAssignmentById(assignmentId);
  if (!assignment) {
    throw new Error(`Failed to get driver-car assignment ${assignmentId}`);
  }
  
  // Parse timestamps
  const createdAt = tripApiItem.created_at
    ? new Date(tripApiItem.created_at).getTime()
    : Date.now();
  const updatedAt = tripApiItem.updated_at
    ? new Date(tripApiItem.updated_at).getTime()
    : Date.now();
  
  // Normalize destination indices to ensure they are sequential without duplicates
  const normalizedDestinations = normalizeDestinationIndices(waypointDestinations);
  
  return {
    id: String(tripApiItem.id),
    carDriver: assignment,
    origin: originLocation,
    destinations: normalizedDestinations,
    status: mapRemoteStatusToLocal(tripApiItem.status),
    totalDistance: tripApiItem.route?.distance_meters ?? 0,
    createdAt,
    updatedAt,
  };
}

async function mapTripServiceWaypointToDestination(
  waypoint: TripServiceWaypoint,
  tripId: string
): Promise<Destination | null> {
  const location: TripLocation = {
    id: String(waypoint.location_id),
    lat: 0, // We don't have coordinates from this event
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
    // If the waypoint includes a nested `location` object, prefer fields from it
    const nested = waypoint.location;
    location.lat = nested.latitude ?? location.lat;
    location.lng = nested.longitude ?? location.lng;
    // Prefer custom_name, then google_place_name, then fallback to any provided location_name
    location.addres = nested.custom_name || nested.google_place_name || location.addres || "";
  } else {
    // Skip waypoints without location data
    console.warn(JSON.stringify({
      level: "WARN",
      event: "TRIP_SERVICE_WAYPOINT_SKIPPED",
      reason: "location_not_found",
      tripId,
      waypointId: waypoint.id,
      locationId: waypoint.location_id,
    }));
    return null;
  }
  
  return {
    id: String(waypoint.id), // Waypoint ID for destination identity
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
  
  // Create origin from route origin (just use route name for now)
  // Build origin using nested route.origin when available.
  const routeOriginAny: any = tripServiceTrip.route && (tripServiceTrip.route as any).origin;
  const originAddress = routeOriginAny
    ? (routeOriginAny.custom_name || routeOriginAny.google_place_name || routeOriginAny.name || "")
    : (typeof tripServiceTrip.route?.origin === "string" ? (tripServiceTrip.route.origin as unknown as string) : "");
  const originLat = routeOriginAny && typeof routeOriginAny.latitude === "number" ? routeOriginAny.latitude : 0;
  const originLng = routeOriginAny && typeof routeOriginAny.longitude === "number" ? routeOriginAny.longitude : 0;

  const originLocation: TripLocation = {
    id: `route-origin-${tripServiceTrip.route_id}`,
    lat: originLat,
    lng: originLng,
    addres: originAddress,
  };
  // Upsert origin location so it's available in trip_locations
  await locationRepository.upsertTripLocation(originLocation);
  
  // Map waypoints to destinations
  const destinations: Destination[] = [];
  for (const waypoint of tripServiceTrip.waypoints) {
    const dest = await mapTripServiceWaypointToDestination(waypoint, String(tripServiceTrip.id));
    if (dest) {
      destinations.push(dest);
    }
  }

  // Sort by incoming order to maintain correct sequence
  destinations.sort((a, b) => {
    const orderA = a.order ?? a.index;
    const orderB = b.order ?? b.index;
    return orderA - orderB;
  });

  // Append route destination as last destination when available
  const routeAny: any = tripServiceTrip.route;
  if (routeAny && routeAny.destination) {
    const destLocation: TripLocation = {
      id: String(routeAny.destination.id),
      lat: routeAny.destination.latitude ?? 0,
      lng: routeAny.destination.longitude ?? 0,
      addres: routeAny.destination.custom_name || routeAny.destination.google_place_name || "",
    };
    await locationRepository.upsertTripLocation(destLocation);

    destinations.push({
      id: String(routeAny.destination.id), // For final destination, id = locationId
      locationId: String(routeAny.destination.id), // Same as id for non-waypoint destinations
      lat: destLocation.lat,
      lng: destLocation.lng,
      addres: destLocation.addres,
      order: Number.MAX_SAFE_INTEGER, // Force final destination to sort last
      index: destinations.length, // Will be normalized
      fare: routeAny.route_price ?? 0,
      remainingDistance: null,
      isPassede: false,
      passedTime: null,
    });
  }
  
  // Get or create driver-car assignment
  const assignmentId = await assignmentRepository.ensureDriverCarAssignment(driverId, vehicleId);
  const assignment = await assignmentRepository.getDriverCarAssignmentById(assignmentId);
  if (!assignment) {
    throw new Error(`Failed to get driver-car assignment for vehicle ${vehicleId}`);
  }
  
  const createdAtMs = typeof tripServiceTrip.created_at === "number" && isFinite(tripServiceTrip.created_at)
    ? tripServiceTrip.created_at * 1000
    : Date.now();
  const updatedAtMs = typeof tripServiceTrip.updated_at === "number" && isFinite(tripServiceTrip.updated_at)
    ? tripServiceTrip.updated_at * 1000
    : createdAtMs;

  // Normalize destination indices to ensure they are sequential without duplicates
  const normalizedDestinations = normalizeDestinationIndices(destinations);

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


