import { and, eq, gte, inArray, isNotNull, lte, sum } from "drizzle-orm";
import { db } from "../db/client";
import { trips, driverCarAssignments, cars, tripMetrics, companies } from "../db/schema";
import type { CompanyPeriodMetrics, Granularity } from "../types";
import { resolveTimestampRange } from "../metrics/dateRange";
import { buildBuckets, toTimeSeries } from "../metrics/timeSeries";
import { PACKAGE_DELIVERY_REVENUE } from "../metrics/revenue";
import { getPassengerFareByTrip } from "./revenue";

type TripsByStatus = Record<"completed" | "cancelled" | "in_progress" | "scheduled", number>;

const emptyTripsByStatus = (): TripsByStatus => ({
  completed: 0,
  cancelled: 0,
  in_progress: 0,
  scheduled: 0,
});

export function determineGranularity(startTime: number, endTime: number): Granularity {
  const diffMs = endTime - startTime;
  const diffDays = diffMs / (1000 * 60 * 60 * 24);

  if (diffDays <= 1) {
    return "hourly";
  } else if (diffDays <= 14) {
    return "daily";
  } else if (diffDays <= 30) {
    return "weekly";
  } else {
    return "monthly";
  }
}

function emptyMetrics(
  companyId: string,
  period: "today" | "custom",
  start: number,
  end: number
): CompanyPeriodMetrics {
  return {
    companyId,
    period,
    startTime: start,
    endTime: end,
    totalTrips: 0,
    completedTrips: 0,
    cancelledTrips: 0,
    inProgressTrips: 0,
    scheduledTrips: 0,
    totalRevenue: 0,
    revenueFromCompletedTrips: 0,
    packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
    totalDistance: 0,
    totalDuration: 0,
    averageTripDistance: 0,
    averageTripDuration: 0,
    uniqueDrivers: 0,
    uniqueCars: 0,
  };
}

/**
 * Company period metrics.
 *
 * Trip status counts, unique drivers/cars and the trips time series all come
 * from a single fetch of trips in the period. The revenue time series is built
 * from one fetch of completions in the window plus one grouped bookings query,
 * so the number of queries is constant regardless of the chosen granularity.
 *
 * Revenue is passenger fare money from qualifying bookings on completed trips,
 * keyed on completion time so totals and the revenue series agree. Trip counts
 * and distance stay keyed on creation time. Package delivery fees are reported
 * separately and are currently zero.
 */
