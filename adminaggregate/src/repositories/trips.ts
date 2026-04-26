import { and, asc, eq, inArray, max, not, or, sql } from "drizzle-orm";
import type { InferModel } from "drizzle-orm";
import { db } from "../db/client";
import {
  bookings,
  cars,
  driverCarAssignments,
  tripDestinations,
  tripLocations,
  trips,
} from "../db/schema";
import { getDriverCarAssignmentById, ensureDriverCarAssignment } from "./assignments";
import { getTripLocationByIds } from "./locations";
import type { Destination, Trip, TripLocation } from "../types";

type TripDestinationRow = InferModel<typeof tripDestinations>;

const buildDestinationRowId = (tripId: string, locationId: string): string => `${tripId}:${locationId}`;

async function getExistingTripDestinationIds(tripId: string): Promise<Map<string, string>> {
  const rows = await db
    .select({ id: tripDestinations.id, locationId: tripDestinations.locationId })
    .from(tripDestinations)
    .where(eq(tripDestinations.tripId, tripId));

  return new Map(rows.map((row) => [row.locationId, row.id]));
}

async function ensureTripLocationsExist(trip: Trip): Promise<void> {
  const requiredLocationIds = new Set<string>([trip.origin.id]);
  for (const destination of trip.destinations) {
    requiredLocationIds.add(destination.locationId || destination.id);
  }

  const locations = await getTripLocationByIds([...requiredLocationIds]);
  const existingLocationIds = new Set(locations.map((location) => location.id));
  const missingLocationIds = [...requiredLocationIds].filter((locationId) => !existingLocationIds.has(locationId));

  if (missingLocationIds.length > 0) {
    throw new Error(`Trip ${trip.id} references missing local locations: ${missingLocationIds.join(", ")}`);
  }
}

async function fetchTripsForAssignments(assignmentIds: number[]) {
  const rowsByAssignment = await Promise.all(
    assignmentIds.map((assignmentId) =>
      db
        .select()
        .from(trips)
        .where(eq(trips.driverCarAssignmentId, assignmentId)),
    ),
  );

  return rowsByAssignment
    .flat()
    .sort((a, b) => {
      const bUpdated = b.updatedAt?.getTime() ?? 0;
      const aUpdated = a.updatedAt?.getTime() ?? 0;
      return bUpdated - aUpdated;
    });
}

const destinationFromRow = (row: TripDestinationRow, location: TripLocation): Destination => ({
  // Keep the public destination identity anchored on the location id.
  id: row.locationId,
  addres: location.addres,
  lat: location.lat,
  lng: location.lng,
  order: row.order ?? null, // Preserve original order from event, or null if not set
  index: row.index,
  fare: Number(row.fare),
  remainingDistance: row.remainingDistance == null ? null : Number(row.remainingDistance),
  isPassede: row.isPassede,
  passedTime: row.passedTime == null ? null : Number(row.passedTime),
});

/**
 * Detect if a trip has duplicate or non-sequential destination indices
 * Returns true if indexing issues are found
 */
export async function hasDestinationIndexingIssues(tripId: string): Promise<boolean> {
  try {
    const destinations = await db
      .select({ index: tripDestinations.index })
      .from(tripDestinations)
      .where(eq(tripDestinations.tripId, tripId))
      .orderBy(asc(tripDestinations.index));
    
    if (destinations.length === 0) return false;
    
    const indices = destinations.map(d => d.index);
    const uniqueIndices = new Set(indices);
    
    // Check for duplicates
    if (uniqueIndices.size !== indices.length) return true;
    
    // Check for sequential order (0, 1, 2, ..., n)
    for (let i = 0; i < indices.length; i++) {
      if (indices[i] !== i) return true;
    }
    
    return false;
  } catch (error) {
    console.error(JSON.stringify({
      level: "ERROR",
      event: "DESTINATION_INDEXING_CHECK_FAILED",
      tripId,
      error: error instanceof Error ? error.message : String(error),
    }));
    return false;
  }
}

/**
 * Fix destination indexing by reassigning sequential indices (0, 1, 2, ..., n)
 * Preserves destination order by sorting by current index before reassigning
 */
export async function fixDestinationIndexing(tripId: string): Promise<void> {
  try {
    const destinations = await db
      .select()
      .from(tripDestinations)
      .where(eq(tripDestinations.tripId, tripId))
      .orderBy(asc(tripDestinations.index));
    
    // Update each destination with correct sequential index
    for (let i = 0; i < destinations.length; i++) {
      const dest = destinations[i];
      if (dest) {
        await db
          .update(tripDestinations)
          .set({ index: i })
          .where(eq(tripDestinations.id, dest.id));
      }
    }
    
    console.log(JSON.stringify({
      level: "INFO",
      event: "DESTINATION_INDEXING_FIXED",
      tripId,
      destinationCount: destinations.length,
    }));
  } catch (error) {
    console.error(JSON.stringify({
      level: "ERROR",
      event: "DESTINATION_INDEXING_FIX_FAILED",
      tripId,
      error: error instanceof Error ? error.message : String(error),
    }));
  }
}

