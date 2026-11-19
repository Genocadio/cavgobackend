import { and, count, eq, gte, inArray, lte, ne, sum } from "drizzle-orm";
import type { InferModel } from "drizzle-orm";
import { db } from "../db/client";
import {
  carMetrics,
  driverCarAssignments,
  driverMetrics,
  tripDestinationMetrics,
  tripMetrics,
  trips,
} from "../db/schema";
import type {
  CarMetrics,
  DriverMetrics,
  PerDestinationMetrics,
  TripMetrics,
} from "../types";
import { resolveDateRange, type DateRange } from "../metrics/dateRange";
import { PACKAGE_DELIVERY_REVENUE } from "../metrics/revenue";
import { getPassengerFareRevenue } from "./revenue";

type DriverMetricsRow = InferModel<typeof driverMetrics>;
type CarMetricsRow = InferModel<typeof carMetrics>;
type TripMetricsRow = InferModel<typeof tripMetrics>;
type TripDestinationMetricsRow = InferModel<typeof tripDestinationMetrics>;

const numericString = (value: number): string => value.toString();

const jobDistanceToNumber = (value: number | string): number =>
  typeof value === "number" ? value : Number(value);

const mapDriverMetrics = (row: DriverMetricsRow): DriverMetrics => ({
  driverId: row.driverId,
  totalRevenue: Number(row.totalRevenue),
  totalTrips: row.totalTrips,
  totalDistance: jobDistanceToNumber(row.totalDistance),
  packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
});

const mapCarMetrics = (row: CarMetricsRow): CarMetrics => ({
  carId: row.carId,
  totalRevenue: Number(row.totalRevenue),
  totalTrips: row.totalTrips,
  totalDistance: jobDistanceToNumber(row.totalDistance),
  packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
});

const mapTripDestinationMetrics = (row: TripDestinationMetricsRow): PerDestinationMetrics => ({
  destinationId: row.destinationId,
  numberOfBookings: row.numberOfBookings,
  totalRevenue: Number(row.totalRevenue),
});

const zeroDriverMetrics = (driverId: string): DriverMetrics => ({
  driverId,
  totalRevenue: 0,
  totalTrips: 0,
  totalDistance: 0,
  packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
});

const zeroCarMetrics = (carId: string): CarMetrics => ({
  carId,
  totalRevenue: 0,
  totalTrips: 0,
  totalDistance: 0,
  packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
});

/** Trip ids owned by a driver through their car assignments, excluding cancellations. */
async function getDriverTripIds(driverId: string, range?: DateRange): Promise<string[]> {
  const assignments = await db
    .select({ id: driverCarAssignments.id })
    .from(driverCarAssignments)
    .where(eq(driverCarAssignments.driverId, driverId));

  const assignmentIds = assignments.map((a) => Number(a.id));
  if (assignmentIds.length === 0) {
    return [];
  }

  const conditions = [
    inArray(trips.driverCarAssignmentId, assignmentIds),
    ne(trips.status, "cancelled"),
  ];
  if (range) {
    conditions.push(gte(trips.createdAt, range.start), lte(trips.createdAt, range.end));
  }

  const rows = await db.select({ id: trips.id }).from(trips).where(and(...conditions));
  return rows.map((row) => row.id);
}

export async function upsertDriverMetrics(metrics: DriverMetrics): Promise<DriverMetrics> {
  await db
    .insert(driverMetrics)
    .values({
      driverId: metrics.driverId,
      totalRevenue: numericString(metrics.totalRevenue),
      totalTrips: metrics.totalTrips,
      totalDistance: metrics.totalDistance,
      updatedAt: new Date(),
    })
    .onConflictDoUpdate({
      target: driverMetrics.driverId,
      set: {
        totalRevenue: numericString(metrics.totalRevenue),
        totalTrips: metrics.totalTrips,
        totalDistance: metrics.totalDistance,
        updatedAt: new Date(),
      },
    });

  return metrics;
}

