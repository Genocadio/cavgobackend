import type { TripSnapshot, SnapshotCapacity, SnapshotLocation, SnapshotSummary, Trip } from "../types";
import * as tripRepository from "./trips";
import { pgPool } from "../db/client";

// Ensure newly added financial totals always exist to avoid undefined values on older snapshots
// Also ensure addresses are always populated to satisfy non-nullable GraphQL schema
// Falls back to trip location data if address is missing from snapshot
async function normalizeSnapshot(snapshot: TripSnapshot, trip?: Trip | null): Promise<TripSnapshot> {
  const capacity: SnapshotCapacity = {
    ...snapshot.capacity,
    totalAmountPaid: snapshot.capacity.totalAmountPaid ?? 0,
    totalAmountPending: snapshot.capacity.totalAmountPending ?? 0,
  };

  // If any location is missing address, fetch the trip to get addresses
  const needsTripData =
    !trip &&
    (snapshot.locations || []).some((loc) => !loc.addres);

  if (needsTripData) {
    const fetchedTrip = await tripRepository.getTripById(snapshot.tripId);
    if (fetchedTrip) {
      trip = fetchedTrip;
    }
  }

  const locations: SnapshotLocation[] = await Promise.all(
    (snapshot.locations || []).map(async (location) => {
      let address = location.addres || "";

      // If address is still missing, try to find it from trip data
      if (!address && trip) {
        if (location.order === 0) {
          // Origin
          address = trip.origin?.addres || "";
        } else {
          // Destination or waypoint
          // First try to match by destination.id (in case it matches)
          let destination = trip.destinations?.find((d) => String(d.id) === String(location.locationId));
          
          // If not found, the locationId in snapshot refers to the trip_locations table id
          // We need to query trip_destinations to find which destination references this locationId
          if (!destination) {
            try {
              const { pgPool } = await import("../db/client");
              const result = await pgPool.query(
                `SELECT td.id, tl.address, tl.latitude, tl.longitude
                 FROM trip_destinations td
                 JOIN trip_locations tl ON td.location_id = tl.id
                 WHERE td.trip_id = $1 AND td.location_id = $2
                 LIMIT 1`,
                [snapshot.tripId, String(location.locationId)]
              );
              
              if (result.rows.length > 0) {
                address = result.rows[0].address || "";
                console.log(JSON.stringify({
                  level: "DEBUG",
                  event: "SNAPSHOT_ADDRESS_RESOLVED",
                  tripId: snapshot.tripId,
                  locationId: location.locationId,
                  destinationId: result.rows[0].id,
                  address,
                }));
              } else {
                console.log(JSON.stringify({
                  level: "WARN",
                  event: "SNAPSHOT_ADDRESS_NOT_FOUND",
                  tripId: snapshot.tripId,
                  locationId: location.locationId,
                  order: location.order,
                }));
              }
            } catch (error) {
              console.error(JSON.stringify({
                level: "ERROR",
                event: "SNAPSHOT_ADDRESS_LOOKUP_FAILED",
                tripId: snapshot.tripId,
                locationId: location.locationId,
                error: error instanceof Error ? error.message : String(error),
              }));
            }
          } else {
            address = destination.addres || "";
          }
        }
      }

      return {
        ...location,
        addres: address,
        seats: {
          ...location.seats,
          totalAmountPaid: location.seats.totalAmountPaid ?? 0,
          totalAmountPending: location.seats.totalAmountPending ?? 0,
        },
      };
    })
  );

  return {
    ...snapshot,
    capacity,
    locations,
  };
}

/**
 * In-memory storage for trip snapshots
 * Maps tripId -> TripSnapshot
 */
const snapshotStore = new Map<string, TripSnapshot>();

/**
 * Create an initial snapshot for a trip based on the car capacity
 * All seats are available initially, no bookings yet
 * @param trip The trip to create a snapshot for
 * @param carCapacity The car's seating capacity
 * @returns The newly created snapshot
 */
