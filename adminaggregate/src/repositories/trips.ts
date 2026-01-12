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
import { upsertTripLocation } from "./locations";
import type { Destination, Trip, TripLocation } from "../types";

type TripDestinationRow = InferModel<typeof tripDestinations>;

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
  // Stored trip destination id is stored as `${tripId}-${locationId}` in DB.
  // For internal Trip model we strip the trip prefix so `destination.id` is the original location id.
  id: row.id && row.id.startsWith(`${row.tripId}-`) ? row.id.replace(`${row.tripId}-`, '') : row.id,
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

export async function createTrip(trip: Trip): Promise<Trip> {
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
  
  await upsertTripLocation(trip.origin);
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

  const savedDestinations = await Promise.all(
    trip.destinations.map(async (destination) => {
      await upsertTripLocation(destination);
      const destinationId = `${trip.id}-${destination.id}`;
      await db
        .insert(tripDestinations)
        .values({
          id: destinationId,
          tripId: trip.id,
          locationId: destination.id,
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
            locationId: destination.id,
          },
        });
      return destinationId;
    }),
  );

  // Verify destination rows were persisted; reinsert any missing destination rows
  try {
    const dbRows = await db
      .select({ id: tripDestinations.id })
      .from(tripDestinations)
      .where(eq(tripDestinations.tripId, trip.id));
    const existingIds = dbRows.map(r => r.id);
    const missing = savedDestinations.filter(id => !existingIds.includes(id));
    if (missing.length > 0) {
      console.warn(JSON.stringify({ level: "WARN", event: "MISSING_DESTINATIONS_AFTER_CREATE", tripId: trip.id, missing }));
      for (const id of missing) {
        // Recreate minimal destination row from in-memory trip.destinations
        const parts = id.split("-");
        const locationId = parts.slice(1).join("-");
        const dest = trip.destinations.find(d => String(d.id) === locationId);
        if (dest) {
          await db.insert(tripDestinations).values({
            id,
            tripId: trip.id,
            locationId: dest.id,
            index: dest.index,
            fare: dest.fare.toString(),
            remainingDistance: dest.remainingDistance ?? null,
            isPassede: dest.isPassede,
            passedTime: dest.passedTime ?? null,
          }).onConflictDoNothing();
        }
      }
    }
  } catch (err) {
    console.error(JSON.stringify({ level: "ERROR", event: "DESTINATION_VERIFICATION_FAILED", tripId: trip.id, error: err instanceof Error ? err.message : String(err) }));
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

export async function updateTrip(trip: Trip): Promise<Trip> {
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
  
  await upsertTripLocation(trip.origin);
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
  if (trip.destinations.length > 0) {
    const keepIds = trip.destinations.map((d) => `${trip.id}-${d.id}`);
    try {
      const existing = await db.select({ id: tripDestinations.id }).from(tripDestinations).where(eq(tripDestinations.tripId, trip.id));
      const existingIds = existing.map(r => r.id);
      const toDelete = existingIds.filter(id => !keepIds.includes(id));
      console.log(JSON.stringify({ level: "DEBUG", event: "DESTINATIONS_PRUNE", tripId: trip.id, keepIds, existingIds, toDelete }));
      if (toDelete.length > 0) {
        await db
          .delete(tripDestinations)
          .where(
            and(
              eq(tripDestinations.tripId, trip.id),
              not(inArray(tripDestinations.id, keepIds)),
            ),
          );
      }
    } catch (err) {
      console.error(JSON.stringify({ level: "ERROR", event: "DESTINATION_PRUNE_FAILED", tripId: trip.id, error: err instanceof Error ? err.message : String(err) }));
    }
  } else {
    await db.delete(tripDestinations).where(eq(tripDestinations.tripId, trip.id));
  }

  const savedDestinations = await Promise.all(
    trip.destinations.map(async (destination) => {
      await upsertTripLocation(destination);
      const destinationId = `${trip.id}-${destination.id}`;
      await db
        .insert(tripDestinations)
        .values({
          id: destinationId,
          tripId: trip.id,
          locationId: destination.id,
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
            locationId: destination.id,
          },
        });
      return destinationId;
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

  const locationRows =
    locationIds.length > 0
      ? await db
          .select()
          .from(tripLocations)
          .where(inArray(tripLocations.id, locationIds))
      : [];

  console.log(JSON.stringify({
    level: "DEBUG",
    event: "FETCHED_LOCATIONS_FOR_DESTINATIONS",
    tripId: id,
    locationIds,
    locationRowsCount: locationRows.length,
    locationRows: locationRows.map(row => ({
      id: row.id,
      address: row.address,
    })),
  }));

  const locationMap = new Map(locationRows.map((row) => [row.id, row]));

  const destinations: Destination[] = [];
  for (const row of destinationRows) {
    try {
      const location = locationMap.get(row.locationId);
      if (!location) {
        console.error(JSON.stringify({
          level: "ERROR",
          event: "MISSING_LOCATION_FOR_DESTINATION",
          tripId: id,
          destinationId: row.id,
          locationId: row.locationId,
          locationIdType: typeof row.locationId,
          availableLocationIds: Array.from(locationMap.keys()),
          locationIdsQueried: locationIds,
        }));
        // Try to fetch the location directly to see if it exists
        const directLocation = await db
          .select()
          .from(tripLocations)
          .where(eq(tripLocations.id, row.locationId));
        console.log(JSON.stringify({
          level: "DEBUG",
          event: "DIRECT_LOCATION_LOOKUP",
          tripId: id,
          locationId: row.locationId,
          found: directLocation.length > 0,
          location: directLocation[0] ? {
            id: directLocation[0].id,
            address: directLocation[0].address,
          } : null,
        }));
        // Skip this destination instead of throwing to see all destinations
        continue;
      }

      destinations.push(destinationFromRow(row, {
        id: location.id,
        addres: location.address,
        lat: location.latitude,
        lng: location.longitude,
      }));
    } catch (error) {
      console.error(JSON.stringify({
        level: "ERROR",
        event: "DESTINATION_MAPPING_ERROR",
        tripId: id,
        destinationId: row.id,
        error: error instanceof Error ? error.message : String(error),
      }));
      // Continue processing other destinations
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