export async function createTrip(trip: Trip): Promise<Trip | null> {
  console.log(JSON.stringify({
    level: "DEBUG",
    event: "CREATING_TRIP",
    tripId: trip.id,
    destinationsCount: trip.destinations.length,
    destinations: trip.destinations.map(d => ({
      id: d.id,
      index: d.index,
      lat: d.lat,
      lng: d.lng,
    })),
  }));

  // STRICT VALIDATION: Block trip creation without destinations
  if (!trip.destinations || trip.destinations.length === 0) {
    const error = `Trip creation BLOCKED: Trip ${trip.id} has no destinations. This indicates invalid data from source API/RabbitMQ. Trip will NOT be created.`;
    console.error(JSON.stringify({
      level: "ERROR",
      event: "TRIP_CREATION_BLOCKED_NO_DESTINATIONS",
      tripId: trip.id,
      error,
      tripData: {
        id: trip.id,
        status: trip.status,
        origin: trip.origin ? { id: trip.origin.id, addres: trip.origin.addres } : null,
        destinationsCount: trip.destinations?.length || 0,
        totalDistance: trip.totalDistance,
      },
    }));
    throw new Error(error);
  }

  // Additional validation: ensure all destinations have valid data
  for (let i = 0; i < trip.destinations.length; i++) {
    const dest = trip.destinations[i];
    if (!dest || !dest.id || !Number.isFinite(dest.lat) || !Number.isFinite(dest.lng) || !dest.addres?.trim()) {
      const error = `Trip creation BLOCKED: Trip ${trip.id} has invalid destination at index ${i}. Destination data incomplete.`;
      console.error(JSON.stringify({
        level: "ERROR",
        event: "TRIP_CREATION_BLOCKED_INVALID_DESTINATION",
        tripId: trip.id,
        destinationIndex: i,
        destinationData: dest,
        error,
      }));
      throw new Error(error);
    }
  }
  
  // Check if all required locations exist, skip trip if missing
  try {
    await ensureTripLocationsExist(trip);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    if (errorMessage.includes("missing local locations")) {
      console.warn(JSON.stringify({
        level: "WARN",
        event: "TRIP_SKIPPED_MISSING_LOCATIONS",
        tripId: trip.id,
        message: "Trip skipped due to missing locations - location sync should handle this",
        error: errorMessage,
      }));
      return null; // Skip this trip, let location sync handle it
    }
    throw error; // Re-throw other errors
  }

  const assignmentId = await ensureDriverCarAssignment(
    trip.carDriver.driver?.id ?? null,
    trip.carDriver.car.id,
  );

  await db.insert(trips).values({
    id: trip.id,
    driverCarAssignmentId: assignmentId,
    originLocationId: trip.origin.id,
    status: trip.status,
    totalDistance: trip.totalDistance,
    createdAt: new Date(trip.createdAt),
    updatedAt: new Date(trip.updatedAt),
  });

  const savedDestinations: string[] = [];
  const existingDestinationIds = await getExistingTripDestinationIds(trip.id);
  for (const destination of trip.destinations) {
    // Use locationId or fallback to destination.id for consistency
    const locationRef = destination.locationId || destination.id;
    const destinationRowId = existingDestinationIds.get(locationRef) ?? buildDestinationRowId(trip.id, locationRef);
    
    try {
      // DEBUG: Log destination saving for debugging
      console.log(JSON.stringify({
        level: "DEBUG",
        event: "SAVING_DESTINATION_RAW",
        tripId: trip.id,
        destinationId: destination.id,
        locationId: destination.locationId,
        locationRef,
        index: destination.index,
        addres: destination.addres,
      }));
      
      // Insert destination with proper conflict resolution
      await db
        .insert(tripDestinations)
        .values({
          id: destinationRowId,
          tripId: trip.id,
          locationId: locationRef,
          index: destination.index,
          fare: destination.fare.toString(),
          remainingDistance: destination.remainingDistance ?? null,
          isPassede: destination.isPassede,
          passedTime: destination.passedTime ?? null,
        })
        .onConflictDoUpdate({
          target: tripDestinations.id,
          set: {
            index: destination.index,
            fare: destination.fare.toString(),
            remainingDistance: destination.remainingDistance ?? null,
            isPassede: destination.isPassede,
            passedTime: destination.passedTime ?? null,
            locationId: locationRef,
          },
        });
      
      existingDestinationIds.set(locationRef, destinationRowId);
      savedDestinations.push(destinationRowId);
    } catch (error) {
      console.error(JSON.stringify({
        level: "ERROR",
        event: "DESTINATION_SAVE_FAILED",
        tripId: trip.id,
        destinationId: destination.id,
        locationId: destination.locationId,
        locationRef,
        error: error instanceof Error ? error.message : String(error),
      }));
      throw error;
    }
  }

  // Verify destination rows were persisted with enhanced logging
  try {
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "DESTINATION_VERIFICATION_START",
      tripId: trip.id,
      expectedCount: savedDestinations.length,
      savedDestinations,
    }));
    
    const dbRows = await db
      .select({ id: tripDestinations.id, index: tripDestinations.index })
      .from(tripDestinations)
      .where(eq(tripDestinations.tripId, trip.id));
    const existingIds = dbRows.map(r => r.id);
    const missing = savedDestinations.filter(id => !existingIds.includes(id));
    
    console.log(JSON.stringify({
      level: "DEBUG",
      event: "DESTINATION_VERIFICATION_CHECK",
      tripId: trip.id,
      expectedCount: savedDestinations.length,
      actualCount: existingIds.length,
      existingIds,
      missing,
    }));
    
    if (missing.length > 0) {
      console.warn(JSON.stringify({ level: "WARN", event: "MISSING_DESTINATIONS_AFTER_CREATE", tripId: trip.id, missing }));
      for (const destinationRowId of missing) {
        // Recreate minimal destination row from in-memory trip.destinations
        const dest = trip.destinations.find((d) => {
          const locationRef = d.locationId || d.id;
          const expectedRowId = existingDestinationIds.get(locationRef) ?? buildDestinationRowId(trip.id, locationRef);
          return expectedRowId === destinationRowId;
        });
        if (dest) {
          const locationRef = dest.locationId || dest.id;
          console.log(JSON.stringify({
            level: "DEBUG",
            event: "RECOVERING_MISSING_DESTINATION",
            tripId: trip.id,
            destinationRowId,
            destinationIndex: dest.index,
          }));
          
          await db.insert(tripDestinations).values({
            id: destinationRowId,
            tripId: trip.id,
            locationId: locationRef,
            index: dest.index,
            fare: dest.fare.toString(),
            remainingDistance: dest.remainingDistance ?? null,
            isPassede: dest.isPassede,
            passedTime: dest.passedTime ?? null,
          }).onConflictDoUpdate({
            target: tripDestinations.id,
            set: {
              tripId: trip.id,
              locationId: locationRef,
              index: dest.index,
              fare: dest.fare.toString(),
              remainingDistance: dest.remainingDistance ?? null,
              isPassede: dest.isPassede,
              passedTime: dest.passedTime ?? null,
            },
          });
        } else {
          console.error(JSON.stringify({
            level: "ERROR",
            event: "CANNOT_RECOVER_MISSING_DESTINATION",
            tripId: trip.id,
            destinationRowId,
            reason: "Destination not found in trip.destinations",
          }));
        }
      }
      
      // Final verification after recovery
      const finalDbRows = await db
        .select({ id: tripDestinations.id })
        .from(tripDestinations)
        .where(eq(tripDestinations.tripId, trip.id));
      const finalExistingIds = finalDbRows.map(r => r.id);
      const stillMissing = savedDestinations.filter(id => !finalExistingIds.includes(id));
      
      if (stillMissing.length > 0) {
        console.error(JSON.stringify({
          level: "ERROR",
          event: "DESTINATION_RECOVERY_FAILED",
          tripId: trip.id,
          stillMissing,
        }));
        throw new Error(`Failed to save ${stillMissing.length} destinations for trip ${trip.id}`);
      } else {
        console.log(JSON.stringify({
          level: "INFO",
          event: "DESTINATION_RECOVERY_SUCCESS",
          tripId: trip.id,
          recoveredCount: missing.length,
          finalCount: finalExistingIds.length,
        }));
      }
    }
  } catch (err) {
    console.error(JSON.stringify({ level: "ERROR", event: "DESTINATION_VERIFICATION_FAILED", tripId: trip.id, error: err instanceof Error ? err.message : String(err) }));
    throw err;
  }

  console.log(JSON.stringify({
    level: "DEBUG",
    event: "TRIP_CREATED",
    tripId: trip.id,
    savedDestinationsCount: savedDestinations.length,
    savedDestinationIds: savedDestinations,
  }));

  return trip;
}

