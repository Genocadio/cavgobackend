import { eq, and, or, inArray, gte, lte, count, sum, sql, isNotNull } from "drizzle-orm";
import { db } from "../db/client";
import { trips, driverCarAssignments, cars, tripMetrics, companies } from "../db/schema";
import type { CompanyPeriodMetrics, Granularity, TimeSeries, TimeSeriesPoint } from "../types";

/**
 * Helper function to convert seconds to milliseconds if needed.
 * If timestamp is before year 2000 in milliseconds, assume it's in seconds.
 */
function convertToMilliseconds(timestamp: number): number {
  // Year 2000 in milliseconds: Jan 1, 2000 00:00:00 UTC
  const year2000Ms = 946684800000;
  if (timestamp < year2000Ms) {
    return timestamp * 1000;
  }
  return timestamp;
}

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

function formatTimeSeriesLabel(
  date: Date,
  granularity: Granularity
): string {
  switch (granularity) {
    case "hourly":
      return `${date.getHours().toString().padStart(2, "0")}:00`;
    case "daily":
      return date.toISOString().split("T")[0] || ""; // YYYY-MM-DD
    case "weekly":
      // Get week number
      const weekNum = Math.ceil((date.getDate() + new Date(date.getFullYear(), date.getMonth(), 1).getDay()) / 7);
      return `Week ${weekNum}`;
    case "monthly":
      const monthName = date.toLocaleString("default", { month: "long" });
      return monthName || date.toISOString(); // "March"
    default:
      return date.toISOString();
  }
}

function getTimeSeriesUnit(granularity: Granularity): "hours" | "days" | "weeks" | "months" {
  switch (granularity) {
    case "hourly":
      return "hours";
    case "daily":
      return "days";
    case "weekly":
      return "weeks";
    case "monthly":
      return "months";
  }
}

async function generateTimeSeries(
  assignmentIds: number[],
  startTime: number,
  endTime: number,
  granularity: Granularity,
  type: "revenue" | "trips"
): Promise<TimeSeries> {
  const startDate = new Date(startTime);
  const endDate = new Date(endTime);
  
  const points: TimeSeriesPoint[] = [];
  
  if (granularity === "hourly") {
    // Group by hour
    const current = new Date(startDate);
    current.setMinutes(0, 0, 0);
    
    while (current <= endDate) {
      const hourStart = new Date(current);
      const hourEnd = new Date(current);
      hourEnd.setHours(hourEnd.getHours() + 1);
      
      if (type === "revenue") {
        const result = await db
          .select({ total: sum(tripMetrics.totalFare) })
          .from(tripMetrics)
          .innerJoin(trips, eq(tripMetrics.tripId, trips.id))
          .where(
            and(
              inArray(trips.driverCarAssignmentId, assignmentIds),
              eq(trips.status, "completed"),
              sql`${tripMetrics.completedAt} IS NOT NULL`,
              gte(tripMetrics.completedAt, hourStart),
              lte(tripMetrics.completedAt, hourEnd)
            )
          );
        points.push({
          label: formatTimeSeriesLabel(current, granularity),
          value: Number(result[0]?.total || 0),
        });
      } else {
        const result = await db
          .select({ count: count() })
          .from(trips)
          .where(
            and(
              inArray(trips.driverCarAssignmentId, assignmentIds),
              gte(trips.createdAt, hourStart),
              lte(trips.createdAt, hourEnd)
            )
          );
        points.push({
          label: formatTimeSeriesLabel(current, granularity),
          value: result[0]?.count || 0,
        });
      }
      
      current.setHours(current.getHours() + 1);
    }
  } else if (granularity === "daily") {
    // Group by day
    const current = new Date(startDate);
    current.setHours(0, 0, 0, 0);
    
    while (current <= endDate) {
      const dayStart = new Date(current);
      const dayEnd = new Date(current);
      dayEnd.setHours(23, 59, 59, 999);
      
      if (type === "revenue") {
        const result = await db
          .select({ total: sum(tripMetrics.totalFare) })
          .from(tripMetrics)
          .innerJoin(trips, eq(tripMetrics.tripId, trips.id))
          .where(
            and(
              inArray(trips.driverCarAssignmentId, assignmentIds),
              eq(trips.status, "completed"),
              sql`${tripMetrics.completedAt} IS NOT NULL`,
              gte(tripMetrics.completedAt, dayStart),
              lte(tripMetrics.completedAt, dayEnd)
            )
          );
        points.push({
          label: formatTimeSeriesLabel(current, granularity),
          value: Number(result[0]?.total || 0),
        });
      } else {
        const result = await db
          .select({ count: count() })
          .from(trips)
          .where(
            and(
              inArray(trips.driverCarAssignmentId, assignmentIds),
              gte(trips.createdAt, dayStart),
              lte(trips.createdAt, dayEnd)
            )
          );
        points.push({
          label: formatTimeSeriesLabel(current, granularity),
          value: result[0]?.count || 0,
        });
      }
      
      current.setDate(current.getDate() + 1);
    }
  } else if (granularity === "weekly") {
    // Group by week
    const current = new Date(startDate);
    // Start of week (Monday)
    const dayOfWeek = current.getDay();
    const diff = current.getDate() - dayOfWeek + (dayOfWeek === 0 ? -6 : 1);
    current.setDate(diff);
    current.setHours(0, 0, 0, 0);
    
    while (current <= endDate) {
      const weekStart = new Date(current);
      const weekEnd = new Date(current);
      weekEnd.setDate(weekEnd.getDate() + 6);
      weekEnd.setHours(23, 59, 59, 999);
      
      if (type === "revenue") {
        const result = await db
          .select({ total: sum(tripMetrics.totalFare) })
          .from(tripMetrics)
          .innerJoin(trips, eq(tripMetrics.tripId, trips.id))
          .where(
            and(
              inArray(trips.driverCarAssignmentId, assignmentIds),
              eq(trips.status, "completed"),
              sql`${tripMetrics.completedAt} IS NOT NULL`,
              gte(tripMetrics.completedAt, weekStart),
              lte(tripMetrics.completedAt, weekEnd)
            )
          );
        points.push({
          label: formatTimeSeriesLabel(current, granularity),
          value: Number(result[0]?.total || 0),
        });
      } else {
        const result = await db
          .select({ count: count() })
          .from(trips)
          .where(
            and(
              inArray(trips.driverCarAssignmentId, assignmentIds),
              gte(trips.createdAt, weekStart),
              lte(trips.createdAt, weekEnd)
            )
          );
        points.push({
          label: formatTimeSeriesLabel(current, granularity),
          value: result[0]?.count || 0,
        });
      }
      
      current.setDate(current.getDate() + 7);
    }
  } else {
    // Monthly
    const current = new Date(startDate);
    current.setDate(1);
    current.setHours(0, 0, 0, 0);
    
    while (current <= endDate) {
      const monthStart = new Date(current);
      const monthEnd = new Date(current.getFullYear(), current.getMonth() + 1, 0);
      monthEnd.setHours(23, 59, 59, 999);
      
      if (type === "revenue") {
        const result = await db
          .select({ total: sum(tripMetrics.totalFare) })
          .from(tripMetrics)
          .innerJoin(trips, eq(tripMetrics.tripId, trips.id))
          .where(
            and(
              inArray(trips.driverCarAssignmentId, assignmentIds),
              eq(trips.status, "completed"),
              sql`${tripMetrics.completedAt} IS NOT NULL`,
              gte(tripMetrics.completedAt, monthStart),
              lte(tripMetrics.completedAt, monthEnd)
            )
          );
        points.push({
          label: formatTimeSeriesLabel(current, granularity),
          value: Number(result[0]?.total || 0),
        });
      } else {
        const result = await db
          .select({ count: count() })
          .from(trips)
          .where(
            and(
              inArray(trips.driverCarAssignmentId, assignmentIds),
              gte(trips.createdAt, monthStart),
              lte(trips.createdAt, monthEnd)
            )
          );
        points.push({
          label: formatTimeSeriesLabel(current, granularity),
          value: result[0]?.count || 0,
        });
      }
      
      current.setMonth(current.getMonth() + 1);
    }
  }
  
  return {
    granularity,
    unit: getTimeSeriesUnit(granularity),
    data: points,
  };
}

