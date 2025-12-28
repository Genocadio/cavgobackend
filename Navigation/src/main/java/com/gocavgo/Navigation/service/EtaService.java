package com.gocavgo.Navigation.service;

import com.gocavgo.Navigation.model.NavigationState;
import com.gocavgo.Navigation.model.Route;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EtaService {
    private static final double MIN_SPEED_MS = 0.5; // Minimum speed for ETA calculation (m/s)
    private static final double ZERO_SPEED_THRESHOLD = 0.1; // Speed below this is considered stopped
    
    /**
     * Calculate stable ETA using max(routeETA, speedETA)
     * When speed is near zero, defaults to routeETA to prevent infinite ETA
     */
    public double calculateETA(Route route, NavigationState state, double currentSpeed) {
        double remainingDistance = route.getTotalDistance() - state.getDistanceTravelled();
        
        if (remainingDistance <= 0) {
            return 0.0;
        }
        
        // Calculate route-based ETA (remaining route duration)
        double routeETA = calculateRouteETA(route, state);
        
        // Calculate speed-based ETA
        double speedETA = calculateSpeedETA(remainingDistance, currentSpeed, state.getAvgSpeed());
        
        // Use max of both, but if speed is near zero, prefer routeETA
        if (currentSpeed < ZERO_SPEED_THRESHOLD && state.getAvgSpeed() < ZERO_SPEED_THRESHOLD) {
            // Vehicle is stopped, use routeETA
            return routeETA;
        }
        
        // Use max to prevent ETA flickering
        return Math.max(routeETA, speedETA);
    }
    
    /**
     * Calculate ETA based on remaining route duration
     */
    private double calculateRouteETA(Route route, NavigationState state) {
        double remainingDistance = route.getTotalDistance() - state.getDistanceTravelled();
        
        if (remainingDistance <= 0) {
            return 0.0;
        }
        
        // Calculate proportion of route remaining
        double distanceRatio = remainingDistance / route.getTotalDistance();
        
        // Estimate remaining duration based on route duration
        // This is approximate - could be improved with leg-based calculation
        return route.getTotalDuration() * distanceRatio;
    }
    
    /**
     * Calculate ETA based on current/average speed
     * Uses weighted moving average to smooth out speed variations
     */
    private double calculateSpeedETA(double remainingDistance, double currentSpeed, double avgSpeed) {
        // Use weighted average: 70% current speed, 30% average speed
        double effectiveSpeed = 0.7 * currentSpeed + 0.3 * avgSpeed;
        
        // Ensure minimum speed to prevent infinite ETA
        effectiveSpeed = Math.max(effectiveSpeed, MIN_SPEED_MS);
        
        return remainingDistance / effectiveSpeed;
    }
    
    /**
     * Calculate remaining time to a specific waypoint
     */
    public double calculateTimeToWaypoint(Route route, NavigationState state, int waypointIndex) {
        if (waypointIndex < 0 || waypointIndex >= route.getLegCumulativeDistances().size()) {
            return 0.0;
        }
        
        double waypointDistance = route.getLegCumulativeDistances().get(waypointIndex);
        double remainingDistance = waypointDistance - state.getDistanceTravelled();
        
        if (remainingDistance <= 0) {
            return 0.0;
        }
        
        // Use average speed or minimum speed
        double effectiveSpeed = Math.max(state.getAvgSpeed(), MIN_SPEED_MS);
        
        return remainingDistance / effectiveSpeed;
    }
}



