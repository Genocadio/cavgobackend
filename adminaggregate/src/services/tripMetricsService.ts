import type { Trip } from "../types";
import * as carRepository from "../repositories/cars";
import * as metricsRepository from "../repositories/metrics";
import * as driverRepository from "../repositories/drivers";
import * as revenueRepository from "../repositories/revenue";
import { PACKAGE_DELIVERY_REVENUE } from "../metrics/revenue";

interface TripMetricsUpdateParams {
  trip: Trip;
  vehicleId: string;
  driverId: string | null;
  existingTrip: Trip | null;
  tripDistance: number;
  tripFare?: number | undefined;
  startedAt?: number | undefined;
  completedAt?: number | undefined;
}

/**
 * Updates metrics when a trip is created, updated, or cancelled.
 * Handles incrementing/decrementing trip counts and distances for cars and drivers.
 */
export async function updateTripMetrics(params: TripMetricsUpdateParams): Promise<void> {
  const { trip, vehicleId, driverId, existingTrip, tripDistance, tripFare = 0, startedAt, completedAt } = params;

  const safeTripDistance = typeof tripDistance === "number" && isFinite(tripDistance) ? tripDistance : 0;

  // Get the car to get companyId
  const car = await carRepository.getCarById(vehicleId);
  if (!car) {
    console.warn(`[METRICS] Car ${vehicleId} not found for trip ${trip.id}, skipping metrics update`);
    return;
  }

  const isNewTrip = !existingTrip;
  const previousStatus = existingTrip?.status;
  const currentStatus = trip.status;
  const wasCounted = previousStatus && previousStatus !== "cancelled";
  const shouldBeCounted = currentStatus !== "cancelled";

  // Determine if we need to increment or decrement
  let tripCountDelta = 0;
  let distanceDelta = 0;

  if (isNewTrip) {
    // New trip created - increment if not cancelled
    if (shouldBeCounted) {
      tripCountDelta = 1;
      distanceDelta = safeTripDistance;
    }
  } else {
    // Existing trip updated
    if (!wasCounted && shouldBeCounted) {
      // Status changed from cancelled (or null) to non-cancelled - increment
      tripCountDelta = 1;
      distanceDelta = tripDistance;
    } else if (wasCounted && !shouldBeCounted) {
      // Status changed from non-cancelled to cancelled - decrement
      tripCountDelta = -1;
      // Need to get the distance from the existing trip
      distanceDelta = -existingTrip.totalDistance;
    } else if (wasCounted && shouldBeCounted) {
      // Status is still non-cancelled but distance might have changed
      const distanceChange = safeTripDistance - existingTrip.totalDistance;
      if (distanceChange !== 0) {
        distanceDelta = distanceChange;
      }
    }
    // If both were/will be cancelled, no change
  }

  // Update car metrics if there's a change
  if (tripCountDelta !== 0 || distanceDelta !== 0) {
    const carMetrics = await metricsRepository.getCarMetrics(vehicleId);
    const newTripCount = Math.max(0, (carMetrics?.totalTrips || 0) + tripCountDelta);
    const newDistance = Math.max(0, (carMetrics?.totalDistance || 0) + distanceDelta);

    await metricsRepository.upsertCarMetrics({
      carId: vehicleId,
      totalRevenue: carMetrics?.totalRevenue || 0, // Keep revenue unchanged for now
      totalTrips: newTripCount,
      totalDistance: newDistance,
      packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
    });

    console.log(
      `[METRICS] Updated car metrics for ${vehicleId}: trips ${tripCountDelta >= 0 ? '+' : ''}${tripCountDelta}, distance ${distanceDelta >= 0 ? '+' : ''}${distanceDelta}`
    );
  }

  // Update driver metrics if there's a change and driver exists
  if (driverId && (tripCountDelta !== 0 || distanceDelta !== 0)) {
    // Guard against invalid driver IDs (e.g. 0) and only update if driver exists
    const driverExists = await driverRepository.getDriverById(String(driverId));
    if (!driverExists) {
      console.warn(`[METRICS] Skipping driver metrics for invalid/missing driver ${driverId}`);
    } else {
      const driverMetrics = await metricsRepository.getDriverMetrics(driverId);
    const newTripCount = Math.max(0, (driverMetrics?.totalTrips || 0) + tripCountDelta);
    const newDistance = Math.max(0, (driverMetrics?.totalDistance || 0) + distanceDelta);

      await metricsRepository.upsertDriverMetrics({
        driverId: driverId,
        totalRevenue: driverMetrics?.totalRevenue || 0,
        totalTrips: newTripCount,
        totalDistance: newDistance,
        packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
      });

      console.log(
        `[METRICS] Updated driver metrics for ${driverId}: trips ${tripCountDelta >= 0 ? '+' : ''}${tripCountDelta}, distance ${distanceDelta >= 0 ? '+' : ''}${distanceDelta}`
      );
    }
  }

  // Passenger fare is sourced from qualifying bookings, never from trip
  // route_price/price. Fares only count once the trip is completed; package
  // delivery fees are tracked in a separate bucket (still zero).
  const passengerFare =
    currentStatus === "completed"
      ? await revenueRepository.getPassengerFareRevenue([trip.id])
      : 0;

  // Update trip-specific metrics for in_progress and completed status
  if (currentStatus === "in_progress") {
    const existingMetrics = await metricsRepository.getTripMetrics(trip.id);
    await metricsRepository.upsertTripMetrics({
      tripId: trip.id,
      companyId: car.companyId,
      totalFare: passengerFare,
      totalDistance: safeTripDistance,
      totalDuration: existingMetrics?.totalDuration || 0,
      startedAt: startedAt || existingMetrics?.startedAt || trip.createdAt,
      completedAt: existingMetrics?.completedAt || null,
      tripCreatedAt: trip.createdAt,
      perDestinationMetrics: existingMetrics?.perDestinationMetrics || [],
      packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
    });
  } else if (currentStatus === "completed") {
    const existingMetrics = await metricsRepository.getTripMetrics(trip.id);
    const computedStartedAt = startedAt || existingMetrics?.startedAt || trip.createdAt;
    const computedCompletedAt = completedAt || existingMetrics?.completedAt || trip.updatedAt;
    const totalDuration = computedCompletedAt && computedStartedAt ? (computedCompletedAt - computedStartedAt) / 1000 : 0;

    await metricsRepository.upsertTripMetrics({
      tripId: trip.id,
      companyId: car.companyId,
      totalFare: passengerFare,
      totalDistance: safeTripDistance,
      totalDuration: totalDuration,
      startedAt: computedStartedAt,
      completedAt: computedCompletedAt,
      tripCreatedAt: trip.createdAt,
      perDestinationMetrics: existingMetrics?.perDestinationMetrics || [],
      packageDeliveryRevenue: PACKAGE_DELIVERY_REVENUE,
    });
  }
}

