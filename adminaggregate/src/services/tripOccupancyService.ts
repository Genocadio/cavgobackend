import type { ActiveTripRow } from "../repositories/trips";
import * as carRepository from "../repositories/cars";
import * as snapshotRepository from "../repositories/snapshots";
import * as tripRepository from "../repositories/trips";
import { pubsub, TRIGGERS } from "./pubsub";

type TerminalStatus = "cancelled" | "completed";
type TerminalPayload = {
  id?: number | string | undefined;
  vehicle_id?: number | string | undefined;
};

function unique(values: Array<string | null | undefined>): string[] {
  return [...new Set(values.filter((value): value is string => value != null && value !== ""))];
}

export async function refreshCompanyActiveTrips(companyId: string): Promise<void> {
  const activeTrips = await tripRepository.getActiveTripsByCompanyId(companyId);
  pubsub.publish(TRIGGERS.COMPANY_TRIPS_UPDATED(companyId), { activeCompanyTrips: activeTrips });
}

export async function syncCarOccupancy(carId: string): Promise<void> {
  const car = await carRepository.getCarById(carId);
  if (!car || car.status !== "OCCUPIED") return;

  const activeTrip = await tripRepository.getActiveTripByCarId(carId);
  if (activeTrip) return;

  await carRepository.updateCar({ ...car, status: "AVAILABLE", updatedAt: new Date().toISOString() });
  console.log(
    JSON.stringify({
      level: "INFO",
      event: "CAR_OCCUPANCY_CLEARED",
      carId,
      reason: "no_active_trips",
    }),
  );
}

/**
 * Close local trip projections from an id-only payload. This runs before the
 * strict trip mapper so terminal events cannot be lost when upstream sends a
 * thin payload (which is exactly what cavgotrips publishes for `deleted`).
 */
export async function closeTrips(
  rows: ActiveTripRow[],
  status: TerminalStatus,
  reason: string,
): Promise<void> {
  if (rows.length === 0) return;

  const ids = unique(rows.map((row) => row.id));
  await tripRepository.markTripsTerminal(ids, status);
  await Promise.all(ids.map((id) => snapshotRepository.deleteSnapshot(id)));

  await Promise.all([
    ...unique(rows.map((row) => row.carId)).map((carId) => syncCarOccupancy(carId)),
    ...unique(rows.map((row) => row.companyId)).map((companyId) => refreshCompanyActiveTrips(companyId)),
  ]);

  for (const id of ids) {
    const trip = await tripRepository.getTripById(id);
    pubsub.publish(TRIGGERS.TRIP_UPDATED(id), { trip });
  }

  console.log(
    JSON.stringify({
      level: "INFO",
      event: "ACTIVE_TRIP_PROJECTION_CLOSED",
      reason,
      status,
      tripIds: ids,
    }),
  );
}

export async function closeTripsForCar(carId: string, reason: string): Promise<void> {
  const rows = await tripRepository.getActiveTripRowsForCar(carId);
  await closeTrips(rows, "cancelled", reason);
}

export async function closeTripsForDriver(driverId: string, reason: string): Promise<void> {
  const rows = await tripRepository.getActiveTripRowsForDriver(driverId);
  await closeTrips(rows, "cancelled", reason);
}

export async function finalizeTerminalTrip(
  event: string,
  payload: TerminalPayload,
): Promise<void> {
  const status: TerminalStatus = event === "completed" ? "completed" : "cancelled";
  const reason = `trip_service_${event}`;
  const tripId = payload.id == null ? null : String(payload.id);

  if (tripId) {
    const rows = await tripRepository.getActiveTripRowsByIds([tripId]);
    if (rows.length > 0) {
      await closeTrips(rows, status, reason);
      return;
    }

    // The local row may already be terminal but its snapshot/subscription was
    // missed. Clean those projections too.
    await snapshotRepository.deleteSnapshot(tripId);
    const trip = await tripRepository.getTripById(tripId);
    if (trip) {
      await syncCarOccupancy(trip.carDriver.car.id);
      await refreshCompanyActiveTrips(trip.carDriver.car.companyId);
      pubsub.publish(TRIGGERS.TRIP_UPDATED(tripId), { trip });
    } else {
      pubsub.publish(TRIGGERS.TRIP_UPDATED(tripId), { trip: null });
    }
    return;
  }

  const carId = payload.vehicle_id == null ? null : String(payload.vehicle_id);
  if (carId) {
    await closeTripsForCar(carId, reason);
  }
}
