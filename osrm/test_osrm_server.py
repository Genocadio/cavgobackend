#!/usr/bin/env python3
"""
OSRM Server Test Script
Tests if OSRM server is running and responds correctly
"""

import sys
import requests
import json

class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    CYAN = '\033[96m'
    BOLD = '\033[1m'
    END = '\033[0m'

def print_header(text: str):
    print(f"\n{Colors.BOLD}{Colors.CYAN}{'='*70}{Colors.END}")
    print(f"{Colors.BOLD}{Colors.CYAN}{text.center(70)}{Colors.END}")
    print(f"{Colors.BOLD}{Colors.CYAN}{'='*70}{Colors.END}\n")

def print_success(text: str):
    print(f"{Colors.GREEN}✅ {text}{Colors.END}")

def print_error(text: str):
    print(f"{Colors.RED}❌ {text}{Colors.END}")

def print_info(text: str):
    print(f"{Colors.CYAN}ℹ️  {text}{Colors.END}")

def test_server_status(base_url: str) -> bool:
    """Test if OSRM server is reachable"""
    try:
        response = requests.get(f"{base_url}/", timeout=5)
        if response.status_code == 200:
            print_success(f"Server is reachable at {base_url}")
            return True
        else:
            print_error(f"Server returned status code {response.status_code}")
            return False
    except requests.exceptions.ConnectionError:
        print_error(f"Cannot connect to server at {base_url}")
        print_info("Make sure OSRM server is running")
        return False
    except requests.exceptions.Timeout:
        print_error("Connection timeout")
        return False
    except Exception as e:
        print_error(f"Error: {str(e)}")
        return False

def test_route(base_url: str, start_lon: float, start_lat: float, 
               end_lon: float, end_lat: float, 
               description: str = "Test route") -> bool:
    """Test routing functionality"""
    try:
        # Following OSRM docs format: lon,lat;lon,lat
        url = f"{base_url}/route/v1/driving/{start_lon},{start_lat};{end_lon},{end_lat}"
        params = {
            'steps': 'true',
            'overview': 'full',
            'geometries': 'geojson'
        }
        
        print_info(f"Testing: {description}")
        print(f"   From: ({start_lat}, {start_lon})")
        print(f"   To: ({end_lat}, {end_lon})")
        
        response = requests.get(url, params=params, timeout=10)
        
        if response.status_code != 200:
            print_error(f"Route request failed with status {response.status_code}")
            return False
        
        data = response.json()
        
        if data.get('code') != 'Ok':
            print_error(f"OSRM returned error: {data.get('code')}")
            return False
        
        routes = data.get('routes', [])
        if not routes:
            print_error("No routes found")
            return False
        
        route = routes[0]
        distance_km = route['distance'] / 1000
        duration_min = route['duration'] / 60
        
        print_success(f"Route calculated successfully")
        print(f"   Distance: {distance_km:.2f} km")
        print(f"   Duration: {duration_min:.1f} minutes")
        
        # Check if route has steps
        legs = route.get('legs', [])
        if legs and legs[0].get('steps'):
            step_count = len(legs[0]['steps'])
            print(f"   Steps: {step_count} turn instructions")
        
        return True
        
    except requests.exceptions.Timeout:
        print_error("Route request timeout")
        return False
    except Exception as e:
        print_error(f"Route test failed: {str(e)}")
        return False

def test_table(base_url: str, coordinates: list, description: str = "Distance matrix") -> bool:
    """Test table/matrix functionality"""
    try:
        coord_str = ';'.join([f"{lon},{lat}" for lon, lat in coordinates])
        url = f"{base_url}/table/v1/driving/{coord_str}"
        
        print_info(f"Testing: {description}")
        print(f"   Points: {len(coordinates)}")
        
        response = requests.get(url, timeout=10)
        
        if response.status_code != 200:
            print_error(f"Table request failed with status {response.status_code}")
            return False
        
        data = response.json()
        
        if data.get('code') != 'Ok':
            print_error(f"OSRM returned error: {data.get('code')}")
            return False
        
        durations = data.get('durations', [])
        if durations:
            print_success(f"Distance matrix calculated successfully")
            print(f"   Matrix size: {len(durations)}x{len(durations[0])}")
            return True
        else:
            print_error("No matrix data returned")
            return False
            
    except Exception as e:
        print_error(f"Table test failed: {str(e)}")
        return False

