#!/usr/bin/env python3
"""
Navigation API Test Script
Tests the navigation system using a GPX file with multi-waypoint trips.
"""

import requests
import xml.etree.ElementTree as ET
import time
import json
import random
from datetime import datetime, timezone, timedelta
from typing import List, Tuple, Dict
import math

# Configuration
API_BASE_URL = "http://localhost:8080/api"
GPX_FILE = "/Users/pro/Downloads/12134448.gpx"
CAR_ID = "test-car-001"
UPDATE_INTERVAL = 1.0  # seconds between GPS updates
BATCH_SIZE_MIN = 1  # Minimum batch size
BATCH_SIZE_MAX = 50  # Maximum batch size

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

def parse_gpx(file_path: str) -> List[Tuple[float, float, float, str]]:
    """
    Parse GPX file and extract track points.
    Returns list of (lat, lon, elevation, timestamp)
    """
    log(f"Parsing GPX file: {file_path}", Colors.CYAN)
    tree = ET.parse(file_path)
    root = tree.getroot()
    
    # Handle namespace
    ns = {'gpx': 'http://www.topografix.com/GPX/1/1'}
    
    points = []
    for trkpt in root.findall('.//gpx:trkpt', ns):
        lat = float(trkpt.get('lat'))
        lon = float(trkpt.get('lon'))
        
        ele_elem = trkpt.find('gpx:ele', ns)
        ele = float(ele_elem.text) if ele_elem is not None else 0.0
        
        time_elem = trkpt.find('gpx:time', ns)
        timestamp = time_elem.text if time_elem is not None else None
        
        points.append((lat, lon, ele, timestamp))
    
    log(f"Parsed {len(points)} track points from GPX", Colors.GREEN)
    return points

