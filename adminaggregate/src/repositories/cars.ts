import { count, eq, sql, desc, asc } from "drizzle-orm";
import type { InferSelectModel } from "drizzle-orm";
import { db } from "../db/client";
import { cars, driverCarAssignments, trips, tripMetrics } from "../db/schema";
import type { Car, CurreLocation } from "../types";

type CarRow = InferSelectModel<typeof cars>;

const hasLocation = (row: CarRow): row is CarRow & {
  currentLocationLatitude: number;
  currentLocationLongitude: number;
  currentLocationTimestamp: Date;
} =>
  row.currentLocationLatitude != null &&
  row.currentLocationLongitude != null &&
  row.currentLocationTimestamp != null;

const mapCar = (row: CarRow): Car => ({
  id: row.id,
  plate: row.plate,
  make: row.make ?? "",
  model: row.model,
  vehicleType: row.vehicleType ?? null,
  capacity: row.capacity,
  status: row.status as Car["status"],
  isOnline: row.isOnline,
  currentLocation: hasLocation(row)
    ? {
        location: {
          lat: row.currentLocationLatitude,
          lng: row.currentLocationLongitude,
        },
        speed: row.currentLocationSpeed ?? 0,
        bearing: row.currentLocationBearing ?? 0,
        timestamp: row.currentLocationTimestamp.getTime(),
      }
    : null,
  companyId: row.companyId,
  createdAt: row.createdAt ? row.createdAt.toISOString() : null,
  updatedAt: row.updatedAt ? row.updatedAt.toISOString() : null,
});

const mapLocationFields = (location: CurreLocation | null) => ({
  currentLocationLatitude: location?.location.lat ?? null,
  currentLocationLongitude: location?.location.lng ?? null,
  currentLocationSpeed: location?.speed ?? null,
  currentLocationBearing: location?.bearing ?? null,
  currentLocationTimestamp: location ? new Date(location.timestamp) : null,
});

export async function createCar(car: Car): Promise<Car> {
  await db.insert(cars).values({
    id: car.id,
    plate: car.plate,
    make: car.make,
    model: car.model,
    vehicleType: car.vehicleType,
    capacity: car.capacity,
    status: car.status,
    isOnline: car.isOnline,
    companyId: car.companyId,
    createdAt: car.createdAt ? new Date(car.createdAt) : null,
    updatedAt: car.updatedAt ? new Date(car.updatedAt) : null,
    ...mapLocationFields(car.currentLocation),
  });
  return car;
}

export async function updateCar(car: Car): Promise<Car> {
  await db
    .update(cars)
    .set({
      plate: car.plate,
      make: car.make,
      model: car.model,
      vehicleType: car.vehicleType,
      capacity: car.capacity,
      status: car.status,
      isOnline: car.isOnline,
      companyId: car.companyId,
      updatedAt: car.updatedAt ? new Date(car.updatedAt) : null,
      ...mapLocationFields(car.currentLocation),
    })
    .where(eq(cars.id, car.id));

  return car;
}

export async function updateCarLocation(carId: string, currentLocation: CurreLocation | null): Promise<Car> {
  await db
    .update(cars)
    .set(mapLocationFields(currentLocation))
    .where(eq(cars.id, carId));

  const updated = await getCarById(carId);
  if (!updated) {
    throw new Error(`car ${carId} not found after updating location`);
  }

  return updated;
}

export async function deleteCar(id: string): Promise<void> {
  await db.delete(cars).where(eq(cars.id, id));
}

export async function getCarById(id: string): Promise<Car | null> {
  const [car] = await db.select().from(cars).where(eq(cars.id, id));
  return car ? mapCar(car) : null;
}

export async function getCarsByCompany(
  companyId: string,
  limit?: number,
  offset?: number
): Promise<{ items: Car[]; total: number; limit: number; offset: number }> {
  // Get all cars for the company with their trip information
  const carsWithTrips = await db
    .select({
      car: cars,
      tripStatus: trips.status,
      tripUpdatedAt: trips.updatedAt,
      tripMetricsStartedAt: tripMetrics.startedAt,
      tripMetricsCompletedAt: tripMetrics.completedAt,
      tripMetricsUpdatedAt: tripMetrics.updatedAt,
    })
    .from(cars)
    .leftJoin(driverCarAssignments, eq(cars.id, driverCarAssignments.carId))
    .leftJoin(trips, eq(driverCarAssignments.id, trips.driverCarAssignmentId))
    .leftJoin(tripMetrics, eq(trips.id, tripMetrics.tripId))
    .where(eq(cars.companyId, companyId));

  // Group by car ID and only keep active trips (scheduled or in_progress)
  const carMap = new Map<string, (typeof carsWithTrips)[0]>();
  
  for (const row of carsWithTrips) {
    const carId = row.car.id;
    const existing = carMap.get(carId);
    const isActiveTrip = row.tripStatus === "scheduled" || row.tripStatus === "in_progress";
    
    // If this is not an active trip, skip it unless the car has no entry yet
    if (!isActiveTrip && existing) {
      continue;
    }
    
    // If no existing entry and no active trip, add the car with null trip data
    if (!existing && !isActiveTrip) {
      carMap.set(carId, {
        car: row.car,
        tripStatus: null,
        tripUpdatedAt: null,
        tripMetricsStartedAt: null,
        tripMetricsCompletedAt: null,
        tripMetricsUpdatedAt: null,
      });
      continue;
    }
    
    // If this is an active trip
    if (isActiveTrip) {
      if (!existing || existing.tripStatus === null) {
        // No existing or existing has no trip, use this active trip
        carMap.set(carId, row);
      } else if (row.tripUpdatedAt && existing.tripUpdatedAt) {
        // Both have active trips, keep the most recently updated
        if (row.tripUpdatedAt > existing.tripUpdatedAt) {
          carMap.set(carId, row);
        }
      } else if (row.tripUpdatedAt) {
        carMap.set(carId, row);
      }
    }
  }

  // Convert to array and sort
  const carsArray = Array.from(carMap.values());
  
  const sortedCars = carsArray.sort((a, b) => {
    const aStatus = a.tripStatus;
    const bStatus = b.tripStatus;
    
    // Priority 1: Cars with scheduled/in_progress trips ordered by trip departure time (closest first)
    const aIsActive = aStatus === "scheduled" || aStatus === "in_progress";
    const bIsActive = bStatus === "scheduled" || bStatus === "in_progress";
    
    if (aIsActive && bIsActive) {
      // Both have active trips - sort by departure time (earliest first = closest to current time)
      const aStartTime = a.tripMetricsStartedAt?.getTime() ?? Number.MAX_VALUE;
      const bStartTime = b.tripMetricsStartedAt?.getTime() ?? Number.MAX_VALUE;
      return aStartTime - bStartTime;
    }
    
    if (aIsActive) return -1;
    if (bIsActive) return 1;
    
    // Priority 2: Cars without active trips ordered by GPS location update (newest first)
    const aLocationTime = a.car.currentLocationTimestamp?.getTime() ?? 0;
    const bLocationTime = b.car.currentLocationTimestamp?.getTime() ?? 0;
    return bLocationTime - aLocationTime; // Descending (newest first)
  });

  // Apply pagination
  const paginatedCars = sortedCars.slice(
    offset ?? 0,
    limit ? (offset ?? 0) + limit : undefined
  );

  const total = carMap.size;
  return {
    items: paginatedCars.map((row) => mapCar(row.car)),
    total,
    limit: typeof limit === "number" ? limit : total,
    offset: typeof offset === "number" ? offset : 0,
  };
}