def test_nearest(base_url: str, lon: float, lat: float) -> bool:
    """Test nearest road snap functionality"""
    try:
        url = f"{base_url}/nearest/v1/driving/{lon},{lat}"
        
        print_info(f"Testing: Nearest road snap")
        print(f"   Point: ({lat}, {lon})")
        
        response = requests.get(url, timeout=5)
        
        if response.status_code != 200:
            print_error(f"Nearest request failed with status {response.status_code}")
            return False
        
        data = response.json()
        
        if data.get('code') != 'Ok':
            print_error(f"OSRM returned error: {data.get('code')}")
            return False
        
        waypoints = data.get('waypoints', [])
        if waypoints:
            wp = waypoints[0]
            snapped_loc = wp['location']
            distance = wp.get('distance', 0)
            print_success(f"Nearest point found")
            print(f"   Snapped to: ({snapped_loc[1]}, {snapped_loc[0]})")
            print(f"   Distance: {distance:.2f} meters")
            return True
        else:
            print_error("No waypoints returned")
            return False
            
    except Exception as e:
        print_error(f"Nearest test failed: {str(e)}")
        return False

def get_berlin_coordinates():
    """Get Berlin test coordinates from OSRM documentation"""
    return {
        'start': (13.388860, 52.517037),  # lon, lat
        'end': (13.385983, 52.496891),
        'points': [
            (13.388860, 52.517037),
            (13.385983, 52.496891),
            (13.397634, 52.529407),  # Additional point
        ]
    }

def get_default_coordinates(location: str):
    """Get default test coordinates for common locations"""
    coords = {
        'berlin': get_berlin_coordinates(),
        'rwanda': {
            'start': (30.0588, -1.94995),  # Kigali
            'end': (29.6333, -1.5),  # Ruhengeri
            'points': [
                (30.0588, -1.94995),
                (29.6333, -1.5),
                (30.4167, -1.6833),
            ]
        },
        'kenya': {
            'start': (36.8219, -1.2921),  # Nairobi
            'end': (39.6682, -4.0435),  # Mombasa
            'points': [
                (36.8219, -1.2921),
                (39.6682, -4.0435),
                (36.0667, -0.3031),
            ]
        }
    }
    return coords.get(location.lower(), coords['berlin'])

def main():
    print_header("OSRM Server Test Suite")
    
    # Get server URL
    default_url = "http://127.0.0.1:5001"
    print(f"Default server URL: {default_url}")
    url_input = input(f"Enter OSRM server URL (press Enter for default): ").strip()
    
    base_url = url_input if url_input else default_url
    base_url = base_url.rstrip('/')
    
    # Get location for test coordinates
    print(f"\nTest location (for coordinates): berlin, rwanda, kenya")
    location_input = input(f"Enter location (press Enter for berlin): ").strip()
    location = location_input if location_input else 'berlin'
    
    coords = get_default_coordinates(location)
    
    print_info(f"Testing server at: {base_url}")
    print_info(f"Using coordinates for: {location}")
    
    # Test 1: Server status
    print_header("Test 1: Server Status")
    if not test_server_status(base_url):
        print_error("Server is not running. Exiting tests.")
        sys.exit(1)
    
    # Test 2: Simple route
    print_header("Test 2: Route Calculation")
    start = coords['start']
    end = coords['end']
    if not test_route(base_url, start[0], start[1], end[0], end[1], 
                      f"{location.title()} route test"):
        print_error("Route test failed")
    
    # Test 3: Distance matrix
    print_header("Test 3: Distance Matrix")
    if not test_table(base_url, coords['points'], 
                      f"{len(coords['points'])}-point distance matrix"):
        print_error("Matrix test failed")
    
    # Test 4: Nearest point
    print_header("Test 4: Nearest Road Snap")
    if not test_nearest(base_url, start[0], start[1]):
        print_error("Nearest test failed")
    
    # Summary
    print_header("Test Summary")
    print_success("All tests completed!")
    print_info("Your OSRM server is working correctly")
    
    # Show example from OSRM documentation
    print(f"\n{Colors.BOLD}Example from OSRM docs:{Colors.END}")
    if location.lower() == 'berlin':
        example_url = f"{base_url}/route/v1/driving/13.388860,52.517037;13.385983,52.496891?steps=true"
        print(f"\n{Colors.YELLOW}curl '{example_url}'{Colors.END}\n")
    else:
        print(f"\n{Colors.YELLOW}curl '{base_url}/route/v1/driving/{start[0]},{start[1]};{end[0]},{end[1]}?steps=true'{Colors.END}\n")

if __name__ == "__main__":
    try:
        import requests
        main()
    except ImportError:
        print(f"{Colors.RED}Error: 'requests' library not installed{Colors.END}")
        print(f"{Colors.YELLOW}Install with: pip install requests{Colors.END}")
        sys.exit(1)