def calculate_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculate haversine distance between two points in meters"""
    R = 6371000  # Earth radius in meters
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat/2) * math.sin(dlat/2) + \
        math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * \
        math.sin(dlon/2) * math.sin(dlon/2)
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))
    return R * c

def calculate_speed(lat1: float, lon1: float, time1: str, 
                   lat2: float, lon2: float, time2: str) -> float:
    """Calculate speed in m/s between two points"""
    distance = calculate_distance(lat1, lon1, lat2, lon2)
    
    if time1 and time2:
        try:
            t1 = datetime.fromisoformat(time1.replace('Z', '+00:00'))
            t2 = datetime.fromisoformat(time2.replace('Z', '+00:00'))
            duration = (t2 - t1).total_seconds()
            if duration > 0:
                return distance / duration
        except:
            pass
    
    # Default speed if time calculation fails
    return 10.0  # m/s (~36 km/h)

def create_multi_waypoint_trip(points: List[Tuple[float, float, float, str]], 
                               use_trace_waypoints: bool = False) -> Dict:
    """
    Create a multi-waypoint trip from GPX points.
    
    Args:
        points: List of GPS points from GPX file
        use_trace_waypoints: If True, use waypoints from trace to follow the route.
                            If False, use start, 1/3, 2/3, end waypoints.
    """
    if len(points) < 4:
        raise ValueError("Not enough points in GPX for multi-waypoint trip")
    
    # Predefined waypoint names for variety
    waypoint_names = [
        "Home", "Office", "Restaurant", "Gas Station", "Shopping Mall",
        "Park", "Airport", "Hotel", "Hospital", "School", "Library",
        "Gym", "Cinema", "Beach", "Mountain", "City Center", "Suburb",
        "Highway Exit", "Parking Lot", "Meeting Point"
    ]
    
    waypoints = []
    
    if use_trace_waypoints:
        # Use waypoints from the trace to ensure the route follows the trace
        # Select waypoints at regular intervals along the trace
        num_waypoints = min(10, len(points) // 50)  # Use up to 10 waypoints, spaced every 50 points
        if num_waypoints < 3:
            num_waypoints = 3  # Minimum 3 waypoints
        
        step = len(points) // (num_waypoints + 1)
        waypoint_indices = [i * step for i in range(1, num_waypoints + 1)]
        waypoint_indices.append(len(points) - 1)  # Always include the last point
        
        log(f"Creating trip with {len(waypoint_indices)} waypoints from trace:", Colors.BOLD + Colors.CYAN)
        for i, idx in enumerate(waypoint_indices):
            lat, lon, _, _ = points[idx]
            
            # Create waypoint with optional id and name
            waypoint = {"latitude": lat, "longitude": lon}
            
            # Randomly add ID (70% chance)
            if random.random() < 0.7:
                waypoint["id"] = f"wp-{i+1:03d}"  # e.g., "wp-001", "wp-002"
            
            # Randomly add name (60% chance)
            if random.random() < 0.6:
                # Use predefined name or generate one
                if i < len(waypoint_names):
                    waypoint["name"] = waypoint_names[i]
                else:
                    waypoint["name"] = f"Location {i+1}"
            
            waypoints.append(waypoint)
            
            # Log waypoint with id/name info
            id_str = f" [ID: {waypoint.get('id', 'none')}]" if 'id' in waypoint else ""
            name_str = f" [Name: {waypoint.get('name', 'none')}]" if 'name' in waypoint else ""
            if i == 0:
                log(f"  Waypoint {i+1} (ORIGIN): {lat:.6f}, {lon:.6f} (point {idx}){id_str}{name_str}", Colors.CYAN)
            else:
                log(f"  Waypoint {i+1}: {lat:.6f}, {lon:.6f} (point {idx}){id_str}{name_str}", Colors.CYAN)
    else:
        # Use start, 1/3, 2/3, end waypoints (original behavior)
        waypoint_indices = [
            0,
            len(points) // 3,
            (2 * len(points)) // 3,
            len(points) - 1
        ]
        
        log(f"Creating trip with {len(waypoint_indices)} waypoints:", Colors.BOLD + Colors.CYAN)
        for i, idx in enumerate(waypoint_indices):
            lat, lon, _, _ = points[idx]
            
            # Create waypoint with optional id and name
            waypoint = {"latitude": lat, "longitude": lon}
            
            # Randomly add ID (70% chance)
            if random.random() < 0.7:
                waypoint["id"] = f"wp-{i+1:03d}"  # e.g., "wp-001", "wp-002"
            
            # Randomly add name (60% chance)
            if random.random() < 0.6:
                # Use predefined name or generate one
                if i < len(waypoint_names):
                    waypoint["name"] = waypoint_names[i]
                else:
                    waypoint["name"] = f"Location {i+1}"
            
            waypoints.append(waypoint)
            
            # Log waypoint with id/name info
            id_str = f" [ID: {waypoint.get('id', 'none')}]" if 'id' in waypoint else ""
            name_str = f" [Name: {waypoint.get('name', 'none')}]" if 'name' in waypoint else ""
            if i == 0:
                log(f"  Waypoint {i+1} (ORIGIN): {lat:.6f}, {lon:.6f}{id_str}{name_str}", Colors.CYAN)
            else:
                log(f"  Waypoint {i+1}: {lat:.6f}, {lon:.6f}{id_str}{name_str}", Colors.CYAN)
    
    include_origin = False  # Device is at origin
    trip_request = {
        "carId": CAR_ID,
        "waypoints": waypoints,
        "includeInstructions": True,
        "includeOrigin": include_origin,
        "isCityTrip": False
    }
    
    # Log tracking information
    if not include_origin:
        log(f"\n📌 Note: includeOrigin=False - Waypoint 1 (origin) will be SKIPPED in progress tracking", Colors.YELLOW)
        log(f"   Only waypoints 2-{len(waypoints)} will be tracked in navigation", Colors.YELLOW)
    else:
        log(f"\n📌 Note: includeOrigin=True - All waypoints including origin will be tracked", Colors.YELLOW)
    
    return trip_request

def create_trip(trip_request: Dict) -> Dict:
    """Create a trip via API"""
    log(f"Creating trip for car: {CAR_ID}", Colors.BOLD + Colors.BLUE)
    
    try:
        response = requests.post(
            f"{API_BASE_URL}/trips",
            json=trip_request,
            headers={"Content-Type": "application/json"},
            timeout=30
        )
        
        if response.status_code == 201:
            trip_data = response.json()
            log(f"✓ Trip created successfully! Trip ID: {trip_data.get('trip', {}).get('id')}", Colors.GREEN)
            return trip_data
        else:
            log(f"✗ Failed to create trip. Status: {response.status_code}", Colors.RED)
            log(f"  Response: {response.text}", Colors.RED)
            return None
    except Exception as e:
        log(f"✗ Error creating trip: {str(e)}", Colors.RED)
        return None

def send_gps_update(lat: float, lon: float, speed: float, timestamp: str = None) -> Dict:
    """Send single GPS update to API"""
    # Always use current timestamp to avoid "too old" rejection
    # The backend validates that timestamps are not older than max-age-seconds (30s)
    timestamp = datetime.now(timezone.utc).isoformat()
    
    gps_request = {
        "carId": CAR_ID,
        "latitude": lat,
        "longitude": lon,
        "speed": speed,
        "heading": None,
        "accuracy": None,
        "timestamp": timestamp
    }
    
    try:
        response = requests.post(
            f"{API_BASE_URL}/gps",
            json=gps_request,
            headers={"Content-Type": "application/json"},
            timeout=10
        )
        
        if response.status_code == 200:
            return response.json()
        elif response.status_code == 400:
            log(f"⚠ GPS update rejected (out of order or invalid)", Colors.YELLOW)
            return None
        elif response.status_code == 404:
            log(f"✗ No active trip found for car", Colors.RED)
            return None
        else:
            log(f"✗ GPS update failed. Status: {response.status_code}", Colors.RED)
            return None
    except Exception as e:
        log(f"✗ Error sending GPS update: {str(e)}", Colors.RED)
        return None

def send_batch_gps_updates(updates: List[Dict], base_timestamp: datetime = None) -> Dict:
    """Send batch of GPS updates to API
    
    Args:
        updates: List of GPS update dictionaries
        base_timestamp: Base timestamp to start from (ensures chronological order across batches)
                       If None, uses current time
    """
    if not updates or len(updates) == 0:
        return None
    
    # Use provided base_timestamp or current time
    if base_timestamp is None:
        base_timestamp = datetime.now(timezone.utc)
    else:
        # Ensure base_timestamp is not in the past (add small buffer)
        now = datetime.now(timezone.utc)
        if base_timestamp < now:
            base_timestamp = now
    
    # Prepare batch request (array of GPS updates)
    batch_request = []
    
    for i, update in enumerate(updates):
        # Use incremental timestamps to maintain chronological order
        # Each update is UPDATE_INTERVAL seconds after the previous one
        timestamp = (base_timestamp + timedelta(seconds=i * UPDATE_INTERVAL)).isoformat()
        
        gps_update = {
            "carId": CAR_ID,
            "latitude": update["latitude"],
            "longitude": update["longitude"],
            "speed": update["speed"],
            "heading": update.get("heading"),
            "accuracy": update.get("accuracy"),
            "timestamp": timestamp
        }
        batch_request.append(gps_update)
    
    try:
        log(f"📦 Sending batch of {len(batch_request)} GPS updates...", Colors.CYAN)
        response = requests.post(
            f"{API_BASE_URL}/gps",
            json=batch_request,
            headers={"Content-Type": "application/json"},
            timeout=30  # Longer timeout for batch processing
        )
        
        if response.status_code == 200:
            result = response.json()
            log(f"✓ Batch processed successfully", Colors.GREEN)
            return result
        elif response.status_code == 400:
            log(f"⚠ Batch GPS update rejected (out of order or invalid)", Colors.YELLOW)
            return None
        elif response.status_code == 404:
            log(f"✗ No active trip found for car", Colors.RED)
            return None
        else:
            log(f"✗ Batch GPS update failed. Status: {response.status_code}", Colors.RED)
            log(f"  Response: {response.text}", Colors.RED)
            return None
    except Exception as e:
        log(f"✗ Error sending batch GPS update: {str(e)}", Colors.RED)
        return None

def analyze_response(response: Dict, prev_response: Dict = None, include_origin: bool = False):
    """Analyze and log navigation response"""
    if not response:
        return
    
    trip = response.get('trip', {})
    current_location = response.get('currentLocation', {})
    waypoint_progresses = trip.get('waypointProgresses', [])
    
    # Log current position
    if current_location:
        lat = float(current_location.get('latitude', 0))
        lon = float(current_location.get('longitude', 0))
        speed = float(current_location.get('speed', 0))
        log(f"📍 Current Position (map-matched): {lat:.6f}, {lon:.6f} | Speed: {speed:.2f} m/s", Colors.CYAN)
    
    # Show origin status if not included
    if not include_origin and not prev_response:
        log(f"   🏁 Origin (Waypoint 1): Already at origin, not tracked", Colors.GREEN)
    
    # Separate waypoints by state
    done_waypoints = []
    active_waypoints = []
    
    # Check for waypoint status changes and categorize waypoints
    if prev_response:
        prev_progresses = prev_response.get('trip', {}).get('waypointProgresses', [])
        for i, wp_progress in enumerate(waypoint_progresses):
            if i < len(prev_progresses):
                prev_state = prev_progresses[i].get('state', '')
                curr_state = wp_progress.get('state', '')
                
                if prev_state != curr_state:
                    wp_idx = wp_progress.get('waypointIndex', i)
                    # Display index: waypointIndex is 0-based from original trip
                    # If includeOrigin=false, index 0 is skipped, so index 1 = waypoint 2
                    # If includeOrigin=true, index 0 = waypoint 1
                    display_idx = wp_idx + 1
                    
                    # Get waypoint name and ID if available
                    wp_name = wp_progress.get('waypointName')
                    wp_id = wp_progress.get('waypointId')
                    name_str = f" [{wp_name}]" if wp_name else ""
                    id_str = f" (ID: {wp_id})" if wp_id else ""
                    
                    log(f"🎯 Waypoint {display_idx}{name_str}{id_str} state changed: {prev_state} → {curr_state}", Colors.BOLD + Colors.GREEN)
                    
                    if curr_state == 'ARRIVED':
                        arrived_at = wp_progress.get('arrivedAt')
                        log(f"   Arrived at: {arrived_at}", Colors.GREEN)
                    elif curr_state == 'DONE':
                        log(f"   Waypoint passed!", Colors.GREEN)
            
            # Categorize waypoints
            state = wp_progress.get('state', '')
            if state == 'DONE':
                done_waypoints.append((wp_progress, i))
            else:
                active_waypoints.append((wp_progress, i))
    else:
        # First response - categorize all waypoints
        for i, wp_progress in enumerate(waypoint_progresses):
            state = wp_progress.get('state', '')
            if state == 'DONE':
                done_waypoints.append((wp_progress, i))
            else:
                active_waypoints.append((wp_progress, i))
    
    # Log passed waypoints (DONE) - always show if any exist
    if done_waypoints:
        done_list = []
        for wp_progress, i in done_waypoints:
            wp_idx = wp_progress.get('waypointIndex', i)
            # Display index: waypointIndex is 0-based from original trip, so add 1 for display
            display_idx = wp_idx + 1
            wp_name = wp_progress.get('waypointName')
            name_str = f" [{wp_name}]" if wp_name else ""
            done_list.append(f"WP {display_idx}{name_str}")
        log(f"   ✅ Passed waypoints: {', '.join(done_list)}", Colors.GREEN)
    elif not prev_response:
        # On first response, if no done waypoints, explicitly show that all are active
        # This helps debug if waypoints are missing
        expected_count = len(waypoint_progresses)
        if expected_count > 0:
            log(f"   📊 Tracking {expected_count} waypoint(s) (all active)", Colors.CYAN)
    
    # Log active waypoints (APPROACHING or ARRIVED)
    if active_waypoints:
        for wp_progress, i in active_waypoints:
            state = wp_progress.get('state', '')
            remaining_dist = float(wp_progress.get('remainingDistance', 0))
            remaining_time = float(wp_progress.get('remainingTime', 0))
            
            wp_idx = wp_progress.get('waypointIndex', i)
            # Display index: waypointIndex is 0-based from original trip, so add 1 for display
            # The backend already handles includeOrigin by skipping index 0 when includeOrigin=false
            display_idx = wp_idx + 1
            
            # Get waypoint name and ID if available
            wp_name = wp_progress.get('waypointName')
            wp_id = wp_progress.get('waypointId')
            name_str = f" [{wp_name}]" if wp_name else ""
            id_str = f" (ID: {wp_id})" if wp_id else ""
            
            log(f"   WP {display_idx}{name_str}{id_str}: {state} | "
                f"Remaining: {remaining_dist:.1f}m, {remaining_time/60:.1f}min", Colors.BLUE)
    else:
        # If no active waypoints, log all waypoints for debugging
        log(f"   ⚠️  No active waypoints found. All waypoints:", Colors.YELLOW)
        for i, wp_progress in enumerate(waypoint_progresses):
            state = wp_progress.get('state', '')
            wp_idx = wp_progress.get('waypointIndex', i)
            # Display index: waypointIndex is 0-based from original trip, so add 1 for display
            display_idx = wp_idx + 1
            
            # Get waypoint name and ID if available
            wp_name = wp_progress.get('waypointName')
            wp_id = wp_progress.get('waypointId')
            name_str = f" [{wp_name}]" if wp_name else ""
            id_str = f" (ID: {wp_id})" if wp_id else ""
            
            log(f"      WP {display_idx}{name_str}{id_str}: {state}", Colors.YELLOW)
    
    # Check for rerouting (if route changed)
    if prev_response:
        prev_trip_id = prev_response.get('trip', {}).get('id')
        curr_trip_id = trip.get('id')
        # Note: We can't easily detect rerouting from response, but we can check logs
    
    # Log trip status
    status = trip.get('status', '')
    if status == 'COMPLETED':
        log(f"🏁 Trip completed!", Colors.BOLD + Colors.GREEN)

def simulate_off_route(points: List[Tuple[float, float, float, str]], 
                      current_idx: int, deviation_meters: float = 50) -> Tuple[float, float]:
    """
    Simulate off-route by adding deviation to GPS coordinates.
    This will trigger rerouting detection.
    """
    if current_idx >= len(points):
        return points[-1][0], points[-1][1]
    
    lat, lon, _, _ = points[current_idx]
    
    # Add deviation (move perpendicular to route)
    # Simple approximation: add offset in latitude
    deviation_degrees = deviation_meters / 111000  # ~111km per degree latitude
    deviated_lat = lat + deviation_degrees
    
    return deviated_lat, lon

def prompt_user(prompt: str, default: str = "y") -> bool:
    """Prompt user for yes/no input"""
    while True:
        response = input(f"{prompt} [{default.upper()}/n]: ").strip().lower()
        if not response:
            response = default.lower()
        if response in ['y', 'yes']:
            return True
        elif response in ['n', 'no']:
            return False
        else:
            print("Please enter 'y' or 'n'")

def main():
    """Main test function"""
    log("=" * 80, Colors.BOLD)
    log("Navigation API Test Script", Colors.BOLD + Colors.CYAN)
    log("=" * 80, Colors.BOLD)
    
    # Prompt for batch mode
    use_batch_mode = prompt_user("Use batch mode for GPS updates?", "y")
    
    # Prompt for deviation mode
    use_deviations = prompt_user("Include off-route deviations in simulation?", "n")
    
    log("\n" + "=" * 80, Colors.BOLD)
    log("Configuration:", Colors.BOLD + Colors.CYAN)
    log(f"  Batch Mode: {'ENABLED' if use_batch_mode else 'DISABLED'}", Colors.CYAN)
    if use_batch_mode:
        log(f"  Batch Size Range: {BATCH_SIZE_MIN} - {BATCH_SIZE_MAX} (variable)", Colors.CYAN)
    log(f"  Deviations: {'ENABLED' if use_deviations else 'DISABLED'}", Colors.CYAN)
    if not use_deviations:
        log(f"  Route Strategy: Waypoints from trace (follows trace route)", Colors.CYAN)
    else:
        log(f"  Route Strategy: Standard waypoints (will deviate from route)", Colors.CYAN)
    log("=" * 80 + "\n", Colors.BOLD)
    
    # Parse GPX file
    try:
        points = parse_gpx(GPX_FILE)
    except Exception as e:
        log(f"✗ Failed to parse GPX file: {str(e)}", Colors.RED)
        return
    
    if len(points) < 10:
        log(f"✗ Not enough points in GPX file ({len(points)})", Colors.RED)
        return
    
    # Create multi-waypoint trip
    # If no deviations, use waypoints from trace to follow the route
    # If deviations enabled, use standard waypoints that may cause deviations
    use_trace_waypoints = not use_deviations
    trip_request = create_multi_waypoint_trip(points, use_trace_waypoints=use_trace_waypoints)
    include_origin = trip_request.get('includeOrigin', False)
    trip_response = create_trip(trip_request)
    
    if not trip_response:
        log("✗ Failed to create trip. Exiting.", Colors.RED)
        return
    
    log("\n" + "=" * 80, Colors.BOLD)
    log("Starting GPS simulation...", Colors.BOLD + Colors.CYAN)
    if use_batch_mode:
        log(f"Mode: BATCH (variable batch size: {BATCH_SIZE_MIN}-{BATCH_SIZE_MAX})", Colors.YELLOW)
    else:
        log(f"Mode: SINGLE (one update at a time)", Colors.YELLOW)
    log("=" * 80 + "\n", Colors.BOLD)
    
    # Simulate GPS updates
    prev_response = None
    off_route_triggered = False
    off_route_start_idx = len(points) // 2  # Trigger off-route at midpoint
    
    # Track previous point for speed calculation
    prev_lat, prev_lon = None, None
    prev_update_time = time.time()
    
    # Batch mode: collect updates and send in batches with variable batch sizes
    if use_batch_mode:
        batch_updates = []
        current_batch_size = random.randint(BATCH_SIZE_MIN, BATCH_SIZE_MAX)  # Random batch size for first batch
        batch_count = 0
        last_timestamp = datetime.now(timezone.utc)  # Track last timestamp to ensure chronological order
        
        for i in range(len(points)):
            lat, lon, ele, _ = points[i]  # Ignore GPX timestamp, use current time
            
            # Calculate speed with progressive increase and variation
            # Start around 60 km/h (16.7 m/s) and gradually increase to 80 km/h (22.2 m/s)
            progress = i / len(points)  # 0.0 to 1.0
            
            # Base speed: start at 16 m/s (57.6 km/h), increase to 22 m/s (79.2 km/h)
            base_speed = 16.0 + (progress * 6.0)  # 16 to 22 m/s
            
            # Add variation based on distance traveled (simulate acceleration/deceleration)
            if prev_lat is not None and prev_lon is not None:
                distance = calculate_distance(prev_lat, prev_lon, lat, lon)
                time_elapsed = UPDATE_INTERVAL  # Time between updates
                if time_elapsed > 0:
                    calculated_speed = distance / time_elapsed
                    # Blend calculated speed with base speed (70% base, 30% calculated)
                    speed = (base_speed * 0.7) + (calculated_speed * 0.3)
                else:
                    speed = base_speed
            else:
                speed = base_speed
            
            # Add small random variation (±2 m/s) for realism
            speed += random.uniform(-2.0, 2.0)
            
            # Cap speed: minimum 12 m/s (43 km/h), maximum 25 m/s (90 km/h)
            speed = max(12.0, min(speed, 25.0))
            
            # Simulate off-route at specific point to test rerouting (only if deviations enabled)
            if use_deviations and i == off_route_start_idx and not off_route_triggered:
                log(f"\n🚨 Simulating off-route deviation at point {i}...", Colors.BOLD + Colors.YELLOW)
                deviated_lat, deviated_lon = simulate_off_route(points, i, deviation_meters=50)
                lat, lon = deviated_lat, deviated_lon
                off_route_triggered = True
                log(f"   Sending deviated GPS: {lat:.6f}, {lon:.6f} (should trigger reroute)", Colors.YELLOW)
            
            # Add to batch
            batch_updates.append({
                "latitude": lat,
                "longitude": lon,
                "speed": speed,
                "heading": None,
                "accuracy": None
            })
            
            log(f"📡 GPS Point {i+1}/{len(points)}: {lat:.6f}, {lon:.6f} | Speed: {speed:.2f} m/s", Colors.BLUE)
            
            # Send batch when it reaches current_batch_size or at the end
            if len(batch_updates) >= current_batch_size or i == len(points) - 1:
                batch_count += 1
                log(f"📦 Sending batch #{batch_count} with {len(batch_updates)} GPS updates (target size: {current_batch_size})", Colors.CYAN)
                response = send_batch_gps_updates(batch_updates, last_timestamp)
                
                if response:
                    analyze_response(response, prev_response, include_origin)
                    prev_response = response
                    # Update last_timestamp to be after the last update in this batch
                    # Add 1 second per update to ensure proper chronological order
                    last_timestamp = last_timestamp + timedelta(seconds=len(batch_updates) * UPDATE_INTERVAL)
                
                # Clear batch for next iteration
                batch_updates = []
                
                # Generate new random batch size for next batch (if not at the end)
                if i < len(points) - 1:
                    current_batch_size = random.randint(BATCH_SIZE_MIN, BATCH_SIZE_MAX)
                    time.sleep(0.1)  # Small delay between batches
                response = send_batch_gps_updates(batch_updates)
                
                if response:
                    analyze_response(response, prev_response, include_origin)
                    prev_response = response
                
                # Clear batch for next iteration
                batch_updates = []
                
                # Small delay between batches
                if i < len(points) - 1:
                    time.sleep(0.1)  # Small delay between batches
            
            # Update previous point for next iteration
            prev_lat, prev_lon = lat, lon
    
    else:
        # Single update mode (original behavior)
        for i in range(len(points)):
            lat, lon, ele, _ = points[i]  # Ignore GPX timestamp, use current time
            
            # Calculate speed with progressive increase and variation
            # Start around 60 km/h (16.7 m/s) and gradually increase to 80 km/h (22.2 m/s)
            progress = i / len(points)  # 0.0 to 1.0
            
            # Base speed: start at 16 m/s (57.6 km/h), increase to 22 m/s (79.2 km/h)
            base_speed = 16.0 + (progress * 6.0)  # 16 to 22 m/s
            
            # Add variation based on distance traveled (simulate acceleration/deceleration)
            if prev_lat is not None and prev_lon is not None:
                distance = calculate_distance(prev_lat, prev_lon, lat, lon)
                time_elapsed = UPDATE_INTERVAL  # Time between updates
                if time_elapsed > 0:
                    calculated_speed = distance / time_elapsed
                    # Blend calculated speed with base speed (70% base, 30% calculated)
                    speed = (base_speed * 0.7) + (calculated_speed * 0.3)
                else:
                    speed = base_speed
            else:
                speed = base_speed
            
            # Add small random variation (±2 m/s) for realism
            speed += random.uniform(-2.0, 2.0)
            
            # Cap speed: minimum 12 m/s (43 km/h), maximum 25 m/s (90 km/h)
            speed = max(12.0, min(speed, 25.0))
            
            # Simulate off-route at specific point to test rerouting (only if deviations enabled)
            if use_deviations and i == off_route_start_idx and not off_route_triggered:
                log(f"\n🚨 Simulating off-route deviation at point {i}...", Colors.BOLD + Colors.YELLOW)
                deviated_lat, deviated_lon = simulate_off_route(points, i, deviation_meters=50)
                lat, lon = deviated_lat, deviated_lon
                off_route_triggered = True
                log(f"   Sending deviated GPS: {lat:.6f}, {lon:.6f} (should trigger reroute)", Colors.YELLOW)
            
            # Send GPS update (timestamp will be set to current time in send_gps_update)
            log(f"\n📡 GPS Update {i+1}/{len(points)}: {lat:.6f}, {lon:.6f} | Speed: {speed:.2f} m/s", Colors.BLUE)
            
            response = send_gps_update(lat, lon, speed)
            
            if response:
                analyze_response(response, prev_response, include_origin)
                prev_response = response
            
            # Update previous point for next iteration
            prev_lat, prev_lon = lat, lon
            
            # Wait before next update
            if i < len(points) - 1:
                time.sleep(UPDATE_INTERVAL)
    
    log("\n" + "=" * 80, Colors.BOLD)
    log("GPS simulation completed!", Colors.BOLD + Colors.GREEN)
    log("=" * 80, Colors.BOLD)

if __name__ == "__main__":
    main()