/**
 * Lifetime stored counters, with passenger fare revenue read live from bookings.
 * Kept backwards compatible for clients that call `driverMetrics(driverId)`
 * without date bounds.
 */
export async function getDriverMetrics(driverId: string): Promise<DriverMetrics | null> {
  const [row] = await db.select().from(driverMetrics).where(eq(driverMetrics.driverId, driverId));
  if (!row) {
    return null;
  }

  const metrics = mapDriverMetrics(row);
  return {
    ...metrics,
    totalRevenue: await getPassengerFareRevenue(await getDriverTripIds(driverId)),
  };
}

/**
 * Date-scoped driver metrics aggregated from trip_metrics, with fare revenue
 * from qualifying bookings. Mirrors `getCarMetricsByDateRange`.
 */
export async function getDriverMetricsByDateRange(
  driverId: string,
  startDate?: string,
  endDate?: string
): Promise<DriverMetrics> {
  const range = resolveDateRange(startDate, endDate);
  const tripIds = await getDriverTripIds(driverId, range);

  if (tripIds.length === 0) {
    return zeroDriverMetrics(driverId);
  }

  const [aggregate] = await db
    .select({
      totalTrips: count(),
      totalDistance: sum(tripMetrics.totalDistance),
    })
    .from(tripMetrics)
    .where(inArray(tripMetrics.tripId, tripIds));

  return {
    driverId,
    totalRevenue: await getPassengerFareRevenue(tripIds),
    totalTrips: aggregate?.totalTrips || 0,
    totalDistance: Number(aggregate?.totalDistance ?? 0),
    packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
  };
}

export async function upsertCarMetrics(metrics: CarMetrics): Promise<CarMetrics> {
  await db
    .insert(carMetrics)
    .values({
      carId: metrics.carId,
      totalRevenue: numericString(metrics.totalRevenue),
      totalTrips: metrics.totalTrips,
      totalDistance: metrics.totalDistance,
      updatedAt: new Date(),
    })
    .onConflictDoUpdate({
      target: carMetrics.carId,
      set: {
        totalRevenue: numericString(metrics.totalRevenue),
        totalTrips: metrics.totalTrips,
        totalDistance: metrics.totalDistance,
        updatedAt: new Date(),
      },
    });

  return metrics;
}

export async function getCarMetrics(carId: string): Promise<CarMetrics | null> {
  const [row] = await db.select().from(carMetrics).where(eq(carMetrics.carId, carId));
  if (!row) {
    return null;
  }

  const metrics = mapCarMetrics(row);
  const assignments = await db
    .select({ id: driverCarAssignments.id })
    .from(driverCarAssignments)
    .where(eq(driverCarAssignments.carId, carId));
  const assignmentIds = assignments.map((a) => Number(a.id));
  if (assignmentIds.length === 0) {
    return metrics;
  }

  const rows = await db
    .select({ id: trips.id })
    .from(trips)
    .where(and(inArray(trips.driverCarAssignmentId, assignmentIds), ne(trips.status, "cancelled")));
  return {
    ...metrics,
    totalRevenue: await getPassengerFareRevenue(rows.map((trip) => trip.id)),
  };
}

/**
 * Get car metrics for a specific date range
 * @param carId - The car ID
 * @param startDate - Optional start date string (format: "YYYY-MM-DD"). If not provided, defaults to today
 * @param endDate - Optional end date string (format: "YYYY-MM-DD"). If not provided and startDate is provided, returns from startDate onwards
 * @returns Car metrics aggregated from trip_metrics for the specified date range
 */