export async function updateTrip(trip: Trip): Promise<Trip | null> {
  // Special diagnostic logging for Trip 521
  if (trip.id === "521") {
    console.log(JSON.stringify({
      level: "DIAG",
      event: "TRIP_521_UPDATE_ATTEMPT",
      tripId: trip.id,
      destinationsCount: trip.destinations.length,
      destinations: trip.destinations.map(d => ({
        id: d.id,
        locationId: d.locationId,
        index: d.index,
        address: d.addres,
      })),
      caller: new Error().stack?.split('\n')[2]?.trim(), // Track who called this
    }));
  }
  
  console.log(JSON.stringify({
    level: "DEBUG",
    event: "UPDATING_TRIP",
    tripId: trip.id,
    destinationsCount: trip.destinations.length,
    destinations: trip.destinations.map(d => ({
      id: d.id,
      index: d.index,
      lat: d.lat,
      lng: d.lng,
    })),
  }));

  // STRICT VALIDATION: Block trip updates without destinations
  if (!trip.destinations || trip.destinations.length === 0) {
    const error = `Trip update BLOCKED: Trip ${trip.id} has no destinations. This indicates invalid data from source API/RabbitMQ. Trip will NOT be updated.`;
    console.error(JSON.stringify({
      level: "ERROR",
      event: "TRIP_UPDATE_BLOCKED_NO_DESTINATIONS",
      tripId: trip.id,
      error,
      tripData: {
        id: trip.id,
        status: trip.status,
        origin: trip.origin ? { id: trip.origin.id, addres: trip.origin.addres } : null,
        destinationsCount: trip.destinations?.length || 0,
        totalDistance: trip.totalDistance,
      },
    }));
    throw new Error(error);
  }

  // Additional validation: ensure all destinations have valid data
  for (let i = 0; i < trip.destinations.length; i++) {
    const dest = trip.destinations[i];
    if (!dest || !dest.id || !Number.isFinite(dest.lat) || !Number.isFinite(dest.lng) || !dest.addres?.trim()) {
      const error = `Trip update BLOCKED: Trip ${trip.id} has invalid destination at index ${i}. Destination data incomplete.`;
      console.error(JSON.stringify({
        level: "ERROR",
        event: "TRIP_UPDATE_BLOCKED_INVALID_DESTINATION",
        tripId: trip.id,
        destinationIndex: i,
        destinationData: dest,
        error,
      }));
      throw new Error(error);
    }
  }
  
  // Check if all required locations exist, skip trip if missing
  try {
    await ensureTripLocationsExist(trip);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    if (errorMessage.includes("missing local locations")) {
      console.warn(JSON.stringify({
        level: "WARN",
        event: "TRIP_SKIPPED_MISSING_LOCATIONS",
        tripId: trip.id,
        message: "Trip skipped due to missing locations - location sync should handle this",
        error: errorMessage,
      }));
      return null; // Skip this trip, let location sync handle it
    }
    throw error; // Re-throw other errors
  }

  const assignmentId = await ensureDriverCarAssignment(
    trip.carDriver.driver?.id ?? null,
    trip.carDriver.car.id,
  );

  await db
    .update(trips)
    .set({
      originLocationId: trip.origin.id,
      status: trip.status,
      totalDistance: trip.totalDistance,
      updatedAt: new Date(trip.updatedAt),
      driverCarAssignmentId: assignmentId,
    })
    .where(eq(trips.id, trip.id));
  const existingDestinationIds = await getExistingTripDestinationIds(trip.id);
  if (trip.destinations.length > 0) {
    // Calculate keepIds using the same logic as destination saving (locationId || id)
    const keepIds = trip.destinations.map((d) => {
      // Use the same identifier logic as in destination saving
      const savedRef = d.locationId || d.id;
      const destinationRowId = existingDestinationIds.get(savedRef) ?? buildDestinationRowId(trip.id, savedRef);
      console.log(JSON.stringify({
        level: "DEBUG",
        event: "DESTINATION_KEEP_ID_CALCULATION",
        tripId: trip.id,
        destinationId: d.id,
        locationId: d.locationId,
        savedRef,
        destinationRowId,
      }));
      return destinationRowId;
    });
    
    // Get existing destinations and determine what to delete
    try {
      const existing = await db.select({ id: tripDestinations.id }).from(tripDestinations).where(eq(tripDestinations.tripId, trip.id));
      const existingIds = existing.map(r => r.id);
      const toDelete = existingIds.filter(id => !keepIds.includes(id));
      
      // Special diagnostic logging for Trip 521
      if (trip.id === "521") {
        console.log(JSON.stringify({ 
          level: "DIAG", 
          event: "TRIP_521_DESTINATION_PRUNE", 
          tripId: trip.id, 
          destinationsInMemory: trip.destinations.length,
          keepIds, 
          existingIds, 
          toDelete,
          destinationsToSave: trip.destinations.map(d => ({
            id: d.id,
            locationId: d.locationId,
            index: d.index,
            address: d.addres,
          }))
        }));
      }
      
      console.log(JSON.stringify({ 
        level: "DEBUG", 
        event: "DESTINATIONS_PRUNE", 
        tripId: trip.id, 
        keepIds, 
        existingIds, 
        toDelete,
        destinationsToSave: trip.destinations.map(d => ({
          id: d.id,
          locationId: d.locationId,
          index: d.index,
          address: d.addres,
        }))
      }));
      
      if (toDelete.length > 0) {
        // Special diagnostic logging for Trip 521 deletion
        if (trip.id === "521") {
          console.log(JSON.stringify({
            level: "DIAG",
            event: "TRIP_521_DELETING_DESTINATIONS",
            tripId: trip.id,
            toDelete,
          }));
        }
        
        await db.delete(tripDestinations).where(and(eq(tripDestinations.tripId, trip.id), not(inArray(tripDestinations.id, keepIds))));
      }
    } catch (err) {
      console.error(JSON.stringify({ level: "ERROR", event: "DESTINATION_PRUNE_FAILED", tripId: trip.id, error: err instanceof Error ? err.message : String(err) }));
    }
  } else {
    // Special diagnostic logging for Trip 521 with no destinations
    if (trip.id === "521") {
      console.log(JSON.stringify({
        level: "DIAG",
        event: "TRIP_521_NO_DESTINATIONS",
        tripId: trip.id,
        action: "DELETING_ALL_DESTINATIONS",
        reason: "Trip has 0 destinations in memory, deleting all from database",
      }));
    }
    await db.delete(tripDestinations).where(eq(tripDestinations.tripId, trip.id));
  }

  const savedDestinations = await Promise.all(
    trip.destinations.map(async (destination, destIndex) => {
      // Use locationId if available (for waypoints), otherwise use id (for route destinations)
      const locationRef = destination.locationId || destination.id;
      const destinationRowId = existingDestinationIds.get(locationRef) ?? buildDestinationRowId(trip.id, locationRef);
      
      console.log(JSON.stringify({
        level: "DEBUG",
        event: "SAVING_DESTINATION",
        tripId: trip.id,
        destinationIndex: destIndex,
        destinationId: destination.id,
        locationId: destination.locationId,
        locationRef,
        destinationRowId,
        destinationAddress: destination.addres,
        destinationDbIndex: destination.index,
      }));
      
      await db
        .insert(tripDestinations)
        .values({
          id: destinationRowId,
          tripId: trip.id,
          locationId: locationRef,
          order: destination.order ?? null, // Store original order from event
          index: destination.index,
          fare: destination.fare.toString(),
          remainingDistance: destination.remainingDistance ?? null,
          isPassede: destination.isPassede,
          passedTime: destination.passedTime ?? null,
        })
        .onConflictDoUpdate({
          target: tripDestinations.id,
          set: {
            order: destination.order ?? null,
            index: destination.index,
            fare: destination.fare.toString(),
            remainingDistance: destination.remainingDistance ?? null,
            isPassede: destination.isPassede,
            passedTime: destination.passedTime ?? null,
            locationId: locationRef,
          },
        });
      return destinationRowId;
    }),
  );

  console.log(JSON.stringify({
    level: "DEBUG",
    event: "TRIP_UPDATED",
    tripId: trip.id,
    savedDestinationsCount: savedDestinations.length,
    savedDestinationIds: savedDestinations,
  }));

  return trip;
}

