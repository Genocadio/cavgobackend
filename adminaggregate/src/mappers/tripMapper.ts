import type { RemoteTrip, RemoteLocation, RemoteWaypoint, RemoteRoute, Trip, TripLocation, Destination, TripApiItem, TripApiLocation, TripApiWaypoint, TripApiRoute, TripServiceTrip, TripServiceWaypoint } from "../types";
import * as locationRepository from "../repositories/locations";
import * as assignmentRepository from "../repositories/assignments";

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
    id: String(waypoint.id || `waypoint-${index}`),
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
    id: String(route.destination.id),
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    index: waypointCount, // Destination is last
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
  
  return {
    id: String(remoteTrip.id),
    carDriver: assignment,
    origin: originLocation,
    destinations: waypointDestinations,
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
    id: String(waypoint.id),
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
    id: String(route.destination.id),
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    index: waypointCount, // Destination is last
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
  
  return {
    id: String(tripApiItem.id),
    carDriver: assignment,
    origin: originLocation,
    destinations: waypointDestinations,
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
    id: String(waypoint.location_id),
    lat: location.lat,
    lng: location.lng,
    addres: location.addres,
    index: waypoint.order - 1, // Convert 1-based to 0-based
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
  const originLocation: TripLocation = {
    id: `route-origin-${tripServiceTrip.route_id}`,
    lat: 0, // Coordinates not provided in event
    lng: 0,
    addres: tripServiceTrip.route.origin,
  };
  
  // Map waypoints to destinations
  const destinations: Destination[] = [];
  for (const waypoint of tripServiceTrip.waypoints) {
    const dest = await mapTripServiceWaypointToDestination(waypoint, String(tripServiceTrip.id));
    if (dest) {
      destinations.push(dest);
    }
  }
  
  // Get or create driver-car assignment
  const assignmentId = await assignmentRepository.ensureDriverCarAssignment(driverId, vehicleId);
  const assignment = await assignmentRepository.getDriverCarAssignmentById(assignmentId);
  if (!assignment) {
    throw new Error(`Failed to get driver-car assignment for vehicle ${vehicleId}`);
  }
  
  return {
    id: String(tripServiceTrip.id),
    carDriver: assignment,
    origin: originLocation,
    destinations,
    status: mapRemoteStatusToLocal(tripServiceTrip.status),
    totalDistance: tripServiceTrip.route.distance,
    createdAt: tripServiceTrip.created_at * 1000, // Convert seconds to milliseconds
    updatedAt: tripServiceTrip.updated_at * 1000,
  };
}


