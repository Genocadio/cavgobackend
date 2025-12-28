package com.gocavgo.Navigation.util;

import com.gocavgo.Navigation.model.Route;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class GeoMath {
    private static final int EARTH_RADIUS_METERS = 6371000;
    
    /**
     * Calculate haversine distance between two points in meters
     */
    public static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }
    
    /**
     * Calculate distance from a point to a line segment
     */
    public static double distanceToLineSegment(double pointLat, double pointLon,
                                               double segStartLat, double segStartLon,
                                               double segEndLat, double segEndLon) {
        // Vector from segment start to end
        double dx = segEndLon - segStartLon;
        double dy = segEndLat - segStartLat;
        
        // Vector from segment start to point
        double px = pointLon - segStartLon;
        double py = pointLat - segStartLat;
        
        // Calculate dot product
        double dot = dx * px + dy * py;
        double lenSq = dx * dx + dy * dy;
        
        if (lenSq == 0) {
            // Segment is a point
            return haversineDistance(pointLat, pointLon, segStartLat, segStartLon);
        }
        
        // Calculate parameter t (projection point on segment)
        double t = dot / lenSq;
        
        // Clamp t to [0, 1] to stay on segment
        t = Math.max(0, Math.min(1, t));
        
        // Calculate closest point on segment
        double closestLon = segStartLon + t * dx;
        double closestLat = segStartLat + t * dy;
        
        return haversineDistance(pointLat, pointLon, closestLat, closestLon);
    }
    
    /**
     * Snap GPS point to route polyline and return index and offset
     * Returns: {index, offsetDistance}
     * Never allows backwards movement (index can only increase)
     */
    public static SnapResult snapToRoute(double gpsLat, double gpsLon, 
                                        Route route, int lastSnappedIndex) {
        List<double[]> polyline = route.getPolyline();
        List<Double> cumulativeDistances = route.getCumulativeDistances();
        
        if (polyline == null || polyline.isEmpty()) {
            throw new IllegalArgumentException("Route polyline is empty");
        }
        
        // Ensure lastSnappedIndex is within bounds
        if (lastSnappedIndex < 0) {
            lastSnappedIndex = 0;
        }
        if (lastSnappedIndex >= polyline.size()) {
            lastSnappedIndex = polyline.size() - 1;
        }
        
        int bestIndex = lastSnappedIndex;
        double minDistance = Double.MAX_VALUE;
        
        // If already at the end of route, return the last point
        if (lastSnappedIndex >= polyline.size() - 1) {
            bestIndex = polyline.size() - 1;
            double[] lastPoint = polyline.get(bestIndex);
            double dist = haversineDistance(gpsLat, gpsLon, lastPoint[0], lastPoint[1]);
            double totalDistance = cumulativeDistances.get(bestIndex);
            return new SnapResult(bestIndex, 0.0, totalDistance, dist);
        }
        
        // Search forward from last snapped index
        for (int i = lastSnappedIndex; i < polyline.size() - 1; i++) {
            double[] p1 = polyline.get(i);
            double[] p2 = polyline.get(i + 1);
            
            double dist = distanceToLineSegment(gpsLat, gpsLon, p1[0], p1[1], p2[0], p2[1]);
            
            if (dist < minDistance) {
                minDistance = dist;
                bestIndex = i;
            }
        }
        
        // Ensure bestIndex is valid
        if (bestIndex >= polyline.size() - 1) {
            bestIndex = polyline.size() - 1;
            double[] lastPoint = polyline.get(bestIndex);
            double dist = haversineDistance(gpsLat, gpsLon, lastPoint[0], lastPoint[1]);
            double totalDistance = cumulativeDistances.get(bestIndex);
            return new SnapResult(bestIndex, 0.0, totalDistance, dist);
        }
        
        // Calculate offset along the segment
        double[] segStart = polyline.get(bestIndex);
        double[] segEnd = polyline.get(bestIndex + 1);
        
        double segDist = haversineDistance(segStart[0], segStart[1], segEnd[0], segEnd[1]);
        double pointToStart = haversineDistance(gpsLat, gpsLon, segStart[0], segStart[1]);
        double pointToEnd = haversineDistance(gpsLat, gpsLon, segEnd[0], segEnd[1]);
        
        // Project point onto segment
        double offset = 0.0;
        if (segDist > 0) {
            // Use law of cosines to find projection
            double cosAngle = (pointToStart * pointToStart + segDist * segDist - pointToEnd * pointToEnd) 
                    / (2 * pointToStart * segDist);
            cosAngle = Math.max(-1, Math.min(1, cosAngle)); // Clamp to valid range
            offset = pointToStart * cosAngle;
            offset = Math.max(0, Math.min(segDist, offset)); // Clamp to segment
        }
        
        double totalDistance = cumulativeDistances.get(bestIndex) + offset;
        
        return new SnapResult(bestIndex, offset, totalDistance, minDistance);
    }
    
    /**
     * Result of snapping GPS to route
     */
    public static class SnapResult {
        public final int index;
        public final double offset;
        public final double totalDistance;
        public final double distanceFromRoute;
        
        public SnapResult(int index, double offset, double totalDistance, double distanceFromRoute) {
            this.index = index;
            this.offset = offset;
            this.totalDistance = totalDistance;
            this.distanceFromRoute = distanceFromRoute;
        }
    }
}

