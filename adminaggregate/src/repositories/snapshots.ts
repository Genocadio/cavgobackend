import type { TripSnapshot, SnapshotCapacity, SnapshotLocation, SnapshotSummary, Trip } from "../types";

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
  // Initialize capacity with all seats available
  const capacity: SnapshotCapacity = {
    totalSeats: carCapacity,
    availableSeats: carCapacity,
    occupiedSeats: 0,
    pendingPaymentSeats: 0,
  };

  // Initialize locations from trip destinations and origin
  const locations: SnapshotLocation[] = [];

  // Add origin as first location
  locations.push({
    locationId: trip.origin.id,
    type: "ORIGIN",
    order: 0,
    status: "UPCOMING",
    seats: {
      pickup: 0,
      dropoff: 0,
      pendingPayment: 0,
      availableFromHere: carCapacity,
    },
  });

  // Add destinations (waypoints and final destination)
  trip.destinations.forEach((dest, index) => {
    const locationType = index === trip.destinations.length - 1 ? "DESTINATION" : "WAYPOINT";
    locations.push({
      locationId: dest.id,
      type: locationType,
      order: index + 1,
      status: "UPCOMING",
      seats: {
        pickup: 0,
        dropoff: 0,
        pendingPayment: 0,
        availableFromHere: carCapacity,
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
  snapshotStore.set(snapshot.tripId, snapshot);
}

/**
 * Get a snapshot for a specific trip
 * @param tripId The trip ID
 * @returns The snapshot or null if not found
 */
export async function getSnapshot(tripId: string): Promise<TripSnapshot | null> {
  return snapshotStore.get(tripId) || null;
}

/**
 * Get all snapshots
 * @returns Array of all snapshots
 */
export async function getAllSnapshots(): Promise<TripSnapshot[]> {
  return Array.from(snapshotStore.values());
}

/**
 * Delete a snapshot
 * @param tripId The trip ID
 */
export async function deleteSnapshot(tripId: string): Promise<void> {
  snapshotStore.delete(tripId);
}

/**
 * Check if a snapshot exists for a trip
 * @param tripId The trip ID
 * @returns true if snapshot exists
 */
export async function snapshotExists(tripId: string): Promise<boolean> {
  return snapshotStore.has(tripId);
}

/**
 * Clear all snapshots (useful for testing/cleanup)
 */
export async function clearAllSnapshots(): Promise<void> {
  snapshotStore.clear();
}