export async function deleteTrip(id: string): Promise<void> {
  await db.delete(bookings).where(eq(bookings.tripId, id));
  await db.delete(tripDestinations).where(eq(tripDestinations.tripId, id));
  await db.delete(trips).where(eq(trips.id, id));
}

export async function getTripById(id: string): Promise<Trip | null> {
  // Special diagnostic logging and fix for Trip 521
  if (id === "521") {
    console.log(JSON.stringify({
      level: "DIAG",
      event: "TRIP_521_INVESTIGATION_START",
      tripId: id,
      timestamp: Date.now(),
    }));
    
    // Check if trip exists
    const [tripRowCheck] = await db.select().from(trips).where(eq(trips.id, id));
    if (!tripRowCheck) {
      console.log(JSON.stringify({
        level: "DIAG",
        event: "TRIP_521_NOT_FOUND",
        tripId: id,
      }));
      return null;
    }
    
    // Check destinations directly
    const destinationRowsCheck = await db.select().from(tripDestinations).where(eq(tripDestinations.tripId, id));
    console.log(JSON.stringify({
      level: "DIAG",
      event: "TRIP_521_DESTINATIONS_CHECK",
      tripId: id,
      destinationRowsCount: destinationRowsCheck.length,
      destinationRows: destinationRowsCheck,
    }));
    
    // NOTE: Auto-repair mechanism removed - we now BLOCK trips without destinations entirely
    // If Trip 521 has no destinations, this indicates a fundamental data integrity issue
    // that should be prevented at the source, not repaired after the fact.
  }

  const [tripRow] = await db.select().from(trips).where(eq(trips.id, id));
  if (!tripRow) {
    return null;
  }

  const assignment = await getDriverCarAssignmentById(tripRow.driverCarAssignmentId);
  if (!assignment) {
    return null;
  }

  const [originLocation] = await db
    .select()
    .from(tripLocations)
    .where(eq(tripLocations.id, tripRow.originLocationId));

  if (!originLocation) {
    return null;
  }

  const destinationRows = await db
    .select()
    .from(tripDestinations)
    .where(eq(tripDestinations.tripId, id))
    .orderBy(
      // Order by index primarily (which is always set), treat NULL order as if it equals index
      asc(tripDestinations.index)
    );

  // DEBUG: Log all destination rows for debugging
  console.log(JSON.stringify({
    level: "DEBUG",
    event: "GETTING_TRIP_DESTINATIONS",
    tripId: id,
    destinationRowsCount: destinationRows.length,
    destinationRows: destinationRows.map(row => ({
      id: row.id,
      tripId: row.tripId,
      locationId: row.locationId,
      order: row.order,
      index: row.index,
    })),
  }));

  // Special diagnostic logging for Trip 521
  if (id === "521") {
    console.log(JSON.stringify({
      level: "DIAG",
      event: "TRIP_521_DESTINATION_ROWS",
      tripId: id,
      destinationRowsCount: destinationRows.length,
      destinationRows: destinationRows.map(row => ({
        id: row.id,
        tripId: row.tripId,
        locationId: row.locationId,
        order: row.order,
        index: row.index,
      })),
    }));
  }

  console.log(JSON.stringify({
    level: "DEBUG",
    event: "GETTING_TRIP_DESTINATIONS",
    tripId: id,
    destinationRowsCount: destinationRows.length,
    destinationRows: destinationRows.map(row => ({
      id: row.id,
      tripId: row.tripId,
      locationId: row.locationId,
      order: row.order,
      index: row.index,
    })),
  }));

  const locationIds = destinationRows.map((row) => row.locationId);
  const locations = locationIds.length > 0 ? await getTripLocationByIds(locationIds) : [];
  const locationMap = new Map(locations.map((loc: TripLocation) => [loc.id, loc]));

  let destinations: Destination[] = [];
  for (const row of destinationRows) {
    const location = locationMap.get(row.locationId);
    if (!location) {
      console.error(JSON.stringify({
        level: "ERROR",
        event: "DESTINATION_LOCATION_NOT_FOUND",
        tripId: id,
        destinationId: row.id,
        locationId: row.locationId,
      }));
      continue;
    }
    destinations.push({
      id: row.locationId,
      locationId: row.locationId,
      lat: location.lat,
      lng: location.lng,
      addres: location.addres,
      order: row.order,
      index: row.index,
      fare: parseFloat(row.fare),
      remainingDistance: row.remainingDistance ? Number(row.remainingDistance) : null,
      isPassede: row.isPassede,
      passedTime: row.passedTime ? Number(row.passedTime) : null,
    });
  }

  // DATA INTEGRITY CHECK: Validate destinations after retrieval
  if (destinations.length > 0) {
    const waypointCount = destinations.filter(d => d.order !== null).length;
    const routeDestinationCount = destinations.filter(d => d.order === null).length;
    const expectedTotal = waypointCount + 1;
    
    // Check for missing destinations
    if (destinations.length !== expectedTotal) {
      console.error(JSON.stringify({
        level: "ERROR",
        event: "TRIP_RETRIEVAL_DESTINATION_COUNT_MISMATCH",
        tripId: id,
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
        }))
      }));
      
      // RECOVERY: Try to fetch missing destinations directly from database
      console.log(JSON.stringify({
        level: "INFO",
        event: "ATTEMPTING_DESTINATION_RECOVERY",
        tripId: id,
        message: "Attempting to recover missing destinations from database"
      }));
      
      // Re-fetch all destination rows without ordering to see if we missed any
      const allDestinationRows = await db
        .select()
        .from(tripDestinations)
        .where(eq(tripDestinations.tripId, id));
      
      console.log(JSON.stringify({
        level: "DEBUG",
        event: "RECOVERY_ALL_DESTINATION_ROWS",
        tripId: id,
        allRowsCount: allDestinationRows.length,
        allRows: allDestinationRows.map(row => ({
          id: row.id,
          tripId: row.tripId,
          locationId: row.locationId,
          order: row.order,
          index: row.index,
        }))
      }));
      
      // If we found more rows, rebuild destinations
      if (allDestinationRows.length > destinationRows.length) {
        console.log(JSON.stringify({
          level: "INFO",
          event: "DESTINATION_RECOVERY_SUCCESS",
          tripId: id,
          originalCount: destinationRows.length,
          recoveredCount: allDestinationRows.length,
          message: "Recovered missing destinations, rebuilding destination list"
        }));
        
        // Rebuild destinations with all rows
        const recoveredLocationIds = allDestinationRows.map(row => row.locationId);
        const recoveredLocations = recoveredLocationIds.length > 0 ? await getTripLocationByIds(recoveredLocationIds) : [];
        const recoveredLocationMap = new Map(recoveredLocations.map((loc: TripLocation) => [loc.id, loc]));
        
        const recoveredDestinations: Destination[] = [];
        for (const row of allDestinationRows) {
          const location = recoveredLocationMap.get(row.locationId);
          if (!location) {
            console.error(JSON.stringify({
              level: "ERROR",
              event: "RECOVERED_DESTINATION_LOCATION_NOT_FOUND",
              tripId: id,
              destinationId: row.id,
              locationId: row.locationId,
            }));
            continue;
          }
          recoveredDestinations.push({
            id: row.locationId,
            locationId: row.locationId,
            lat: location.lat,
            lng: location.lng,
            addres: location.addres,
            order: row.order,
            index: row.index,
            fare: parseFloat(row.fare),
            remainingDistance: row.remainingDistance ? Number(row.remainingDistance) : null,
            isPassede: row.isPassede,
            passedTime: row.passedTime ? Number(row.passedTime) : null,
          });
        }
        
        // Replace destinations with recovered ones
        destinations = recoveredDestinations;
      }
    }
    
    // Check for index gaps
    const indices = destinations.map(d => d.index).sort((a, b) => a - b);
    const hasGap = indices.some((expectedIndex, actualIndex) => expectedIndex !== actualIndex);
    if (hasGap) {
      // Use the first destination's locationId for the row ID
      const firstDestination = destinations[0];
      if (!firstDestination) {
        console.error(JSON.stringify({
          level: "ERROR",
          event: "TRIP_RETRIEVAL_INDEX_GAP",
          tripId: id,
          error: "No destinations available to build destinationRowId",
          expectedIndices: indices.map((_, i) => i),
          actualIndices: indices,
        }));
        return null;
      }
      if (!firstDestination.locationId) {
        console.error(JSON.stringify({
          level: "ERROR",
          event: "TRIP_RETRIEVAL_INDEX_GAP",
          tripId: id,
          error: "First destination has no locationId",
          expectedIndices: indices.map((_, i) => i),
          actualIndices: indices,
        }));
        return null;
      }
      const destinationRowId = buildDestinationRowId(id, firstDestination.locationId);
      console.error(JSON.stringify({
        level: "ERROR",
        event: "TRIP_RETRIEVAL_INDEX_GAP",
        tripId: id,
        expectedIndices: indices.map((_, i) => i),
        destinationRowId,
        actualIndices: indices,
        destinations: destinations.map(d => ({
          id: d.id,
          locationId: d.locationId,
          order: d.order,
          addres: d.addres,
        })),
      }));
    }
  }

  console.log(JSON.stringify({
    level: "DEBUG",
    event: "TRIP_RETRIEVED",
    tripId: id,
    destinationsCount: destinations.length,
    destinations: destinations.map(d => ({
      id: d.id,
      index: d.index,
      lat: d.lat,
      lng: d.lng,
    })),
  }));

  const createdAtMs = tripRow.createdAt ? tripRow.createdAt.getTime() : 0;
  const updatedAtMs = tripRow.updatedAt ? tripRow.updatedAt.getTime() : 0;

  return {
    id: tripRow.id,
    carDriver: assignment,
    origin: {
      id: originLocation.id,
      addres: originLocation.address,
      lat: originLocation.latitude,
      lng: originLocation.longitude,
    },
    destinations,
    status: tripRow.status,
    totalDistance: Number(tripRow.totalDistance),
    createdAt: createdAtMs,
    updatedAt: updatedAtMs,
  };
}

