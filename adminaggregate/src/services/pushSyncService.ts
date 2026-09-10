import type { Car, CurreLocation, Driver, Status, VehicleStatus } from "../types";
import * as carRepository from "../repositories/cars";
import * as driverRepository from "../repositories/drivers";
import * as assignmentRepository from "../repositories/assignments";

/**
 * Intake for data pushed from the main API (cavgomain).
 *
 * The main API triggers a POST to `{AGGREGATOR_BASE_URL}/company/{companyId}/vehicle`
 * and `{AGGREGATOR_BASE_URL}/company/{companyId}/worker` whenever the company's
 * vehicles/workers change (see AggregatorSyncService and AGGREGATOR_JSON_FORMAT.md).
 * These handlers upsert the same entities the pull-sync writes, so either path keeps
 * the aggregator's local store consistent.
 */

export interface InternalVehicleLocation {
  latitude?: number | null;
  longitude?: number | null;
  address?: string | null;
  timestamp?: string | null;
  bearing?: number | null;
  speed?: number | null;
}

export interface InternalVehicle {
  id?: string | number | null;
  companyId?: string | number | null;
  companyCode?: string | null;
  plate?: string | null;
  model?: string | null;
  make?: string | null;
  capacity?: number | null;
  connectionStatus?: string | null;
  operationalStatus?: string | null;
  currentLocation?: InternalVehicleLocation | null;
  lastUpdated?: string | null;
}

export interface InternalWorker {
  id?: string | number | null;
  name?: string | null;
  phone?: string | null;
  email?: string | null;
  licenseNumber?: string | null;
  status?: string | null;
  role?: string | null;
  vehicle?: InternalVehicle | null;
}

const VEHICLE_STATUSES: ReadonlySet<string> = new Set([
  "AVAILABLE",
  "MAINTENANCE",
  "OUT_OF_SERVICE",
  "OCCUPIED",
]);

const DRIVER_STATUSES: ReadonlySet<string> = new Set([
  "ACTIVE",
  "INACTIVE",
  "SUSPENDED",
  "PENDING_VERIFICATION",
]);

function parseTimestamp(value: string | null | undefined): number | null {
  if (!value) {
    return null;
  }
  const time = new Date(value).getTime();
  return Number.isFinite(time) ? time : null;
}

function toCurrentLocation(location: InternalVehicleLocation | null | undefined): CurreLocation | null {
  if (!location || location.latitude == null || location.longitude == null) {
    return null;
  }
  const timestamp = parseTimestamp(location.timestamp) ?? Date.now();
  return {
    location: { lat: location.latitude, lng: location.longitude },
    speed: location.speed ?? 0,
    bearing: location.bearing ?? 0,
    timestamp,
  };
}

export function mapInternalVehicleToCar(vehicle: InternalVehicle, fallbackCompanyId: string): Car {
  if (vehicle.id == null) {
    throw new Error("Vehicle mapper: id is required");
  }
  if (vehicle.plate == null) {
    throw new Error(`Vehicle mapper: plate is required for vehicle ${vehicle.id}`);
  }
  if (vehicle.model == null) {
    throw new Error(`Vehicle mapper: model is required for vehicle ${vehicle.id}`);
  }
  if (vehicle.capacity == null || !Number.isFinite(Number(vehicle.capacity))) {
    throw new Error(`Vehicle mapper: capacity is required for vehicle ${vehicle.id}`);
  }
  if (!vehicle.operationalStatus || !VEHICLE_STATUSES.has(vehicle.operationalStatus)) {
    throw new Error(`Vehicle mapper: operationalStatus is required for vehicle ${vehicle.id}`);
  }

  return {
    id: String(vehicle.id),
    plate: vehicle.plate,
    make: vehicle.make ?? "",
    model: vehicle.model,
    vehicleType: null,
    capacity: Number(vehicle.capacity),
    status: vehicle.operationalStatus as VehicleStatus,
    isOnline: vehicle.connectionStatus === "ONLINE",
    currentLocation: toCurrentLocation(vehicle.currentLocation),
    companyId: vehicle.companyId != null ? String(vehicle.companyId) : fallbackCompanyId,
    createdAt: null,
    updatedAt: vehicle.lastUpdated ?? null,
  };
}

export function mapInternalWorkerToDriver(worker: InternalWorker, companyId: string): Driver {
  if (worker.id == null) {
    throw new Error("Worker mapper: id is required");
  }
  if (!worker.status || !DRIVER_STATUSES.has(worker.status)) {
    throw new Error(`Worker mapper: status is required for worker ${worker.id}`);
  }

  const nameParts = (worker.name ?? "").trim().split(/\s+/).filter(Boolean);

  return {
    id: String(worker.id),
    firstName: nameParts[0] ?? "Unknown",
    lastName: nameParts.slice(1).join(" ") || "Driver",
    phoneNumber: worker.phone?.trim() || `unknown-${worker.id}`,
    email: worker.email?.trim() || `driver-${worker.id}@missing.local`,
    status: worker.status as Status,
    companyId,
    dateOfBirth: null,
    address: null,
    licenseNumber: worker.licenseNumber ?? null,
    licenseExpiry: null,
    role: worker.role ?? null,
    createdAt: null,
    updatedAt: null,
  };
}

async function upsertCar(car: Car): Promise<void> {
  const existing = await carRepository.getCarById(car.id);
  if (existing) {
    await carRepository.updateCar(car);
  } else {
    await carRepository.createCar(car);
  }
}

async function upsertDriver(driver: Driver): Promise<void> {
  const existing = await driverRepository.getDriverById(driver.id);
  if (existing) {
    await driverRepository.updateDriver(driver);
  } else {
    await driverRepository.createDriver(driver);
  }
}

export async function syncVehiclesFromInternal(
  companyId: string,
  vehicles: InternalVehicle[]
): Promise<{ synced: number; skipped: number }> {
  let synced = 0;
  let skipped = 0;

  for (const vehicle of vehicles) {
    try {
      const car = mapInternalVehicleToCar(vehicle, companyId);
      await upsertCar(car);
      synced++;
    } catch (error) {
      console.warn(`[PUSH SYNC] Skipping vehicle ${vehicle?.id ?? "unknown"} for company ${companyId}:`, error);
      skipped++;
    }
  }

  console.log(`[PUSH SYNC] Synced ${synced} vehicles for company ${companyId} (${skipped} skipped)`);
  return { synced, skipped };
}

export async function syncWorkersFromInternal(
  companyId: string,
  workers: InternalWorker[]
): Promise<{ synced: number; skipped: number }> {
  let synced = 0;
  let skipped = 0;

  for (const worker of workers) {
    try {
      if (worker.role !== "DRIVER") {
        console.log(`[PUSH SYNC] Skipping non-driver worker ${worker?.id ?? "unknown"} (role: ${worker?.role ?? "unknown"})`);
        skipped++;
        continue;
      }

      const driver = mapInternalWorkerToDriver(worker, companyId);
      await upsertDriver(driver);

      if (worker.vehicle) {
        const car = mapInternalVehicleToCar(worker.vehicle, companyId);
        await upsertCar(car);
        await assignmentRepository.ensureDriverCarAssignment(driver.id, car.id);
      } else {
        await assignmentRepository.removeAssignmentByDriverId(driver.id);
      }

      synced++;
    } catch (error) {
      console.warn(`[PUSH SYNC] Skipping worker ${worker?.id ?? "unknown"} for company ${companyId}:`, error);
      skipped++;
    }
  }

  console.log(`[PUSH SYNC] Synced ${synced} workers for company ${companyId} (${skipped} skipped)`);
  return { synced, skipped };
}