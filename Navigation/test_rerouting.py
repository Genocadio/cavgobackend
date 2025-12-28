#!/usr/bin/env python3
"""
Rerouting Verification Script
Tests the navigation system's ability to detect off-route deviations and update the route.
"""

import requests
import time
import json
import math
from datetime import datetime, timezone, timedelta

# Configuration
API_BASE_URL = "http://localhost:8080/api"
CAR_ID = "test-car-reroute-01"

# Colors for terminal output
class Colors:
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    RESET = '\033[0m'
    BOLD = '\033[1m'

def log(message: str, color: str = Colors.RESET):
    """Print colored log message with timestamp"""
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"{color}[{timestamp}] {message}{Colors.RESET}")

def send_gps_update(lat: float, lon: float, speed: float = 15.0) -> dict:
    timestamp = datetime.now(timezone.utc).isoformat()
    payload = [{
        "carId": CAR_ID,
        "latitude": lat,
        "longitude": lon,
        "speed": speed,
        "heading": 0,
        "timestamp": timestamp
    }]
    
    try:
        response = requests.post(
            f"{API_BASE_URL}/gps",
            json=payload,
            headers={"Content-Type": "application/json"},
            timeout=5
        )
        if response.status_code == 200:
            return response.json()
        else:
            log(f"GPS Update failed: {response.status_code}", Colors.RED)
            return None
    except Exception as e:
        log(f"Error sending GPS: {str(e)}", Colors.RED)
        return None

def main():
    log("=" * 60, Colors.BOLD)
    log("Rerouting Verification Test", Colors.BOLD + Colors.CYAN)
    log("=" * 60, Colors.BOLD)

    # 1. Define Route (Stuttgart area)
    # Start: Schlossplatz
    start_lat, start_lon = 48.778400, 9.180000 
    # End: ~1km East
    end_lat, end_lon = 48.778400, 9.195000
    
    # Deviation Point: 100m North of midpoint
    mid_lat, mid_lon = 48.778400, 9.187500
    deviated_lat = 48.779300 # ~100m North (1 deg lat = 111km, 0.0009 ~ 100m)
    deviated_lon = 9.187500

    # 2. Create Trip
    trip_request = {
        "carId": CAR_ID,
        "waypoints": [
            {"latitude": start_lat, "longitude": start_lon, "name": "Start"},
            {"latitude": end_lat, "longitude": end_lon, "name": "End"}
        ],
        "includeInstructions": False,
        "includeOrigin": True,
        "isCityTrip": True # Use city thresholds (25m distance, 1 update)
    }

    log(f"Creating trip from {start_lat},{start_lon} to {end_lat},{end_lon}...", Colors.CYAN)
    resp = requests.post(f"{API_BASE_URL}/trips", json=trip_request)
    if resp.status_code != 201:
        log(f"Failed to create trip: {resp.text}", Colors.RED)
        return

    trip_data = resp.json()
    trip_id = trip_data['trip']['id']
    initial_dist = trip_data['trip']['waypointProgresses'][0]['remainingDistance']
    log(f"Trip created! ID: {trip_id}, Total Distance: {initial_dist:.1f}m", Colors.GREEN)

    # 3. Drive On-Route (Start -> Midpoint)
    log("\n--- Phase 1: Driving On-Route ---", Colors.BOLD)
    
    # Simulate movement 20m at a time
    steps = 5
    current_lat = start_lat
    current_lon = start_lon
    
    # Simple linear interpolation for simulation
    for i in range(steps):
        # Move east
        current_lon += (mid_lon - start_lon) / steps
        
        log(f"Driving... Loc: {current_lat:.6f}, {current_lon:.6f}", Colors.BLUE)
        res = send_gps_update(current_lat, current_lon)
        
        if res:
            # Track distance to FINAL waypoint (last in list)
            rem_dist = res['trip']['waypointProgresses'][-1]['remainingDistance']
            log(f"   Response: Remaining Dist to END: {rem_dist:.1f}m", Colors.GREEN)
        
        time.sleep(1)

    # 4. Trigger Deviation
    log("\n--- Phase 2: DEVIATING OFF-ROUTE ---", Colors.BOLD + Colors.YELLOW)
    log(f"Jumping to {deviated_lat:.6f}, {deviated_lon:.6f} (~100m off-route)", Colors.YELLOW)
    
    # Send deviated point
    res = send_gps_update(deviated_lat, deviated_lon)
    if res:
        rem_dist = res['trip']['waypointProgresses'][0]['remainingDistance']
        log(f"Deviation Update 1: Remaining Dist: {rem_dist:.1f}m", Colors.YELLOW)
        # Check logs for "OFF-ROUTE" warning
    
    time.sleep(1)
    
    res = send_gps_update(deviated_lat, deviated_lon)
    if res:
        rem_dist = res['trip']['waypointProgresses'][-1]['remainingDistance']
        log(f"Deviation Update 2: Remaining Dist: {rem_dist:.1f}m", Colors.BLUE)
        
        # This one SHOULD trigger reroute if thresholds are met (25m dist, 1 consecutive for city)
        log("Checking for reroute evidence (distance jump)...", Colors.CYAN)
    
    time.sleep(1)

    # 5. Verify Reroute
    log("\n--- Phase 3: Post-Reroute Verification ---", Colors.BOLD)
    # Send another point on the NEW path
    deviated_lat += 0.00001
    res = send_gps_update(deviated_lat, deviated_lon)
    
    if res:
        rem_dist = res['trip']['waypointProgresses'][0]['remainingDistance']
        log(f"Post-Reroute Update: Remaining Dist: {rem_dist:.1f}m", Colors.GREEN)
        
        # Logic: If rerouted, we are now ON the new route.
        # So "distance from route" (internal) is small.
        # "Remaining distance" should be accurate to the new path length.
        
        log("If remaining distance seems valid for the new location and logs showed 'Reroute completed', SUCCESS!", Colors.GREEN)

if __name__ == "__main__":
    main()