export async function getCompanyPeriodMetrics(
  companyId: string,
  startTime?: number,
  endTime?: number
): Promise<CompanyPeriodMetrics | null> {
  // Determine time range
  let period: "today" | "custom" = "today";
  let start: number;
  let end: number;
  
  if (startTime && endTime) {
    period = "custom";
    // Convert timestamps from seconds to milliseconds if needed
    start = convertToMilliseconds(startTime);
    end = convertToMilliseconds(endTime);
  } else {
    // Default to today
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    start = today.getTime();
    const todayEnd = new Date();
    todayEnd.setHours(23, 59, 59, 999);
    end = todayEnd.getTime();
  }
  
  // Create date objects from timestamps
  const startDate = new Date(start);
  const endDate = new Date(end);
  
  // Determine granularity (using converted timestamps in milliseconds)
  const granularity = determineGranularity(start, end);
  
  // Verify company exists first
  const company = await db
    .select({ id: companies.id })
    .from(companies)
    .where(eq(companies.id, companyId))
    .limit(1);
  
  if (company.length === 0) {
    return null;
  }
  
  // Get all cars for company
  const companyCars = await db
    .select({ id: cars.id })
    .from(cars)
    .where(eq(cars.companyId, companyId));
  
  const carIds = companyCars.map(c => c.id);
  
  if (carIds.length === 0) {
    // Return empty metrics if no cars
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
      totalDistance: 0,
      totalDuration: 0,
      averageTripDistance: 0,
      averageTripDuration: 0,
      uniqueDrivers: 0,
      uniqueCars: 0,
    };
  }
  
  // Get all assignment IDs for these cars
  const assignments = await db
    .select({ id: driverCarAssignments.id, driverId: driverCarAssignments.driverId, carId: driverCarAssignments.carId })
    .from(driverCarAssignments)
    .where(inArray(driverCarAssignments.carId, carIds));
  
  const assignmentIds = assignments.map(a => Number(a.id));
  
  if (assignmentIds.length === 0) {
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
      totalDistance: 0,
      totalDuration: 0,
      averageTripDistance: 0,
      averageTripDuration: 0,
      uniqueDrivers: 0,
      uniqueCars: 0,
    };
  }
  
  // Count trips by status
  const totalTripsResult = await db
    .select({ count: count() })
    .from(trips)
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate)
      )
    );
  const totalTrips = totalTripsResult[0]?.count || 0;
  
  const completedTripsResult = await db
    .select({ count: count() })
    .from(trips)
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate),
        eq(trips.status, "completed")
      )
    );
  const completedTrips = completedTripsResult[0]?.count || 0;
  
  const cancelledTripsResult = await db
    .select({ count: count() })
    .from(trips)
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate),
        eq(trips.status, "cancelled")
      )
    );
  const cancelledTrips = cancelledTripsResult[0]?.count || 0;
  
  const inProgressTripsResult = await db
    .select({ count: count() })
    .from(trips)
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate),
        eq(trips.status, "in_progress")
      )
    );
  const inProgressTrips = inProgressTripsResult[0]?.count || 0;
  
  const scheduledTripsResult = await db
    .select({ count: count() })
    .from(trips)
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate),
        eq(trips.status, "scheduled")
      )
    );
  const scheduledTrips = scheduledTripsResult[0]?.count || 0;
  
  // Get revenue from trip_metrics
  // Filter by trips created in the period, but only include completed trips
  const revenueResult = await db
    .select({ total: sum(tripMetrics.totalFare) })
    .from(tripMetrics)
    .innerJoin(trips, eq(tripMetrics.tripId, trips.id))
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        eq(trips.status, "completed"),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate),
        isNotNull(tripMetrics.completedAt)
      )
    );
  const totalRevenue = Number(revenueResult[0]?.total || 0);
  const revenueFromCompletedTrips = totalRevenue; // Same query for completed trips

  // Get travel metrics
  // Filter by trips created in the period, but only include completed trips
  const distanceResult = await db
    .select({ total: sum(tripMetrics.totalDistance) })
    .from(tripMetrics)
    .innerJoin(trips, eq(tripMetrics.tripId, trips.id))
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        eq(trips.status, "completed"),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate),
        isNotNull(tripMetrics.completedAt)
      )
    );
  const totalDistance = Number(distanceResult[0]?.total || 0);

  const durationResult = await db
    .select({ total: sum(tripMetrics.totalDuration) })
    .from(tripMetrics)
    .innerJoin(trips, eq(tripMetrics.tripId, trips.id))
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        eq(trips.status, "completed"),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate),
        isNotNull(tripMetrics.completedAt)
      )
    );
  const totalDuration = Number(durationResult[0]?.total || 0);
  
  // Calculate averages
  const averageTripDistance = completedTrips > 0 ? totalDistance / completedTrips : 0;
  const averageTripDuration = completedTrips > 0 ? totalDuration / completedTrips : 0;
  
  // Get unique drivers and cars from trips in period
  // Get all trips in period, then extract unique driver/car IDs
  const tripsInPeriod = await db
    .select({
      assignmentId: trips.driverCarAssignmentId,
    })
    .from(trips)
    .where(
      and(
        inArray(trips.driverCarAssignmentId, assignmentIds),
        gte(trips.createdAt, startDate),
        lte(trips.createdAt, endDate)
      )
    );
  
  const uniqueAssignmentIds = [...new Set(tripsInPeriod.map(t => t.assignmentId))];
  
  let uniqueDrivers = 0;
  let uniqueCars = 0;
  
  if (uniqueAssignmentIds.length > 0) {
    const assignmentsData = await db
      .select({
        driverId: driverCarAssignments.driverId,
        carId: driverCarAssignments.carId,
      })
      .from(driverCarAssignments)
      .where(inArray(driverCarAssignments.id, uniqueAssignmentIds));
    
    const uniqueDriverIds = new Set(
      assignmentsData
        .map(a => a.driverId)
        .filter(id => id !== null)
    );
    const uniqueCarIds = new Set(assignmentsData.map(a => a.carId));
    
    uniqueDrivers = uniqueDriverIds.size;
    uniqueCars = uniqueCarIds.size;
  }
  
  // Generate time series
  const revenueSeries = await generateTimeSeries(assignmentIds, start, end, granularity, "revenue");
  const tripsSeries = await generateTimeSeries(assignmentIds, start, end, granularity, "trips");
  
  return {
    companyId,
    period,
    startTime: start,
    endTime: end,
    totalTrips,
    completedTrips,
    cancelledTrips,
    inProgressTrips,
    scheduledTrips,
    totalRevenue,
    revenueFromCompletedTrips,
    totalDistance,
    totalDuration,
    averageTripDistance,
    averageTripDuration,
    uniqueDrivers,
    uniqueCars,
    tripsByStatus: {
      completed: completedTrips,
      cancelled: cancelledTrips,
      in_progress: inProgressTrips,
      scheduled: scheduledTrips,
    },
    revenueSeries,
    tripsSeries,
  };
}