export async function getCarMetricsByDateRange(
  carId: string,
  startDate?: string,
  endDate?: string
): Promise<CarMetrics> {
  // Get assignment IDs for this car
  const assignments = await db
    .select({ id: driverCarAssignments.id })
    .from(driverCarAssignments)
    .where(eq(driverCarAssignments.carId, carId));

  const assignmentIds = assignments.map((a) => Number(a.id));

  if (assignmentIds.length === 0) {
    return zeroCarMetrics(carId);
  }

  const { start: startDateTime, end: endDateTime } = resolveDateRange(startDate, endDate);

  // Get trips for this car (to filter by car)
  const carTrips = await db
    .select({ id: trips.id, status: trips.status })
    .from(trips)
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        gte(trips.createdAt, startDateTime),
        lte(trips.createdAt, endDateTime)
      )
    );

  const nonCancelledTripIds = carTrips
    .filter((trip) => trip.status !== "cancelled")
    .map((trip) => trip.id);

  if (nonCancelledTripIds.length === 0) {
    return zeroCarMetrics(carId);
  }

  // Aggregate trips/distance from trip_metrics for the date range
  const metricsResult = await db
    .select({
      totalTrips: count(),
      totalDistance: sum(tripMetrics.totalDistance),
    })
    .from(tripMetrics)
    .where(inArray(tripMetrics.tripId, nonCancelledTripIds));

  const completedTripIds = carTrips
    .filter((trip) => trip.status === "completed")
    .map((trip) => trip.id);

  const metrics = metricsResult[0];

  return {
    carId,
    totalRevenue: await getPassengerFareRevenue(completedTripIds),
    totalTrips: metrics?.totalTrips || 0,
    totalDistance: Number(metrics?.totalDistance ?? 0),
    packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
  };
}

export async function upsertTripMetrics(metrics: TripMetrics): Promise<TripMetrics> {
  await db
    .insert(tripMetrics)
    .values({
      companyId: metrics.companyId,
      tripId: metrics.tripId,
      totalFare: numericString(metrics.totalFare),
      totalDistance: metrics.totalDistance,
      totalDuration: metrics.totalDuration,
      startedAt: metrics.startedAt ? new Date(metrics.startedAt) : null,
      completedAt: metrics.completedAt ? new Date(metrics.completedAt) : null,
      tripCreatedAt: new Date(metrics.tripCreatedAt),
      updatedAt: new Date(),
    })
    .onConflictDoUpdate({
      target: tripMetrics.tripId,
      set: {
        companyId: metrics.companyId,
        totalFare: numericString(metrics.totalFare),
        totalDistance: metrics.totalDistance,
        totalDuration: metrics.totalDuration,
        startedAt: metrics.startedAt ? new Date(metrics.startedAt) : null,
        completedAt: metrics.completedAt ? new Date(metrics.completedAt) : null,
        tripCreatedAt: new Date(metrics.tripCreatedAt),
        updatedAt: new Date(),
      },
    });

  await db.delete(tripDestinationMetrics).where(eq(tripDestinationMetrics.tripId, metrics.tripId));

  await Promise.all(
    metrics.perDestinationMetrics.map(async (entry) => {
      await db.insert(tripDestinationMetrics).values({
        tripId: metrics.tripId,
        destinationId: entry.destinationId,
        numberOfBookings: entry.numberOfBookings,
        totalRevenue: numericString(entry.totalRevenue),
        createdAt: new Date(),
      });
    }),
  );

  return metrics;
}

export async function getTripMetrics(tripId: string): Promise<TripMetrics | null> {
  const [row] = await db.select().from(tripMetrics).where(eq(tripMetrics.tripId, tripId));
  if (!row) {
    return null;
  }

  const destinationRows = await db
    .select()
    .from(tripDestinationMetrics)
    .where(eq(tripDestinationMetrics.tripId, tripId));

  return {
    tripId: row.tripId,
    companyId: row.companyId,
    totalFare: Number(row.totalFare),
    totalDistance: jobDistanceToNumber(row.totalDistance),
    totalDuration: jobDistanceToNumber(row.totalDuration),
    startedAt: row.startedAt ? row.startedAt.getTime() : null,
    completedAt: row.completedAt ? row.completedAt.getTime() : null,
    tripCreatedAt: row.tripCreatedAt.getTime(),
    perDestinationMetrics: destinationRows.map(mapTripDestinationMetrics),
    packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
  };
}
