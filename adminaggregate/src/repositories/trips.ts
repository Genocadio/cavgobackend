import { and, eq, inArray, max, not, or } from "drizzle-orm";
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
  id: row.id,
  addres: location.addres,
  lat: location.lat,
  lng: location.lng,
  index: row.index,
  fare: Number(row.fare),
  remainingDistance: row.remainingDistance == null ? null : Number(row.remainingDistance),
  isPassede: row.isPassede,
  passedTime: row.passedTime == null ? null : Number(row.passedTime),
});

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
    await db
      .delete(tripDestinations)
      .where(
        and(
          eq(tripDestinations.tripId, trip.id),
          not(inArray(tripDestinations.id, trip.destinations.map((d) => `${trip.id}-${d.id}`))),
        ),
      );
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
    .orderBy(tripDestinations.index);

  console.log(JSON.stringify({
    level: "DEBUG",
    event: "GETTING_TRIP_DESTINATIONS",
    tripId: id,
    destinationRowsCount: destinationRows.length,
    destinationRows: destinationRows.map(row => ({
      id: row.id,
      tripId: row.tripId,
      locationId: row.locationId,
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