async function getAssignmentIdsByCar(carId: string): Promise<number[]> {
  const rows = await db
    .select({ id: driverCarAssignments.id })
    .from(driverCarAssignments)
    .where(eq(driverCarAssignments.carId, carId));
  return rows.map((row) => Number(row.id));
}

async function getAssignmentIdsByDriver(driverId: string): Promise<number[]> {
  const rows = await db
    .select({ id: driverCarAssignments.id })
    .from(driverCarAssignments)
    .where(eq(driverCarAssignments.driverId, driverId));
  return rows.map((row) => Number(row.id));
}

async function hydrateTrips(tripRows: InferModel<typeof trips>[]): Promise<Trip[]> {
  const resolved = await Promise.all(tripRows.map((row) => getTripById(row.id)));
  return resolved.filter((trip): trip is Trip => trip !== null);
}

export async function getTripsByCarId(carId: string): Promise<Trip[]> {
  const assignmentIds = await getAssignmentIdsByCar(carId);
  if (assignmentIds.length === 0) {
    return [];
  }

  const rows = await fetchTripsForAssignments(assignmentIds);

  return hydrateTrips(rows);
}

export async function getTripsByDriverId(driverId: string): Promise<Trip[]> {
  const assignmentIds = await getAssignmentIdsByDriver(driverId);
  if (assignmentIds.length === 0) {
    return [];
  }

  const rows = await fetchTripsForAssignments(assignmentIds);

  return hydrateTrips(rows);
}

