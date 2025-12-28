#!/usr/bin/env python3
"""
Quick Start OSRM Server
Starts server for already-setup locations
"""

import os
import sys
import subprocess
from pathlib import Path

class Colors:
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
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

def print_warning(text: str):
    print(f"{Colors.YELLOW}⚠️  {text}{Colors.END}")

def print_info(text: str):
    print(f"{Colors.CYAN}ℹ️  {text}{Colors.END}")

def find_locations():
    """Find all directories that could be OSRM locations"""
    locations = []
    current_dir = Path('.')
    
    # Skip these common non-location directories
    skip_dirs = {'.git', '.venv', 'venv', '__pycache__', 'node_modules', 
                 '.idea', '.vscode', 'data'}
    
    for item in current_dir.iterdir():
        if item.is_dir() and not item.name.startswith('.') and item.name not in skip_dirs:
            # List any directory, check if it has OSRM data
            osrm_file = item / f"{item.name}-latest.osrm"
            osm_file = item / f"{item.name}-latest.osm.pbf"
            
            try:
                size = sum(f.stat().st_size for f in item.glob('*') if f.is_file())
                size_mb = size / (1024 * 1024)
            except:
                size_mb = 0
            
            locations.append({
                'name': item.name,
                'path': str(item.absolute()),
                'size': size_mb,
                'has_osrm': osrm_file.exists(),
                'has_map': osm_file.exists()
            })
    
    return sorted(locations, key=lambda x: x['name'])

def get_port_input(default: int = 5001) -> int:
    """Get port number from user"""
    print(f"\n{Colors.CYAN}Port Configuration:{Colors.END}")
    print(f"Default: {default} (macOS uses 5000 for AirPlay)")
    
    port_input = input(f"Enter port (press Enter for {default}): ").strip()
    
    if not port_input:
        print_success(f"Using port: {default}")
        return default
    
    try:
        port = int(port_input)
        if 1 <= port <= 65535:
            print_success(f"Using port: {port}")
            return port
        else:
            print_warning(f"Invalid port. Using {default}")
            return default
    except ValueError:
        print_error(f"Invalid input. Using {default}")
        return default

def start_server(location: dict, port: int):
    """Start OSRM server for location"""
    location_name = location['name']
    work_dir = location['path']
    
    print_header(f"Starting OSRM Server: {location_name}")
    print_info(f"Location: {work_dir}")
    print_info(f"Port: {port}")
    
    # Check if running in a TTY - only use -i if we have a TTY
    import sys
    has_tty = sys.stdin.isatty()
    
    server_cmd = ['docker', 'run']
    if has_tty:
        server_cmd.extend(['-t', '-i'])
    else:
        server_cmd.append('-t')
    
    server_cmd.extend([
        '-p', f'{port}:5000',
        '-v', f'{work_dir}:/data',
        'ghcr.io/project-osrm/osrm-backend',
        'osrm-routed', '--algorithm', 'mld', f'/data/{location_name}-latest.osrm'
    ])
    
    cmd_str = ' '.join(server_cmd)
    print(f"\n{Colors.GREEN}Command:{Colors.END}")
    print(f"{Colors.YELLOW}{cmd_str}{Colors.END}\n")
    
    print_warning("Server will run in foreground. Press Ctrl+C to stop.")
    print_info(f"Test: curl http://127.0.0.1:{port}/\n")
    
    print(f"{Colors.CYAN}Starting server...{Colors.END}\n")
    
    try:
        subprocess.run(server_cmd)
    except KeyboardInterrupt:
        print_info("\n\nServer stopped by user")
    except Exception as e:
        print_error(f"Failed to start server: {str(e)}")

def main():
    print_header("OSRM Quick Start Server")
    
    # Find available locations
    locations = find_locations()
    
    if not locations:
        print_warning("No directories found in current location!")
        print_info("Current directory: " + os.getcwd())
        print_info("\nTip: Run 'python3 osrm_setup.py' to set up a location first")
        sys.exit(1)
    
    # Display available locations
    print(f"{Colors.CYAN}Legend: {Colors.GREEN}✓{Colors.END} = Ready  {Colors.YELLOW}⚠{Colors.END} = Needs setup\n{Colors.END}")
    print(f"{Colors.GREEN}Available Directories:{Colors.END}\n")
    for i, loc in enumerate(locations, 1):
        status_icon = "✓" if loc['has_osrm'] else "⚠"
        status_color = Colors.GREEN if loc['has_osrm'] else Colors.YELLOW
        
        print(f"{i}. {Colors.BOLD}{loc['name']}{Colors.END} {status_color}{status_icon}{Colors.END}")
        print(f"   Path: {loc['path']}")
        if loc['size'] > 0:
            print(f"   Size: {loc['size']:.1f} MB")
        if not loc['has_osrm']:
            print(f"   {Colors.YELLOW}Note: May need setup first{Colors.END}")
        print()
    
    # Select location
    if len(locations) == 1:
        print_info(f"Only one directory found: {locations[0]['name']}")
        choice = "1"
    else:
        choice = input(f"Select directory (1-{len(locations)}): ").strip()
    
    try:
        idx = int(choice) - 1
        if 0 <= idx < len(locations):
            selected = locations[idx]
            print_success(f"Selected: {selected['name']}")
            
            # Warn if not fully set up
            if not selected['has_osrm']:
                print_warning(f"Directory '{selected['name']}' may not have OSRM data")
                print_info("If server fails, run: python3 osrm_setup.py")
                cont = input("\nContinue anyway? (y/n): ").strip().lower()
                if cont != 'y':
                    print_info("Cancelled")
                    sys.exit(0)
        else:
            print_error("Invalid selection")
            sys.exit(1)
    except ValueError:
        print_error("Invalid input")
        sys.exit(1)
    
    # Get port
    port = get_port_input()
    
    # Start server
    start_server(selected, port)

if __name__ == "__main__":
    main()

