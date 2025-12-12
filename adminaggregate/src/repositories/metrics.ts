import { and, count, eq, gte, inArray, lte, ne, sql, sum } from "drizzle-orm";
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
});

const mapCarMetrics = (row: CarMetricsRow): CarMetrics => ({
  carId: row.carId,
  totalRevenue: Number(row.totalRevenue),
  totalTrips: row.totalTrips,
  totalDistance: jobDistanceToNumber(row.totalDistance),
});

const mapTripDestinationMetrics = (row: TripDestinationMetricsRow): PerDestinationMetrics => ({
  destinationId: row.destinationId,
  numberOfBookings: row.numberOfBookings,
  totalRevenue: Number(row.totalRevenue),
});

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

export async function getDriverMetrics(driverId: string): Promise<DriverMetrics | null> {
  const [row] = await db.select().from(driverMetrics).where(eq(driverMetrics.driverId, driverId));
  return row ? mapDriverMetrics(row) : null;
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
  return row ? mapCarMetrics(row) : null;
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
    // Return zero metrics if no assignments
    return {
      carId,
      totalRevenue: 0,
      totalTrips: 0,
      totalDistance: 0,
    };
  }

  // Parse dates and set up date range
  let startDateTime: Date;
  let endDateTime: Date;

  if (!startDate && !endDate) {
    // No dates provided - default to today
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    startDateTime = today;
    const todayEnd = new Date();
    todayEnd.setHours(23, 59, 59, 999);
    endDateTime = todayEnd;
  } else if (startDate && !endDate) {
    // Only start date - from that date onwards
    startDateTime = new Date(startDate);
    startDateTime.setHours(0, 0, 0, 0);
    endDateTime = new Date(); // Current time
  } else if (startDate && endDate) {
    // Both dates - date range
    startDateTime = new Date(startDate);
    startDateTime.setHours(0, 0, 0, 0);
    endDateTime = new Date(endDate);
    endDateTime.setHours(23, 59, 59, 999);
  } else {
    // Only end date (shouldn't happen, but handle it)
    endDateTime = new Date(endDate!);
    endDateTime.setHours(23, 59, 59, 999);
    startDateTime = new Date(0); // Beginning of time
  }

  // Get trips for this car (to filter by car)
  const carTrips = await db
    .select({ id: trips.id, status: trips.status })
    .from(trips)
    .where(inArray(trips.driverCarAssignmentId, assignmentIds));

  const carTripIds = carTrips.map((t) => t.id);

  if (carTripIds.length === 0) {
    return {
      carId,
      totalRevenue: 0,
      totalTrips: 0,
      totalDistance: 0,
    };
  }

  // Filter out cancelled trips
  const nonCancelledTripIds = carTrips
    .filter((t) => t.status !== "cancelled")
    .map((t) => t.id);

  if (nonCancelledTripIds.length === 0) {
    return {
      carId,
      totalRevenue: 0,
      totalTrips: 0,
      totalDistance: 0,
    };
  }

  // Aggregate metrics from trip_metrics filtered by date range
  // Use tripCreatedAt from trip_metrics for date filtering
  const metricsResult = await db
    .select({
      totalTrips: count(),
      totalDistance: sum(tripMetrics.totalDistance),
      totalRevenue: sum(tripMetrics.totalFare),
    })
    .from(tripMetrics)
    .where(
      and(
        inArray(tripMetrics.tripId, nonCancelledTripIds),
        gte(tripMetrics.tripCreatedAt, startDateTime),
        lte(tripMetrics.tripCreatedAt, endDateTime)
      )
    );

  const metrics = metricsResult[0];

  return {
    carId,
    totalRevenue: metrics ? Number(metrics.totalRevenue) || 0 : 0,
    totalTrips: metrics?.totalTrips || 0,
    totalDistance: metrics ? Number(metrics.totalDistance) || 0 : 0,
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
  };
}



