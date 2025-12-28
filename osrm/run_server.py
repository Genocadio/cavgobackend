#!/usr/bin/env python3
"""
Simple OSRM Server Runner
Lists directories and runs the server - no validation
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

def print_info(text: str):
    print(f"{Colors.CYAN}ℹ️  {text}{Colors.END}")

def find_directories():
    """Find all subdirectories"""
    dirs = []
    current_dir = Path('.')
    
    # Skip these common non-location directories
    skip_dirs = {'.git', '.venv', 'venv', '__pycache__', 'node_modules', 
                 '.idea', '.vscode', 'data'}
    
    for item in current_dir.iterdir():
        if item.is_dir() and not item.name.startswith('.') and item.name not in skip_dirs:
            try:
                size = sum(f.stat().st_size for f in item.glob('*') if f.is_file())
                size_mb = size / (1024 * 1024)
            except:
                size_mb = 0
            
            dirs.append({
                'name': item.name,
                'path': str(item.absolute()),
                'size': size_mb
            })
    
    return sorted(dirs, key=lambda x: x['name'])

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
            print_info(f"Invalid port. Using {default}")
            return default
    except ValueError:
        print_error(f"Invalid input. Using {default}")
        return default

def run_server(location_name: str, work_dir: str, port: int):
    """Run OSRM server"""
    print_header(f"Starting OSRM Server: {location_name}")
    print_info(f"Directory: {work_dir}")
    print_info(f"Port: {port}")
    
    # Check if running in a TTY
    import sys
    has_tty = sys.stdin.isatty()
    
    # Build docker command - only use -i if we have a TTY
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
    
    print_info(f"Test: curl http://127.0.0.1:{port}/\n")
    print(f"{Colors.CYAN}Starting server (press Ctrl+C to stop)...{Colors.END}\n")
    
    try:
        subprocess.run(server_cmd)
    except KeyboardInterrupt:
        print_info("\n\nServer stopped")
    except Exception as e:
        print_error(f"Failed: {str(e)}")

def main():
    print_header("OSRM Server Runner")
    
    # Find directories
    dirs = find_directories()
    
    if not dirs:
        print_error("No directories found!")
        print_info("Current: " + os.getcwd())
        sys.exit(1)
    
    # List directories
    print(f"{Colors.GREEN}Available Directories:{Colors.END}\n")
    for i, d in enumerate(dirs, 1):
        print(f"{i}. {Colors.BOLD}{d['name']}{Colors.END}")
        print(f"   {d['path']}")
        if d['size'] > 0:
            print(f"   {d['size']:.1f} MB")
        print()
    
    # Select
    if len(dirs) == 1:
        print_success(f"Auto-selected: {dirs[0]['name']}")
        selected = dirs[0]
    else:
        choice = input(f"Select (1-{len(dirs)}): ").strip()
        try:
            idx = int(choice) - 1
            if 0 <= idx < len(dirs):
                selected = dirs[idx]
                print_success(f"Selected: {selected['name']}")
            else:
                print_error("Invalid selection")
                sys.exit(1)
        except ValueError:
            print_error("Invalid input")
            sys.exit(1)
    
    # Get port
    port = get_port_input()
    
    # Run
    run_server(selected['name'], selected['path'], port)

if __name__ == "__main__":
    main()