export async function getLatestTripByCarId(carId: string): Promise<Trip | null> {
  const assignmentIds = await getAssignmentIdsByCar(carId);
  if (assignmentIds.length === 0) {
    return null;
  }

  const rows = await fetchTripsForAssignments(assignmentIds);
  const latestRow = rows[0];
  if (!latestRow) {
    return null;
  }

  return getTripById(latestRow.id);
}

export async function getActiveTripByCarId(carId: string): Promise<Trip | null> {
  const assignmentIds = await getAssignmentIdsByCar(carId);
  if (assignmentIds.length === 0) {
    return null;
  }

  const rows = await fetchTripsForAssignments(assignmentIds);
  // Find the first active trip (scheduled or in_progress)
  const activeRow = rows.find(row => row.status === 'scheduled' || row.status === 'in_progress');
  if (!activeRow) {
    return null;
  }

  return getTripById(activeRow.id);
}

export async function getLatestTripByDriverId(driverId: string): Promise<Trip | null> {
  const assignmentIds = await getAssignmentIdsByDriver(driverId);
  if (assignmentIds.length === 0) {
    return null;
  }

  const rows = await fetchTripsForAssignments(assignmentIds);
  const latestRow = rows[0];
  if (!latestRow) {
    return null;
  }

  return getTripById(latestRow.id);
}

