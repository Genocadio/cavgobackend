package com.gocavgo.Navigation.service;

import com.gocavgo.Navigation.model.NavigationState;
import com.gocavgo.Navigation.model.Route;
import com.gocavgo.Navigation.routing.OsrmClient;
import com.gocavgo.Navigation.store.RedisStateStore;
import com.gocavgo.Navigation.util.GeoMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RerouteService {
    private final OsrmClient osrmClient;
    private final RedisStateStore redisStateStore;

    @Value("${navigation.off-route.city.distance-threshold-meters:40}")
    private double cityDistanceThreshold;

    @Value("${navigation.off-route.city.consecutive-updates:2}")
    private int cityConsecutiveUpdates;

    @Value("${navigation.off-route.non-city.distance-threshold-meters:40}")
    private double nonCityDistanceThreshold;

    @Value("${navigation.off-route.non-city.consecutive-updates:3}")
    private int nonCityConsecutiveUpdates;

    /**
     * Check if vehicle is off-route and trigger reroute if needed
     * Returns new route if rerouting occurred, null otherwise
     */
    public Route checkAndReroute(String carId, Long tripId, double gpsLat, double gpsLon,
            Route currentRoute, NavigationState state,
            boolean isCityTrip, List<double[]> remainingWaypoints) {
        double distanceThreshold = isCityTrip ? cityDistanceThreshold : nonCityDistanceThreshold;
        int consecutiveThreshold = isCityTrip ? cityConsecutiveUpdates : nonCityConsecutiveUpdates;

        // Calculate distance from GPS to route
        GeoMath.SnapResult snapResult = GeoMath.snapToRoute(gpsLat, gpsLon, currentRoute, state.getLastSnappedIndex());
        double distanceFromRoute = snapResult.distanceFromRoute;

        // LOG: Map matching result with on/off route status
        String routeStatus = distanceFromRoute <= distanceThreshold ? "ON-ROUTE" : "OFF-ROUTE";
        log.info(
                "📍 Map Match Result | CarId: {} | Status: {} | Distance from route: {}m | Threshold: {}m | GPS: ({}, {}) | Snapped Index: {}",
                carId, routeStatus, String.format("%.2f", distanceFromRoute), String.format("%.0f", distanceThreshold),
                String.format("%.6f", gpsLat), String.format("%.6f", gpsLon), snapResult.index);

        // Update off-route counter
        int previousOffRouteCount = state.getOffRouteConsecutiveCount();
        int offRouteCount = previousOffRouteCount;
        if (distanceFromRoute > distanceThreshold) {
            offRouteCount++;
            state.setOffRouteConsecutiveCount(offRouteCount);
            log.warn(
                    "🚨 Vehicle {} OFF-ROUTE: {}m from route (threshold: {}m) | Consecutive: {}/{} | Trip type: {}",
                    carId, String.format("%.2f", distanceFromRoute), String.format("%.0f", distanceThreshold),
                    offRouteCount, consecutiveThreshold, isCityTrip ? "CITY" : "NON-CITY");
        } else {
            if (previousOffRouteCount > 0) {
                log.info("✅ Vehicle {} back ON-ROUTE: {}m from route | Resetting consecutive count from {}",
                        carId, String.format("%.2f", distanceFromRoute), previousOffRouteCount);
            }
            offRouteCount = 0;
            state.setOffRouteConsecutiveCount(0);
        }

        // Trigger reroute if threshold exceeded
        if (offRouteCount >= consecutiveThreshold) {
            log.warn(
                    "🔄 REROUTING TRIGGERED | CarId: {} | Distance: {}m | Consecutive: {} (threshold: {}) | Trip: {}",
                    carId, String.format("%.2f", distanceFromRoute), offRouteCount, consecutiveThreshold,
                    isCityTrip ? "CITY" : "NON-CITY");

            return performReroute(carId, tripId, gpsLat, gpsLon, remainingWaypoints, state);
        } else if (distanceFromRoute > distanceThreshold) {
            log.info("⏳ Waiting for reroute | CarId: {} | Consecutive: {}/{} | Distance: {}m",
                    carId, offRouteCount, consecutiveThreshold, String.format("%.2f", distanceFromRoute));
        }

        // Save updated state
        redisStateStore.saveNavigationState(carId, state);
        return null;
    }

    /**
     * Perform reroute from current GPS position to remaining waypoints
     * Rerouting resets spatial progress (distanceTravelled) but preserves logical
     * progress (waypoint index)
     */
    private Route performReroute(String carId, Long tripId, double gpsLat, double gpsLon,
            List<double[]> remainingWaypoints, NavigationState state) {
        if (remainingWaypoints == null || remainingWaypoints.isEmpty()) {
            log.warn("No remaining waypoints for reroute, carId: {}", carId);
            return null;
        }

        // Build waypoint list: current GPS position + remaining waypoints
        List<double[]> rerouteWaypoints = new java.util.ArrayList<>();
        rerouteWaypoints.add(new double[] { gpsLat, gpsLon });
        rerouteWaypoints.addAll(remainingWaypoints);

        // Get new route from OSRM
        Route newRoute = osrmClient.getRoute(rerouteWaypoints, false);

        // Reset spatial progress (distance travelled)
        state.setDistanceTravelled(0.0);
        state.setLastSnappedIndex(0);
        state.setOffRouteConsecutiveCount(0);

        // Reset logical progress (currentLegIndex) because we are starting a NEW route
        // The new route starts from current location, so we are on the first leg (index
        // 0)
        state.setCurrentLegIndex(0);

        log.info("Reroute completed for carId: {}, new route distance: {}m, legIndex reset to 0",
                carId, newRoute.getTotalDistance());

        return newRoute;
    }
}
