import { count, eq } from "drizzle-orm";
import type { InferSelectModel } from "drizzle-orm";
import { db } from "../db/client";
import { cars } from "../db/schema";
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
  const baseQuery = db.select().from(cars).where(eq(cars.companyId, companyId));
  const limitedQuery = typeof limit === "number" ? baseQuery.limit(limit) : baseQuery;
  const finalQuery = typeof offset === "number" ? limitedQuery.offset(offset) : limitedQuery;

  const [rows, totalResult] = await Promise.all([
    finalQuery,
    db.select({ count: count() }).from(cars).where(eq(cars.companyId, companyId)),
  ]);

  const total = totalResult[0]?.count ?? 0;
  return {
    items: rows.map(mapCar),
    total,
    limit: typeof limit === "number" ? limit : total,
    offset: typeof offset === "number" ? offset : 0,
  };
}