export async function getActiveTripByDriverId(driverId: string): Promise<Trip | null> {
  const assignmentIds = await getAssignmentIdsByDriver(driverId);
  if (assignmentIds.length === 0) {
    return null;
  }

  const rows = await fetchTripsForAssignments(assignmentIds);
  // Find the first active trip (scheduled or in_progress)
  const activeRow = rows.find(row => row.status === 'scheduled' || row.status === 'in_progress');
  if (!activeRow) {
    return null;
  }

  return getTripById(activeRow.id);
}

export async function getTripsByCompanyId(companyId: string): Promise<Trip[]> {
  // Get all cars for the company
  const companyCars = await db
    .select({ id: cars.id })
    .from(cars)
    .where(eq(cars.companyId, companyId));
  
  const carIds = companyCars.map(c => c.id);
  
  if (carIds.length === 0) {
    return [];
  }
  
  // Get all assignment IDs for these cars
  const assignments = await db
    .select({ id: driverCarAssignments.id })
    .from(driverCarAssignments)
    .where(inArray(driverCarAssignments.carId, carIds));
  
  const assignmentIds = assignments.map(a => Number(a.id));
  
  if (assignmentIds.length === 0) {
    return [];
  }
  
  const rows = await fetchTripsForAssignments(assignmentIds);
  return hydrateTrips(rows);
}

