import { and, inArray, sum } from "drizzle-orm";
import { db } from "../db/client";
import { bookings } from "../db/schema";
import { REVENUE_BOOKING_STATUSES } from "../metrics/revenue";

/**
 * Passenger fare revenue per trip, computed live from local booking rows.
 *
 * Query-time aggregation (rather than reading `trip_metrics.total_fare`) means
 * bookings that sync after their trip still show up without a backfill.
 * Callers decide which trips qualify — revenue is only reported for completed
 * trips by the metrics repositories.
 */
export async function getPassengerFareByTrip(tripIds: string[]): Promise<Map<string, number>> {
  if (tripIds.length === 0) {
    return new Map();
  }

  const rows = await db
    .select({ tripId: bookings.tripId, total: sum(bookings.totalFare) })
    .from(bookings)
    .where(
      and(
        inArray(bookings.tripId, tripIds),
        inArray(bookings.status, [...REVENUE_BOOKING_STATUSES])
      )
    )
    .groupBy(bookings.tripId);

  const revenueByTrip = new Map<string, number>();
  for (const row of rows) {
    revenueByTrip.set(row.tripId, Number(row.total ?? 0));
  }
  return revenueByTrip;
}

/** Sum of qualifying passenger fares for a set of trips. */
export async function getPassengerFareRevenue(tripIds: string[]): Promise<number> {
  const revenueByTrip = await getPassengerFareByTrip(tripIds);
  let total = 0;
  for (const value of revenueByTrip.values()) {
    total += value;
  }
  return total;
}