export async function createInitialSnapshot(trip: Trip, carCapacity: number): Promise<TripSnapshot> {
  // If a snapshot already exists for this trip (e.g., persisted from bookings), don't overwrite it
  const exists = await snapshotExists(trip.id);
  if (exists) {
    const existing = await getSnapshot(trip.id);
    if (existing) return existing;
  }
  // Initialize capacity with all seats available
  const capacity: SnapshotCapacity = {
    totalSeats: carCapacity,
    availableSeats: carCapacity,
    occupiedSeats: 0,
    pendingPaymentSeats: 0,
    totalAmountPaid: 0,
    totalAmountPending: 0,
  };

  // Initialize locations from trip destinations and origin
  const locations: SnapshotLocation[] = [];

  // Add origin as first location
  locations.push({
    locationId: trip.origin.id,
    addres: trip.origin.addres || "",
    type: "ORIGIN",
    order: 0,
    status: "UPCOMING",
    seats: {
      pickup: 0,
      dropoff: 0,
      pendingPayment: 0,
      availableFromHere: carCapacity,
      totalAmountPaid: 0,
      totalAmountPending: 0,
    },
  });

  // Add destinations (waypoints and final destination)
  trip.destinations.forEach((dest, index) => {
    const locationType = index === trip.destinations.length - 1 ? "DESTINATION" : "WAYPOINT";
    // Use locationId if available (for waypoints), otherwise use id
    const locationRef = dest.locationId || dest.id;
    locations.push({
      locationId: locationRef,
      addres: dest.addres || "",
      type: locationType,
      order: index + 1,
      status: "UPCOMING",
      seats: {
        pickup: 0,
        dropoff: 0,
        pendingPayment: 0,
        availableFromHere: carCapacity,
        totalAmountPaid: 0,
        totalAmountPending: 0,
      },
    });
  });

  // Initialize summary with all zeros
  const summary: SnapshotSummary = {
    totalTickets: 0,
    paidTickets: 0,
    pendingPayments: 0,
    completedDropoffs: 0,
  };

  // Create the snapshot
  const snapshot: TripSnapshot = {
    tripId: trip.id,
    tripStatus: trip.status.toUpperCase() as any,
    lastUpdated: new Date().toISOString(),
    capacity,
    locations,
    summary,
  };

  // Store it
  await upsertSnapshot(snapshot);

  return snapshot;
}

/**
 * Store or update a trip snapshot
 * @param snapshot The snapshot to store
 */
export async function upsertSnapshot(snapshot: TripSnapshot): Promise<void> {
  // Preserve existing addresses when incoming snapshot omits them.
  const key = String(snapshot.tripId);
  const existing = snapshotStore.get(key) || null;

  const mergedLocations = (snapshot.locations || []).map((loc) => {
    const existingLoc = existing?.locations?.find((l) => String(l.locationId) === String(loc.locationId));
    return {
      ...loc,
      addres: loc.addres || existingLoc?.addres || "",
    };
  });

  const mergedSnapshot: TripSnapshot = {
    ...snapshot,
    locations: mergedLocations,
  };

  const normalized = await normalizeSnapshot(mergedSnapshot);

  // Persist to DB so snapshots survive restarts
  try {
    await pgPool.query(
      `INSERT INTO trip_snapshots (trip_id, snapshot, updated_at) VALUES ($1, $2, NOW())
       ON CONFLICT (trip_id) DO UPDATE SET snapshot = EXCLUDED.snapshot, updated_at = NOW()`,
      [key, JSON.stringify(normalized)]
    );
  } catch (err) {
    console.error("Failed to persist snapshot to DB:", err);
  }

  snapshotStore.set(key, normalized);
}

/**
 * Get a snapshot for a specific trip
 * @param tripId The trip ID
 * @returns The snapshot or null if not found
 */
export async function getSnapshot(tripId: string): Promise<TripSnapshot | null> {
  const snapshot = snapshotStore.get(String(tripId));
  return snapshot ? await normalizeSnapshot(snapshot) : null;
}

/**
 * Get all snapshots
 * @returns Array of all snapshots
 */
export async function getAllSnapshots(): Promise<TripSnapshot[]> {
  return Promise.all(Array.from(snapshotStore.values()).map((snap) => normalizeSnapshot(snap)));
}

/**
 * Delete a snapshot
 * @param tripId The trip ID
 */
export async function deleteSnapshot(tripId: string): Promise<void> {
  snapshotStore.delete(String(tripId));
}

/**
 * Check if a snapshot exists for a trip
 * @param tripId The trip ID
 * @returns true if snapshot exists
 */
export async function snapshotExists(tripId: string): Promise<boolean> {
  return snapshotStore.has(String(tripId));
}

/**
 * Load all snapshots from the database into the in-memory store.
 * Call this once at startup after migrations.
 */
export async function loadAllSnapshots(): Promise<void> {
  try {
    const res = await pgPool.query(`SELECT trip_id, snapshot FROM trip_snapshots`);
    for (const row of res.rows) {
      try {
        const snap: TripSnapshot = row.snapshot as TripSnapshot;
        const key = String(row.trip_id);
        const normalized = await normalizeSnapshot(snap);
        snapshotStore.set(key, normalized);
      } catch (err) {
        console.error("Failed to load snapshot row:", err, row.trip_id);
      }
    }
  } catch (err) {
    console.error("Failed to load snapshots from DB:", err);
  }
}

/**
 * Clear all snapshots (useful for testing/cleanup)
 */
export async function clearAllSnapshots(): Promise<void> {
  snapshotStore.clear();
}