export async function getActiveTripsByCompanyId(companyId: string): Promise<Trip[]> {
  // Get all cars for the company
  const companyCars = await db
    .select({ id: cars.id })
    .from(cars)
    .where(eq(cars.companyId, companyId));
  
  const carIds = companyCars.map(c => c.id);
  
  if (carIds.length === 0) {
    return [];
  }
  
  // Get all assignment IDs for these cars
  const assignments = await db
    .select({ id: driverCarAssignments.id })
    .from(driverCarAssignments)
    .where(inArray(driverCarAssignments.carId, carIds));
  
  const assignmentIds = assignments.map(a => Number(a.id));
  
  if (assignmentIds.length === 0) {
    return [];
  }
  
  // Fetch trips but filter for only scheduled and in_progress status
  const rowsByAssignment = await Promise.all(
    assignmentIds.map((assignmentId) =>
      db
        .select()
        .from(trips)
        .where(
          and(
            eq(trips.driverCarAssignmentId, assignmentId),
            or(
              eq(trips.status, "scheduled"),
              eq(trips.status, "in_progress")
            )
          )
        ),
    ),
  );

  const rows = rowsByAssignment
    .flat()
    .sort((a, b) => {
      const bUpdated = b.updatedAt?.getTime() ?? 0;
      const aUpdated = a.updatedAt?.getTime() ?? 0;
      return bUpdated - aUpdated;
    });

  return hydrateTrips(rows);
}

export async function getLatestTripUpdatedAt(): Promise<string | null> {
  const result = await db
    .select({ maxUpdatedAt: max(trips.updatedAt) })
    .from(trips);
  
  const maxDate = result[0]?.maxUpdatedAt;
  if (!maxDate) {
    return null;
  }
  return maxDate.toISOString();
}