export async function getCompanyPeriodMetrics(
  companyId: string,
  startTime?: number,
  endTime?: number
): Promise<CompanyPeriodMetrics | null> {
  const period: "today" | "custom" = startTime && endTime ? "custom" : "today";
  const range = resolveTimestampRange(startTime, endTime);
  const start = range.start.getTime();
  const end = range.end.getTime();
  const startDate = range.start;
  const endDate = range.end;
  const granularity = determineGranularity(start, end);

  const company = await db
    .select({ id: companies.id })
    .from(companies)
    .where(eq(companies.id, companyId))
    .limit(1);

  if (company.length === 0) {
    return null;
  }

  const companyCars = await db
    .select({ id: cars.id })
    .from(cars)
    .where(eq(cars.companyId, companyId));

  if (companyCars.length === 0) {
    return emptyMetrics(companyId, period, start, end);
  }

  const assignments = await db
    .select({
      id: driverCarAssignments.id,
      driverId: driverCarAssignments.driverId,
      carId: driverCarAssignments.carId,
    })
    .from(driverCarAssignments)
    .where(inArray(driverCarAssignments.carId, companyCars.map((car) => car.id)));

  const assignmentIds = assignments.map((a) => Number(a.id));
  if (assignmentIds.length === 0) {
    return emptyMetrics(companyId, period, start, end);
  }

  // One fetch supplies status counts, completed trip ids, unique assignments
  // and the trips time series.
  const periodTrips = await db
    .select({
      id: trips.id,
      status: trips.status,
      assignmentId: trips.driverCarAssignmentId,
      createdAt: trips.createdAt,
    })
    .from(trips)
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate)
      )
    );

  const tripsByStatus = emptyTripsByStatus();
  const completedTripIds: string[] = [];
  const uniqueAssignmentIds = new Set<number>();

  for (const trip of periodTrips) {
    if (trip.status in tripsByStatus) {
      tripsByStatus[trip.status as keyof TripsByStatus] += 1;
    }
    if (trip.status === "completed") {
      completedTripIds.push(trip.id);
    }
    uniqueAssignmentIds.add(Number(trip.assignmentId));
  }

  // Distance and duration in one query.
  let totalDistance = 0;
  let totalDuration = 0;
  if (completedTripIds.length > 0) {
    const [travel] = await db
      .select({
        totalDistance: sum(tripMetrics.totalDistance),
        totalDuration: sum(tripMetrics.totalDuration),
      })
      .from(tripMetrics)
      .where(
        and(
          inArray(tripMetrics.tripId, completedTripIds),
          isNotNull(tripMetrics.completedAt)
        )
      );
    totalDistance = Number(travel?.totalDistance ?? 0);
    totalDuration = Number(travel?.totalDuration ?? 0);
  }

  const completedTrips = tripsByStatus.completed;

  let uniqueDrivers = 0;
  let uniqueCars = 0;
  if (uniqueAssignmentIds.size > 0) {
    const assignmentsData = await db
      .select({ driverId: driverCarAssignments.driverId, carId: driverCarAssignments.carId })
      .from(driverCarAssignments)
      .where(inArray(driverCarAssignments.id, [...uniqueAssignmentIds]));

    uniqueDrivers = new Set(
      assignmentsData.map((a) => a.driverId).filter((id): id is string => id !== null)
    ).size;
    uniqueCars = new Set(assignmentsData.map((a) => a.carId)).size;
  }

  // Revenue completions use completedAt (a trip can complete in a later
  // period than it was created); trip counts use createdAt.
  const completions = await db
    .select({ tripId: tripMetrics.tripId, completedAt: tripMetrics.completedAt })
    .from(tripMetrics)
    .innerJoin(trips, eq(tripMetrics.tripId, trips.id))
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        eq(trips.status, "completed"),
        isNotNull(tripMetrics.completedAt),
        gte(tripMetrics.completedAt, startDate),
        lte(tripMetrics.completedAt, endDate)
      )
    );

  const revenueByTrip = await getPassengerFareByTrip(completions.map((c) => c.tripId));

  // Revenue is read live from bookings, never from trip route prices. Totals
  // and the revenue series both use completion time so they reconcile.
  let totalRevenue = 0;
  for (const fare of revenueByTrip.values()) {
    totalRevenue += fare;
  }

  const buckets = buildBuckets(startDate, endDate, granularity);

  const revenueSeries = toTimeSeries(
    granularity,
    buckets,
    completions.flatMap((completion) =>
      completion.completedAt
        ? [{
            date: completion.completedAt,
            value: revenueByTrip.get(completion.tripId) || 0,
          }]
        : []
    )
  );

  const tripsSeries = toTimeSeries(
    granularity,
    buckets,
    periodTrips.map((trip) => ({ date: trip.createdAt ?? startDate, value: 1 }))
  );

  return {
    companyId,
    period,
    startTime: start,
    endTime: end,
    totalTrips: periodTrips.length,
    completedTrips,
    cancelledTrips: tripsByStatus.cancelled,
    inProgressTrips: tripsByStatus.in_progress,
    scheduledTrips: tripsByStatus.scheduled,
    totalRevenue,
    revenueFromCompletedTrips: totalRevenue,
    packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
    totalDistance,
    totalDuration,
    averageTripDistance: completedTrips > 0 ? totalDistance / completedTrips : 0,
    averageTripDuration: completedTrips > 0 ? totalDuration / completedTrips : 0,
    uniqueDrivers,
    uniqueCars,
    tripsByStatus,
    revenueSeries,
    tripsSeries,
  };
}
