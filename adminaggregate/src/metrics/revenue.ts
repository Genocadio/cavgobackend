/**
 * Revenue semantics for admin metrics.
 *
 * There are two independent revenue buckets and they must never be merged:
 *
 * 1. Passenger fare revenue (`totalRevenue` in the GraphQL types) comes from
 *    local `bookings` rows. Only confirmed/completed/used bookings count, and
 *    only on completed trips. `pending`, `cancelled` and `expired` bookings do
 *    not represent earned money.
 * 2. Package delivery revenue comes from package fees. There is no package
 *    payment or ledger data in the aggregator yet, so it is reported as zero
 *    rather than being conflated with passenger fares.
 *
 * Fare revenue is computed at query time from `bookings` (not from the stored
 * `trip_metrics.total_fare` column) so that bookings syncing after a trip
 * still show up immediately. The stored column is updated on trip events as a
 * best-effort snapshot.
 */

export const REVENUE_BOOKING_STATUSES = ["confirmed", "completed", "used"] as const;
export const REVENUE_TRIP_STATUS = "completed";

/** No package payment/ledger exists yet — this is an explicit placeholder. */
export const PACKAGE_DELIVERY_REVENUE = 0;

export function isRevenueBookingStatus(status: string): boolean {
  return (REVENUE_BOOKING_STATUSES as readonly string[]).includes(status);
}
