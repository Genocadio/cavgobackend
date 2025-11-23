#!/usr/bin/env python3
"""
Docker Image Builder Script with Terminal GUI

Scans docker-compose.yml for buildable services and builds them with target
image names from docker-compose-hub.yml.
"""

import argparse
import json
import platform
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

import yaml
from rich.console import Console, Group
from rich.layout import Layout
from rich.live import Live
from rich.panel import Panel
from rich.progress import Progress, SpinnerColumn, BarColumn, TextColumn, TimeElapsedColumn, TaskID
from rich.prompt import Confirm, Prompt
from rich.table import Table
from rich.text import Text


class DockerImageBuilder:
    """Main class for building Docker images from docker-compose files."""
    
    def __init__(self, compose_file: str = "docker-compose.yml", 
                 hub_file: str = "docker-compose-hub.yml",
                 base_dir: Optional[str] = None):
        """Initialize the builder.
        
        Args:
            compose_file: Path to docker-compose.yml (relative to base_dir)
            hub_file: Path to docker-compose-hub.yml (relative to base_dir)
            base_dir: Base directory containing compose files (default: parent of script)
        """
        if base_dir is None:
            # Default to parent directory of deployment folder
            self.base_dir = Path(__file__).parent.parent.absolute()
        else:
            self.base_dir = Path(base_dir).absolute()
        
        self.compose_file = self.base_dir / compose_file
        self.hub_file = self.base_dir / hub_file
        self.console = Console()
        
        # Validate files exist
        if not self.compose_file.exists():
            self.console.print(f"[red]Error:[/red] {self.compose_file} not found")
            sys.exit(1)
        if not self.hub_file.exists():
            self.console.print(f"[red]Error:[/red] {self.hub_file} not found")
            sys.exit(1)
        
        # Test Docker connection using subprocess (avoids urllib3/OpenSSL issues)
        try:
            result = subprocess.run(
                ["docker", "info"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=5
            )
            if result.returncode != 0:
                self.console.print(f"[red]Error:[/red] Cannot connect to Docker daemon. Is Docker running?")
                sys.exit(1)
        except FileNotFoundError:
            self.console.print(f"[red]Error:[/red] Docker command not found. Please install Docker.")
            sys.exit(1)
        except subprocess.TimeoutExpired:
            self.console.print(f"[red]Error:[/red] Docker daemon not responding.")
            sys.exit(1)
        except Exception as e:
            self.console.print(f"[red]Error:[/red] Cannot connect to Docker daemon: {e}")
            sys.exit(1)
        
        # Load compose files
        self.compose_data = self._load_yaml(self.compose_file)
        self.hub_data = self._load_yaml(self.hub_file)
        
        # Extract buildable services
        self.services = self._extract_buildable_services()
        
        # Detect platform
        self.arch = platform.machine().lower()
        self.is_arm64 = self.arch == 'arm64' or self.arch == 'aarch64'
        self.use_buildx = False  # Will be set when needed
        
        # Setup logging directory
        self.logs_dir = Path(__file__).parent / "logs"
        self.logs_dir.mkdir(exist_ok=True)
    
    def _load_yaml(self, file_path: Path) -> dict:
        """Load and parse a YAML file."""
        try:
            with open(file_path, 'r') as f:
                return yaml.safe_load(f)
        except yaml.YAMLError as e:
            self.console.print(f"[red]Error:[/red] Failed to parse {file_path}: {e}")
            sys.exit(1)
        except Exception as e:
            self.console.print(f"[red]Error:[/red] Failed to read {file_path}: {e}")
            sys.exit(1)
    
    def _extract_buildable_services(self) -> Dict[str, dict]:
        """Extract services with build.context from compose files.
        
        Returns:
            Dictionary mapping service names to their build info:
            {
                'service_name': {
                    'context': './path/to/context',
                    'dockerfile': 'Dockerfile',
                    'target_image': 'genoyves/cavgo-system:tag',
                    'exists_locally': bool
                }
            }
        """
        services = {}
        
        # Get services from docker-compose.yml
        compose_services = self.compose_data.get('services', {})
        hub_services = self.hub_data.get('services', {})
        
        for service_name, service_config in compose_services.items():
            # Check if service has build context
            if 'build' in service_config:
                build_config = service_config['build']
                if isinstance(build_config, dict) and 'context' in build_config:
                    context = build_config['context']
                    dockerfile = build_config.get('dockerfile', 'Dockerfile')
                    
                    # Get target image from hub file
                    target_image = None
                    if service_name in hub_services:
                        hub_service = hub_services[service_name]
                        if 'image' in hub_service:
                            target_image = hub_service['image']
                    
                    if target_image:
                        # Check if image exists locally
                        exists_locally = self._image_exists_locally(target_image)
                        
                        services[service_name] = {
                            'context': context,
                            'dockerfile': dockerfile,
                            'target_image': target_image,
                            'exists_locally': exists_locally
                        }
        
        return services
    
    def _image_exists_locally(self, image_name: str) -> bool:
        """Check if a Docker image exists locally using subprocess."""
        try:
            result = subprocess.run(
                ["docker", "image", "inspect", image_name],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=5
            )
            return result.returncode == 0
        except Exception:
            return False
    
    def _check_docker_hub_login(self, expected_username: Optional[str] = None) -> bool:
        """Check if user is logged in to Docker Hub.
        
        Args:
            expected_username: Optional username to check for
        
        Returns:
            True if logged in, False otherwise
        """
        try:
            result = subprocess.run(
                ["docker", "info"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                check=False,
                timeout=5
            )
            if result.returncode == 0:
                output = result.stdout
                # Check for username in docker info output
                if expected_username:
                    if f"Username: {expected_username}" in output:
                        return True
                else:
                    # Just check if any username is present
                    if "Username:" in output:
                        return True
            return False
        except Exception:
            return False
    
    def _prompt_docker_login(self) -> bool:
        """Prompt user to login to Docker Hub.
        
        Returns:
            True if login successful, False otherwise
        """
        self.console.print("[yellow]You are not logged in to Docker Hub.[/yellow]")
        if Confirm.ask("Do you want to login now?"):
            try:
                # Run docker login interactively - ensure it's connected to terminal
                # Use sys.stdin/stdout/stderr directly for proper terminal interaction
                result = subprocess.run(
                    ["docker", "login"],
                    check=False,
                    stdin=sys.stdin,
                    stdout=sys.stdout,
                    stderr=sys.stderr
                )
                self.console.print()  # Add newline after login
                return result.returncode == 0
            except Exception as e:
                self.console.print(f"[red]Login failed: {e}[/red]")
                return False
        return False
    
    def _ensure_docker_hub_login(self, image_name: str) -> bool:
        """Ensure user is logged in to Docker Hub, prompt if not.
        
        Args:
            image_name: Image name to extract username from (e.g., genoyves/cavgo-system:tag)
        
        Returns:
            True if logged in (or successfully logged in), False otherwise
        """
        # Extract username from image name (format: username/repo:tag)
        username = None
        if "/" in image_name:
            username = image_name.split("/")[0]
        
        # Check if already logged in
        if self._check_docker_hub_login(username):
            return True
        
        # Prompt for login
        return self._prompt_docker_login()
    
    def _check_image_freshness(self, image_name: str) -> Tuple[bool, str]:
        """Check if local image is the latest version.
        
        Args:
            image_name: Image name to check
        
        Returns:
            Tuple of (is_latest: bool, message: str)
        """
        try:
            # Get local image digest
            result = subprocess.run(
                ["docker", "image", "inspect", image_name, "--format", "{{.Id}}"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                check=False,
                timeout=5
            )
            if result.returncode != 0:
                return False, "Image not found locally"
            
            local_id = result.stdout.strip()
            
            # For now, if image exists locally, consider it ready to push
            # In the future, we could compare with remote digest
            # But user said "if u find its not latest push it" - so we'll push anyway
            return True, f"Local image found: {local_id[:12]}..."
        except Exception as e:
            return False, f"Error checking image: {e}"
    
    def get_services_to_build(self, build_all: bool = False, build_local_only: bool = False) -> List[str]:
        """Get list of service names that should be built.
        
        Args:
            build_all: If True, return all buildable services regardless of existence
            build_local_only: If True, return only services that don't exist locally
        
        Returns:
            List of service names to build
        """
        if build_all:
            return list(self.services.keys())
        elif build_local_only:
            return [name for name, info in self.services.items() if not info['exists_locally']]
        else:
            # Default: build all (for GUI selection)
            return list(self.services.keys())
    
    def _setup_buildx(self) -> bool:
        """Setup Docker buildx for multi-platform builds if needed.
        
        Returns:
            True if buildx is available and set up, False otherwise
        """
        if self.use_buildx:
            try:
                # Check if buildx is available
                result = subprocess.run(
                    ["docker", "buildx", "version"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False
                )
                if result.returncode != 0:
                    return False
                
                # Create or use existing buildx builder
                builder_name = "cavgo-builder"
                result = subprocess.run(
                    ["docker", "buildx", "ls"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    check=False
                )
                
                if builder_name not in result.stdout:
                    # Create new builder
                    subprocess.run(
                        ["docker", "buildx", "create", "--name", builder_name, "--use"],
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        check=False
                    )
                else:
                    # Use existing builder
                    subprocess.run(
                        ["docker", "buildx", "use", builder_name],
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        check=False
                    )
                
                # Bootstrap the builder
                subprocess.run(
                    ["docker", "buildx", "inspect", "--bootstrap"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False
                )
                
                return True
            except Exception:
                return False
        return False
    
    def build_service(self, service_name: str, progress: Optional[Progress] = None, 
                     task_id: Optional[TaskID] = None, multi_platform: bool = True,
                     log_callback: Optional[Callable[[str], None]] = None) -> Tuple[bool, str]:
        """Build a single Docker service.
        
        Args:
            service_name: Name of the service to build
            progress: Optional Rich Progress object for tracking
            task_id: Optional task ID for progress tracking
        
        Returns:
            Tuple of (success: bool, message: str)
        """
        if service_name not in self.services:
            return False, f"Service '{service_name}' not found in buildable services"
        
        service_info = self.services[service_name]
        context_path = self.base_dir / service_info['context']
        dockerfile = service_info['dockerfile']
        target_image = service_info['target_image']
        
        # Validate context exists
        if not context_path.exists():
            return False, f"Build context '{context_path}' does not exist"
        
        # Check if Dockerfile exists
        dockerfile_path = context_path / dockerfile
        if not dockerfile_path.exists():
            return False, f"Dockerfile '{dockerfile_path}' does not exist"
        
        # Update progress
        if progress and task_id is not None:
            progress.update(task_id, description=f"[cyan]Building {service_name}...")
        
        try:
            compose_file = self.compose_file
            
            # Determine build command based on platform requirements
            # Note: --load with buildx doesn't support multi-platform manifest lists
            # So we'll use regular docker compose build for local builds (which works fine)
            # True multi-platform builds would require --push (not implemented yet)
            
            # For now, always use docker compose build for local builds
            # This matches the working behavior and avoids the manifest list issue
            cmd = [
                "docker", "compose",
                "-f", str(compose_file),
                "build", service_name
            ]
            use_compose_tagging = True  # Need to tag after compose build
            
            # If multi-platform was requested, note that we're building for current platform
            # (True multi-platform requires --push which we're not doing yet)
            if multi_platform and progress and task_id is not None:
                platform_note = "arm64" if self.is_arm64 else "amd64"
                progress.update(task_id, description=f"[dim]{service_name}: building for {platform_note} (multi-platform requires push)[/dim]")
            
            # Setup logging for this service
            service_log_dir = self.logs_dir / service_name
            service_log_dir.mkdir(exist_ok=True)
            
            # Create timestamped full log file (never overwritten)
            timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            full_log_file = service_log_dir / f"build-{timestamp}.log"
            
            # Categorized log files (overwritten each build)
            error_log_file = service_log_dir / "error.log"
            success_log_file = service_log_dir / "success.log"
            
            # Execute the build command
            process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                cwd=str(self.base_dir)
            )
            
            # Stream output and update progress, capture all output for logging
            last_line = ""
            full_output = []
            
            # Open log files
            with open(full_log_file, 'w', encoding='utf-8') as full_log:
                # Write header to full log
                full_log.write(f"Build started: {datetime.now().isoformat()}\n")
                full_log.write(f"Service: {service_name}\n")
                full_log.write(f"Target Image: {target_image}\n")
                full_log.write(f"Command: {' '.join(cmd)}\n")
                full_log.write("=" * 80 + "\n\n")
                
                for line in process.stdout:
                    # Keep original line for full log (may already have newline)
                    original_line = line.rstrip('\n\r')
                    line_stripped = line.strip()
                    
                    # Always write to full log
                    full_log.write(line)
                    full_log.flush()  # Ensure immediate write
                    
                    if line_stripped:
                        last_line = line_stripped
                        full_output.append(original_line)
                        
                        # Call log callback if provided (for verbose mode)
                        if log_callback:
                            try:
                                log_callback(line_stripped)
                            except Exception:
                                pass  # Ignore callback errors
                        
                        # Update progress with build output
                        if progress and task_id is not None:
                            # Extract meaningful status from docker compose output
                            if "Building" in line_stripped or "Step" in line_stripped or "--->" in line_stripped:
                                # Truncate long lines for display
                                display_line = line_stripped[:60] + "..." if len(line_stripped) > 60 else line_stripped
                                progress.update(task_id, description=f"[cyan]{service_name}: {display_line}")
            
            # Wait for process to complete
            return_code = process.wait()
            
            # Write footer to full log
            with open(full_log_file, 'a', encoding='utf-8') as full_log:
                full_log.write("\n" + "=" * 80 + "\n")
                full_log.write(f"Build finished: {datetime.now().isoformat()}\n")
                full_log.write(f"Exit code: {return_code}\n")
            
            # Write categorized logs (overwrite previous)
            if return_code != 0:
                # Write to error.log
                with open(error_log_file, 'w', encoding='utf-8') as err_log:
                    err_log.write(f"Build failed: {datetime.now().isoformat()}\n")
                    err_log.write(f"Service: {service_name}\n")
                    err_log.write(f"Target Image: {target_image}\n")
                    err_log.write(f"Exit code: {return_code}\n")
                    err_log.write("=" * 80 + "\n\n")
                    for output_line in full_output:
                        err_log.write(output_line + "\n")
                    err_log.write(f"\n\nLast line: {last_line}\n")
                
                if progress and task_id is not None:
                    progress.update(task_id, description=f"[red]✗ Failed {service_name}")
                return False, f"Build failed: {last_line if last_line else 'Unknown error'}"
            else:
                # Write to success.log
                with open(success_log_file, 'w', encoding='utf-8') as succ_log:
                    succ_log.write(f"Build succeeded: {datetime.now().isoformat()}\n")
                    succ_log.write(f"Service: {service_name}\n")
                    succ_log.write(f"Target Image: {target_image}\n")
                    succ_log.write("=" * 80 + "\n\n")
                    for output_line in full_output:
                        succ_log.write(output_line + "\n")
                    succ_log.write(f"\n\nBuild completed successfully.\n")
            
            # For buildx builds, image is already tagged with target_image, so we're done
            if not use_compose_tagging:
                if progress and task_id is not None:
                    progress.update(task_id, description=f"[green]✓ Built {service_name}")
                return True, f"Successfully built {target_image}"
            
            # After building with compose, tag the image with the target name
            # Use docker compose images to get the exact image name that was built
            images_cmd = [
                "docker", "compose",
                "-f", str(compose_file),
                "images", "--format", "json", service_name
            ]
            
            source_image = None
            try:
                result = subprocess.run(
                    images_cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    cwd=str(self.base_dir)
                )
                if result.returncode == 0 and result.stdout.strip():
                    # Parse JSON output to get image repository and tag
                    for line in result.stdout.strip().split('\n'):
                        if line:
                            try:
                                img_info = json.loads(line)
                                if 'Repository' in img_info and 'Tag' in img_info:
                                    source_image = f"{img_info['Repository']}:{img_info['Tag']}"
                                    break
                            except json.JSONDecodeError:
                                continue
            except Exception:
                pass
            
            # If compose images didn't work, try to find by image ID or common patterns
            if not source_image:
                # Try getting image ID directly
                images_id_cmd = [
                    "docker", "compose",
                    "-f", str(compose_file),
                    "images", "-q", service_name
                ]
                try:
                    result = subprocess.run(
                        images_id_cmd,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True,
                        cwd=str(self.base_dir)
                    )
                    if result.returncode == 0 and result.stdout.strip():
                        image_id = result.stdout.strip().split('\n')[0]
                        # Use image ID directly for tagging
                        tag_cmd = ["docker", "tag", image_id, target_image]
                        tag_result = subprocess.run(
                            tag_cmd,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE,
                            check=False
                        )
                        if tag_result.returncode == 0:
                            source_image = "found"  # Mark as found
                except Exception:
                    pass
            
            # Tag with target image name if we found the source
            if source_image and source_image != "found":
                # Use docker tag command directly
                tag_cmd = ["docker", "tag", source_image, target_image]
                subprocess.run(
                    tag_cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False
                )
            
            if progress and task_id is not None:
                progress.update(task_id, description=f"[green]✓ Built {service_name}")
            
            return True, f"Successfully built {target_image}"
            
        except FileNotFoundError:
            if progress and task_id is not None:
                progress.update(task_id, description=f"[red]✗ Failed {service_name}")
            return False, "Docker command not found. Please ensure Docker is installed and in PATH."
        except Exception as e:
            error_msg = str(e)
            if progress and task_id is not None:
                progress.update(task_id, description=f"[red]✗ Failed {service_name}")
            return False, f"Build failed: {error_msg}"
    
    def push_service(self, service_name: str, progress: Optional[Progress] = None,
                     task_id: Optional[TaskID] = None, multi_platform: bool = True,
                     log_callback: Optional[Callable[[str], None]] = None) -> Tuple[bool, str]:
        """Push a single Docker service image to Docker Hub.
        
        Args:
            service_name: Name of the service to push
            progress: Optional Rich Progress object for tracking
            task_id: Optional task ID for progress tracking
            multi_platform: Whether to push as multi-platform
            log_callback: Optional callback for log lines (for verbose mode)
        
        Returns:
            Tuple of (success: bool, message: str)
        """
        if service_name not in self.services:
            return False, f"Service '{service_name}' not found in buildable services"
        
        service_info = self.services[service_name]
        target_image = service_info['target_image']
        context_path = self.base_dir / service_info['context']
        dockerfile = service_info['dockerfile']
        
        # Check if image exists locally
        if not self._image_exists_locally(target_image):
            return False, f"Image '{target_image}' does not exist locally. Build it first."
        
        # Login check is done in push_services before starting, so we skip it here
        # to avoid duplicate prompts and hanging issues
        
        # Check image freshness before pushing
        # User requirement: "before pushing always check if the image locally is latest
        # even if we select it on push if u find its not latest push it"
        is_fresh, freshness_msg = self._check_image_freshness(target_image)
        if not is_fresh:
            # Image not found or not latest - we'll push anyway (user requirement)
            if progress and task_id is not None:
                progress.update(task_id, description=f"[yellow]{service_name}: {freshness_msg}, will push...[/yellow]")
        
        # Setup push logging
        service_log_dir = self.logs_dir / service_name / "push"
        service_log_dir.mkdir(parents=True, exist_ok=True)
        
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        full_log_file = service_log_dir / f"push-{timestamp}.log"
        error_log_file = service_log_dir / "error.log"
        success_log_file = service_log_dir / "success.log"
        
        # Update progress
        if progress and task_id is not None:
            progress.update(task_id, description=f"[cyan]Pushing {service_name}...")
        
        try:
            # Determine push method based on platform requirements
            if multi_platform and self.is_arm64:
                # Use buildx for multi-platform push
                self.use_buildx = True
                if not self._setup_buildx():
                    # Fallback to single platform push
                    multi_platform = False
                    self.use_buildx = False
                    if progress and task_id is not None:
                        progress.update(task_id, description=f"[yellow]{service_name}: buildx not available, pushing single platform")
            
            if multi_platform and self.use_buildx and self.is_arm64:
                # Use buildx build --push for multi-platform
                platforms = "linux/amd64,linux/arm64"
                cmd = [
                    "docker", "buildx", "build",
                    "--platform", platforms,
                    "-f", str(context_path / dockerfile),
                    "-t", target_image,
                    "--push",  # Push directly
                    str(context_path)
                ]
            else:
                # Use regular docker push for single platform
                cmd = ["docker", "push", target_image]
            
            # Execute push command
            process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                cwd=str(self.base_dir)
            )
            
            # Stream output and capture for logging
            last_line = ""
            full_output = []
            
            with open(full_log_file, 'w', encoding='utf-8') as full_log:
                # Write header
                full_log.write(f"Push started: {datetime.now().isoformat()}\n")
                full_log.write(f"Service: {service_name}\n")
                full_log.write(f"Target Image: {target_image}\n")
                full_log.write(f"Command: {' '.join(cmd)}\n")
                full_log.write("=" * 80 + "\n\n")
                
                for line in process.stdout:
                    original_line = line.rstrip('\n\r')
                    line_stripped = line.strip()
                    
                    # Always write to full log
                    full_log.write(line)
                    full_log.flush()
                    
                    if line_stripped:
                        last_line = line_stripped
                        full_output.append(original_line)
                        
                        # Call log callback if provided
                        if log_callback:
                            try:
                                log_callback(line_stripped)
                            except Exception:
                                pass
                        
                        # Update progress
                        if progress and task_id is not None:
                            if "Pushing" in line_stripped or "Layer" in line_stripped or "Digest:" in line_stripped:
                                display_line = line_stripped[:60] + "..." if len(line_stripped) > 60 else line_stripped
                                progress.update(task_id, description=f"[cyan]{service_name}: {display_line}")
            
            # Wait for process
            return_code = process.wait()
            
            # Write footer
            with open(full_log_file, 'a', encoding='utf-8') as full_log:
                full_log.write("\n" + "=" * 80 + "\n")
                full_log.write(f"Push finished: {datetime.now().isoformat()}\n")
                full_log.write(f"Exit code: {return_code}\n")
            
            # Write categorized logs
            if return_code != 0:
                with open(error_log_file, 'w', encoding='utf-8') as err_log:
                    err_log.write(f"Push failed: {datetime.now().isoformat()}\n")
                    err_log.write(f"Service: {service_name}\n")
                    err_log.write(f"Target Image: {target_image}\n")
                    err_log.write(f"Exit code: {return_code}\n")
                    err_log.write("=" * 80 + "\n\n")
                    for output_line in full_output:
                        err_log.write(output_line + "\n")
                    err_log.write(f"\n\nLast line: {last_line}\n")
                
                if progress and task_id is not None:
                    progress.update(task_id, description=f"[red]✗ Push failed {service_name}")
                return False, f"Push failed: {last_line if last_line else 'Unknown error'}"
            else:
                with open(success_log_file, 'w', encoding='utf-8') as succ_log:
                    succ_log.write(f"Push succeeded: {datetime.now().isoformat()}\n")
                    succ_log.write(f"Service: {service_name}\n")
                    succ_log.write(f"Target Image: {target_image}\n")
                    succ_log.write("=" * 80 + "\n\n")
                    for output_line in full_output:
                        succ_log.write(output_line + "\n")
                    succ_log.write(f"\n\nPush completed successfully.\n")
                
                if progress and task_id is not None:
                    progress.update(task_id, description=f"[green]✓ Pushed {service_name}")
                return True, f"Successfully pushed {target_image}"
        
        except FileNotFoundError:
            if progress and task_id is not None:
                progress.update(task_id, description=f"[red]✗ Failed {service_name}")
            return False, "Docker command not found. Please ensure Docker is installed and in PATH."
        except Exception as e:
            error_msg = str(e)
            if progress and task_id is not None:
                progress.update(task_id, description=f"[red]✗ Failed {service_name}")
            return False, f"Push failed: {error_msg}"
    
    def build_services(self, service_names: List[str], show_progress: bool = True, 
                      multi_platform: bool = True, verbose: bool = True) -> Dict[str, Tuple[bool, str]]:
        """Build multiple Docker services.
        
        Args:
            service_names: List of service names to build
            show_progress: Whether to show progress UI
            multi_platform: Whether to build for multiple platforms
            verbose: Whether to show live build logs in split window
        
        Returns:
            Dictionary mapping service names to (success, message) tuples
        """
        results = {}
        
        if show_progress and verbose:
            # Create split layout with logs on left (small), progress on right (large)
            # Create log buffers for each service
            log_buffers: Dict[str, List[str]] = {name: [] for name in service_names}
            current_service = service_names[0] if service_names else None
            
            # Create progress display
            progress = Progress(
                SpinnerColumn(),
                TextColumn("[progress.description]{task.description}"),
                BarColumn(),
                TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
                TimeElapsedColumn(),
                console=self.console,
                expand=True
            )
            
            total = len(service_names)
            main_task = progress.add_task("[bold blue]Overall Progress", total=total)
            service_tasks = {}
            for service_name in service_names:
                task_id = progress.add_task(f"[dim]{service_name}[/dim]", total=None)
                service_tasks[service_name] = task_id
            
            def make_log_panel() -> Panel:
                """Create log panel showing current service logs."""
                if current_service and log_buffers.get(current_service):
                    # Show last 15 lines of current service (truncate long lines)
                    lines = log_buffers[current_service][-15:]
                    log_lines = []
                    for line in lines:
                        # Truncate to fit in panel
                        if len(line) > 60:
                            log_lines.append(line[:57] + "...")
                        else:
                            log_lines.append(line)
                    log_text = "\n".join(log_lines)
                else:
                    log_text = f"[dim]Waiting for {current_service or 'build'} to start...[/dim]"
                
                return Panel(
                    log_text,
                    title=f"[bold cyan]Build Logs: {current_service or 'N/A'}[/bold cyan]",
                    border_style="cyan"
                )
            
            def make_layout() -> Layout:
                """Create the layout with logs on left, progress on right."""
                layout = Layout()
                layout.split_row(
                    Layout(make_log_panel(), name="logs", size=40),  # Left side, small
                    Layout(progress, name="progress")  # Right side, larger
                )
                return layout
            
            # Build each service with live updates
            def create_callback(svc_name: str, live_ref):
                def callback(line: str):
                    if svc_name in log_buffers:
                        # Keep last 50 lines per service
                        if len(log_buffers[svc_name]) >= 50:
                            log_buffers[svc_name].pop(0)
                        log_buffers[svc_name].append(line)
                        # Trigger layout update
                        try:
                            live_ref.update(make_layout())
                        except Exception:
                            pass  # Ignore update errors
                return callback
            
            with Live(make_layout(), refresh_per_second=10, screen=False, console=self.console) as live:
                for service_name in service_names:
                    current_service = service_name
                    task_id = service_tasks[service_name]
                    
                    # Update layout to show current service
                    live.update(make_layout())
                    
                    # Build with log capture
                    success, message = self.build_service(
                        service_name, 
                        progress, 
                        task_id, 
                        multi_platform,
                        log_callback=create_callback(service_name, live)
                    )
                    results[service_name] = (success, message)
                    progress.update(main_task, advance=1)
                    
                    # Final layout update
                    live.update(make_layout())
        
        elif show_progress:
            # Create progress display with individual service tasks
            with Progress(
                SpinnerColumn(),
                TextColumn("[progress.description]{task.description}"),
                BarColumn(),
                TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
                TimeElapsedColumn(),
                console=self.console,
                expand=True
            ) as progress:
                total = len(service_names)
                main_task = progress.add_task("[bold blue]Overall Progress", total=total)
                
                # Create individual tasks for each service
                service_tasks = {}
                for service_name in service_names:
                    task_id = progress.add_task(f"[dim]{service_name}[/dim]", total=None)
                    service_tasks[service_name] = task_id
                
                # Build each service
                for service_name in service_names:
                    task_id = service_tasks[service_name]
                    success, message = self.build_service(service_name, progress, task_id, multi_platform, None)
                    results[service_name] = (success, message)
                    progress.update(main_task, advance=1)
        else:
            for service_name in service_names:
                success, message = self.build_service(service_name, None, None, multi_platform, None)
                results[service_name] = (success, message)
        
        return results
    
    def push_services(self, service_names: List[str], show_progress: bool = True,
                      multi_platform: bool = True, verbose: bool = True) -> Dict[str, Tuple[bool, str]]:
        """Push multiple Docker services to Docker Hub.
        
        Args:
            service_names: List of service names to push
            show_progress: Whether to show progress UI
            multi_platform: Whether to push as multi-platform
            verbose: Whether to show live push logs in split window
        
        Returns:
            Dictionary mapping service names to (success, message) tuples
        """
        # Check Docker Hub login BEFORE starting push operations (outside Live context)
        # This ensures the login prompt is visible
        if service_names:
            first_service = service_names[0]
            if first_service in self.services:
                target_image = self.services[first_service]['target_image']
                if not self._ensure_docker_hub_login(target_image):
                    # Return failure for all services if login fails
                    return {name: (False, "Docker Hub login required but not completed") 
                           for name in service_names}
        
        results = {}
        
        if show_progress and verbose:
            # Create split layout with logs on left (small), progress on right (large)
            log_buffers: Dict[str, List[str]] = {name: [] for name in service_names}
            current_service = service_names[0] if service_names else None
            
            # Create progress display
            progress = Progress(
                SpinnerColumn(),
                TextColumn("[progress.description]{task.description}"),
                BarColumn(),
                TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
                TimeElapsedColumn(),
                console=self.console,
                expand=True
            )
            
            total = len(service_names)
            main_task = progress.add_task("[bold blue]Overall Push Progress", total=total)
            service_tasks = {}
            for service_name in service_names:
                task_id = progress.add_task(f"[dim]{service_name}[/dim]", total=None)
                service_tasks[service_name] = task_id
            
            def make_log_panel() -> Panel:
                """Create log panel showing current service push logs."""
                if current_service and log_buffers.get(current_service):
                    lines = log_buffers[current_service][-15:]
                    log_lines = []
                    for line in lines:
                        if len(line) > 60:
                            log_lines.append(line[:57] + "...")
                        else:
                            log_lines.append(line)
                    log_text = "\n".join(log_lines)
                else:
                    log_text = f"[dim]Waiting for {current_service or 'push'} to start...[/dim]"
                
                return Panel(
                    log_text,
                    title=f"[bold cyan]Push Logs: {current_service or 'N/A'}[/bold cyan]",
                    border_style="cyan"
                )
            
            def make_layout() -> Layout:
                """Create the layout with logs on left, progress on right."""
                layout = Layout()
                layout.split_row(
                    Layout(make_log_panel(), name="logs", size=40),
                    Layout(progress, name="progress")
                )
                return layout
            
            # Push each service with live updates
            def create_callback(svc_name: str, live_ref):
                def callback(line: str):
                    if svc_name in log_buffers:
                        if len(log_buffers[svc_name]) >= 50:
                            log_buffers[svc_name].pop(0)
                        log_buffers[svc_name].append(line)
                        try:
                            live_ref.update(make_layout())
                        except Exception:
                            pass
                return callback
            
            with Live(make_layout(), refresh_per_second=10, screen=False, console=self.console) as live:
                for service_name in service_names:
                    current_service = service_name
                    task_id = service_tasks[service_name]
                    
                    live.update(make_layout())
                    
                    success, message = self.push_service(
                        service_name,
                        progress,
                        task_id,
                        multi_platform,
                        log_callback=create_callback(service_name, live)
                    )
                    results[service_name] = (success, message)
                    progress.update(main_task, advance=1)
                    
                    live.update(make_layout())
        
        elif show_progress:
            # Create progress display without verbose
            with Progress(
                SpinnerColumn(),
                TextColumn("[progress.description]{task.description}"),
                BarColumn(),
                TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
                TimeElapsedColumn(),
                console=self.console,
                expand=True
            ) as progress:
                total = len(service_names)
                main_task = progress.add_task("[bold blue]Overall Push Progress", total=total)
                
                service_tasks = {}
                for service_name in service_names:
                    task_id = progress.add_task(f"[dim]{service_name}[/dim]", total=None)
                    service_tasks[service_name] = task_id
                
                for service_name in service_names:
                    task_id = service_tasks[service_name]
                    success, message = self.push_service(service_name, progress, task_id, multi_platform, None)
                    results[service_name] = (success, message)
                    progress.update(main_task, advance=1)
        else:
            for service_name in service_names:
                success, message = self.push_service(service_name, None, None, multi_platform, None)
                results[service_name] = (success, message)
        
        return results
    
    def display_services_table(self):
        """Display a table of all buildable services."""
        table = Table(title="Buildable Services", show_header=True, header_style="bold magenta")
        table.add_column("Service Name", style="cyan", no_wrap=True)
        table.add_column("Build Context", style="green")
        table.add_column("Target Image", style="yellow")
        table.add_column("Status", justify="center")
        
        for service_name, info in self.services.items():
            status = "[green]✓ Exists[/green]" if info['exists_locally'] else "[red]✗ Missing[/red]"
            table.add_row(
                service_name,
                info['context'],
                info['target_image'],
                status
            )
        
        self.console.print(table)
    
    def show_build_results(self, results: Dict[str, Tuple[bool, str]], operation: str = "Build"):
        """Display build results summary.
        
        Args:
            results: Dictionary mapping service names to (success, message) tuples
            operation: Operation name ("Build" or "Push")
        """
        success_count = sum(1 for success, _ in results.values() if success)
        total_count = len(results)
        
        # Create results table
        table = Table(title=f"{operation} Results ({success_count}/{total_count} successful)", 
                     show_header=True, header_style="bold magenta")
        table.add_column("Service", style="cyan")
        table.add_column("Status", justify="center")
        table.add_column("Message", style="white")
        
        for service_name, (success, message) in results.items():
            status = "[green]✓ Success[/green]" if success else "[red]✗ Failed[/red]"
            table.add_row(service_name, status, message)
        
        self.console.print("\n")
        self.console.print(table)
        
        # Show log location
        log_type = operation.lower()
        self.console.print(f"\n[dim]{operation} logs saved to: {self.logs_dir}[/dim]")
        if operation == "Build":
            self.console.print(f"[dim]  - Full logs: logs/<service>/build-YYYYMMDD-HHMMSS.log (preserved)[/dim]")
            self.console.print(f"[dim]  - Latest: logs/<service>/error.log or success.log (overwritten)[/dim]")
        else:
            self.console.print(f"[dim]  - Full logs: logs/<service>/push/push-YYYYMMDD-HHMMSS.log (preserved)[/dim]")
            self.console.print(f"[dim]  - Latest: logs/<service>/push/error.log or success.log (overwritten)[/dim]")
        
        if success_count == total_count:
            self.console.print(f"\n[bold green]✓ All {operation.lower()}s completed successfully![/bold green]")
        else:
            self.console.print(f"\n[bold yellow]⚠ {total_count - success_count} {operation.lower()}(s) failed[/bold yellow]")
    
    def show_combined_results(self, build_results: Dict[str, Tuple[bool, str]], 
                             push_results: Optional[Dict[str, Tuple[bool, str]]] = None):
        """Display combined build and push results."""
        self.show_build_results(build_results, "Build")
        
        if push_results:
            self.console.print("\n")
            self.show_build_results(push_results, "Push")


def show_main_menu(builder: DockerImageBuilder, build_all: Optional[bool] = None, 
                   multi_platform: Optional[bool] = None, quick_mode: bool = False) -> Tuple[Optional[List[str]], bool, bool]:
    """Display interactive main menu and return selected services.
    
    Args:
        builder: DockerImageBuilder instance
        build_all: If True, skip service selection question (already answered)
        multi_platform: If set, skip platform question (already answered)
        quick_mode: If True, skip to service selection directly
    
    Returns:
        Tuple of (List of service names to build or None if cancelled, multi_platform bool, will_push bool)
    """
    console = builder.console
    
    # Quick mode: skip directly to service selection
    if quick_mode:
        console.print(Panel.fit("[bold cyan]Docker Image Builder - Quick Mode[/bold cyan]", border_style="cyan"))
        console.print()
        builder.display_services_table()
        console.print()
        
        # Interactive selection with numbered list
        all_services = list(builder.services.keys())
        console.print("[bold]Select services to build:[/bold]")
        console.print("[dim](Enter service numbers separated by commas, or 'all' for all services)[/dim]\n")
        
        # Display numbered list
        for idx, service_name in enumerate(all_services, 1):
            info = builder.services[service_name]
            status = "[green]✓ Exists[/green]" if info['exists_locally'] else "[red]✗ Missing[/red]"
            console.print(f"  [cyan]{idx}.[/cyan] {service_name:20} {status:15} [dim]{info['target_image']}[/dim]")
        
        console.print()
        selection = Prompt.ask("Enter service numbers", default="")
        
        if selection.lower() == "all":
            return all_services, True, False  # multi-platform enabled in quick mode, no push (quick mode handles push separately)
        
        selected = []
        try:
            indices = [int(x.strip()) for x in selection.split(",")]
            for idx in indices:
                if 1 <= idx <= len(all_services):
                    selected.append(all_services[idx - 1])
                else:
                    console.print(f"[yellow]Warning: Invalid index {idx}, skipping[/yellow]")
        except ValueError:
            console.print("[red]Invalid input. Please enter numbers separated by commas.[/red]")
            return None, True, False
        
        if not selected:
            console.print("[yellow]No valid services selected.[/yellow]")
            return None, True, False
        
        return selected, True, False  # multi-platform enabled in quick mode, no push (quick mode handles push separately)
    
    while True:
        # Clear screen (works in most terminals)
        try:
            console.clear()
        except Exception:
            pass  # If clear doesn't work, continue anyway
        
        console.print(Panel.fit("[bold cyan]Docker Image Builder[/bold cyan]", border_style="cyan"))
        console.print()
        
        builder.display_services_table()
        console.print()
        
        # Menu options
        console.print("[bold]Options:[/bold]")
        console.print("  [cyan]1.[/cyan] Build Local (only missing images)")
        console.print("  [cyan]2.[/cyan] Build All (all buildable services)")
        console.print("  [cyan]3.[/cyan] Select Services (interactive selection)")
        console.print("  [cyan]4.[/cyan] Push Images")
        console.print("  [cyan]5.[/cyan] Exit")
        console.print()
        
        choice = Prompt.ask("Select an option", choices=["1", "2", "3", "4", "5"], default="5")
        
        if choice == "1":
            if build_all is None:
                services = builder.get_services_to_build(build_local_only=True)
                if not services:
                    console.print("[yellow]All images already exist locally. Nothing to build.[/yellow]")
                    if not Confirm.ask("\nDo you want to build all anyway?"):
                        continue
                    services = builder.get_services_to_build(build_all=True)
            else:
                services = builder.get_services_to_build(build_all=build_all)
        elif choice == "2":
            if build_all is None:
                services = builder.get_services_to_build(build_all=True)
                if not Confirm.ask(f"\nBuild all {len(services)} service(s)?"):
                    continue
            else:
                services = builder.get_services_to_build(build_all=build_all)
        
        elif choice == "3":
            # Interactive selection with numbered list
            all_services = list(builder.services.keys())
            console.print("\n[bold]Select services to build:[/bold]")
            console.print("[dim](Enter service numbers separated by commas, or 'all' for all services)[/dim]\n")
            
            # Display numbered list
            for idx, service_name in enumerate(all_services, 1):
                info = builder.services[service_name]
                status = "[green]✓ Exists[/green]" if info['exists_locally'] else "[red]✗ Missing[/red]"
                console.print(f"  [cyan]{idx}.[/cyan] {service_name:20} {status:15} [dim]{info['target_image']}[/dim]")
            
            console.print()
            selection = Prompt.ask("Enter service numbers", default="")
            
            if selection.lower() == "all":
                services = all_services
            else:
                selected = []
                try:
                    indices = [int(x.strip()) for x in selection.split(",")]
                    for idx in indices:
                        if 1 <= idx <= len(all_services):
                            selected.append(all_services[idx - 1])
                        else:
                            console.print(f"[yellow]Warning: Invalid index {idx}, skipping[/yellow]")
                except ValueError:
                    console.print("[red]Invalid input. Please enter numbers separated by commas.[/red]")
                    if not Confirm.ask("Try again?"):
                        continue
                    continue
                
                if not selected:
                    console.print("[yellow]No valid services selected.[/yellow]")
                    if not Confirm.ask("Continue?"):
                        continue
                    continue
                services = selected
        
        elif choice == "4":
            # Push menu - handle push directly
            push_services, push_multi_platform = show_push_menu(builder, multi_platform)
            if push_services:
                platform_info = "multi-platform" if push_multi_platform else "current platform only"
                console.print(f"\n[cyan]Pushing {len(push_services)} service(s) ({platform_info})...[/cyan]\n")
                push_results = builder.push_services(push_services, show_progress=True, multi_platform=push_multi_platform, verbose=True)
                builder.show_build_results(push_results, "Push")
                console.print()
                if not Confirm.ask("Return to main menu?", default=True):
                    return None, True, False
            continue  # Return to menu
        
        elif choice == "5":
            return None, True, False
        
        # Ask about platform if not already answered
        if multi_platform is None:
            console.print()
            platform_choice = Prompt.ask(
                "Build for [cyan]multi-platform[/cyan] (linux/amd64,linux/arm64) or [yellow]current platform only[/yellow]?",
                choices=["m", "c", "multi", "current"],
                default="m"
            )
            use_multi_platform = platform_choice in ["m", "multi"]
        else:
            use_multi_platform = multi_platform
        
        # Ask if user wants to push after build
        console.print()
        will_push = Confirm.ask("Push images after build? (multi-platform)", default=False)
        
        # Return services, multi_platform, and push flag
        # We'll use a tuple with 3 elements, but need to update the return type
        return (services, use_multi_platform, will_push)


def show_push_menu(builder: DockerImageBuilder, multi_platform: Optional[bool] = None) -> Tuple[Optional[List[str]], bool]:
    """Display push menu and return selected services to push.
    
    Args:
        builder: DockerImageBuilder instance
        multi_platform: If set, skip platform question
    
    Returns:
        Tuple of (List of service names to push or None if cancelled, multi_platform bool)
    """
    console = builder.console
    
    # Filter services that exist locally
    available_services = [name for name, info in builder.services.items() 
                          if builder._image_exists_locally(info['target_image'])]
    
    if not available_services:
        console.print("[yellow]No images found locally to push. Build images first.[/yellow]")
        if not Confirm.ask("Return to main menu?"):
            return None, True
        return None, True
    
    console.print("\n[bold]Select services to push:[/bold]")
    console.print("[dim](Enter service numbers separated by commas, or 'all' for all services)[/dim]\n")
    
    for idx, service_name in enumerate(available_services, 1):
        info = builder.services[service_name]
        console.print(f"  [cyan]{idx}.[/cyan] {service_name:20} [dim]{info['target_image']}[/dim]")
    
    console.print()
    selection = Prompt.ask("Enter service numbers", default="")
    
    if selection.lower() == "all":
        services = available_services
    else:
        selected = []
        try:
            indices = [int(x.strip()) for x in selection.split(",")]
            for idx in indices:
                if 1 <= idx <= len(available_services):
                    selected.append(available_services[idx - 1])
                else:
                    console.print(f"[yellow]Warning: Invalid index {idx}, skipping[/yellow]")
        except ValueError:
            console.print("[red]Invalid input. Please enter numbers separated by commas.[/red]")
            return None, True
        
        if not selected:
            console.print("[yellow]No valid services selected.[/yellow]")
            return None, True
        services = selected
    
    # Ask about platform if not already answered
    if multi_platform is None:
        console.print()
        platform_choice = Prompt.ask(
            "Push as [cyan]multi-platform[/cyan] (linux/amd64,linux/arm64) or [yellow]current platform only[/yellow]?",
            choices=["m", "c", "multi", "current"],
            default="m"
        )
        use_multi_platform = platform_choice in ["m", "multi"]
    else:
        use_multi_platform = multi_platform
    
    return services, use_multi_platform


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="Build Docker images from docker-compose files",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "-s", "--silent",
        action="store_true",
        help="Auto-build all necessary services (skip existing), show progress only"
    )
    parser.add_argument(
        "-a", "--all",
        action="store_true",
        help="Build all local buildable services regardless of existence"
    )
    parser.add_argument(
        "-l", "--local",
        action="store_true",
        help="Build for current platform only (no multi-platform)"
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Show live build logs in split window (default: enabled)"
    )
    parser.add_argument(
        "-q", "--quick",
        action="store_true",
        help="Quick mode: select images to build, then build with multi-platform and verbose"
    )
    parser.add_argument(
        "-p", "--push",
        action="store_true",
        help="Push images after building (or push only if images exist)"
    )
    parser.add_argument(
        "-b", "--build-only",
        action="store_true",
        help="Build only, do not push (overrides --push)"
    )
    parser.add_argument(
        "--compose-file",
        default="docker-compose.yml",
        help="Path to docker-compose.yml (relative to script parent directory)"
    )
    parser.add_argument(
        "--hub-file",
        default="docker-compose-hub.yml",
        help="Path to docker-compose-hub.yml (relative to script parent directory)"
    )
    
    args = parser.parse_args()
    
    # Determine multi-platform setting
    # Default: multi-platform (True), unless -l flag is set
    multi_platform = not args.local
    
    # Determine verbose setting
    # Default: verbose is True (always show logs)
    verbose = True  # Always verbose by default, including in silent mode
    
    # Determine push setting
    # -b (build-only) overrides -p (push)
    should_push = args.push and not args.build_only
    # Quick mode: push by default unless -b is specified
    if args.quick and not args.build_only:
        should_push = True
    # Silent mode: push only if -p is specified
    if args.silent and not args.push:
        should_push = False
    
    # Initialize builder
    builder = DockerImageBuilder(
        compose_file=args.compose_file,
        hub_file=args.hub_file
    )
    
    build_results = {}
    push_results = {}
    
    # Determine which services to build/push
    if args.quick:
        # Quick mode: select services, then build (and push if not -b)
        services, final_multi_platform = show_main_menu(
            builder,
            quick_mode=True
        )
        
        if services is None:
            builder.console.print("\n[yellow]Build cancelled.[/yellow]")
            return 0
        
        if not services:
            builder.console.print("\n[yellow]No services selected.[/yellow]")
            return 0
        
        platform_info = "multi-platform" if final_multi_platform else "current platform only"
        builder.console.print(f"\n[cyan]Building {len(services)} service(s) ({platform_info}) in quick mode...[/cyan]\n")
        build_results = builder.build_services(services, show_progress=True, multi_platform=final_multi_platform, verbose=True)
        builder.show_build_results(build_results, "Build")
        
        # Push after build if enabled (default in quick mode unless -b)
        if should_push:
            # Filter to only successfully built services
            services_to_push = [name for name in services if build_results.get(name, (False, ""))[0]]
            if services_to_push:
                builder.console.print(f"\n[cyan]Pushing {len(services_to_push)} service(s) ({platform_info})...[/cyan]\n")
                push_results = builder.push_services(services_to_push, show_progress=True, multi_platform=final_multi_platform, verbose=True)
                builder.show_build_results(push_results, "Push")
        
    elif args.silent:
        # Silent mode: build only by default, push if -p is specified
        if args.push and not args.build_only:
            # Push-only mode: push existing images
            available_services = [name for name, info in builder.services.items() 
                                  if builder._image_exists_locally(info['target_image'])]
            if available_services:
                platform_info = "multi-platform" if multi_platform else "current platform only"
                builder.console.print(f"[cyan]Pushing {len(available_services)} service(s) ({platform_info})...[/cyan]\n")
                push_results = builder.push_services(available_services, show_progress=True, multi_platform=multi_platform, verbose=verbose)
                builder.show_build_results(push_results, "Push")
            else:
                builder.console.print("[yellow]No images found locally to push.[/yellow]")
        else:
            # Build mode
            services = builder.get_services_to_build(build_local_only=not args.all, build_all=args.all)
            if not services:
                builder.console.print("[green]All images already exist locally. Nothing to build.[/green]")
                return 0
            
            platform_info = "multi-platform" if multi_platform else "current platform only"
            builder.console.print(f"[cyan]Building {len(services)} service(s) ({platform_info})...[/cyan]\n")
            build_results = builder.build_services(services, show_progress=True, multi_platform=multi_platform, verbose=verbose)
            builder.show_build_results(build_results, "Build")
            
            # Push after build if -p is specified
            if should_push:
                services_to_push = [name for name in services if build_results.get(name, (False, ""))[0]]
                if services_to_push:
                    builder.console.print(f"\n[cyan]Pushing {len(services_to_push)} service(s) ({platform_info})...[/cyan]\n")
                    push_results = builder.push_services(services_to_push, show_progress=True, multi_platform=multi_platform, verbose=verbose)
                    builder.show_build_results(push_results, "Push")
        
    else:
        # Interactive GUI mode (or partial flags without -s)
        # Check if push-only mode
        if args.push and not args.build_only:
            # Push-only: show push menu
            services, final_multi_platform = show_push_menu(builder, multi_platform if args.local else None)
            
            if services is None:
                builder.console.print("\n[yellow]Push cancelled.[/yellow]")
                return 0
            
            if not services:
                builder.console.print("\n[yellow]No services selected.[/yellow]")
                return 0
            
            platform_info = "multi-platform" if final_multi_platform else "current platform only"
            builder.console.print(f"\n[cyan]Pushing {len(services)} service(s) ({platform_info})...[/cyan]\n")
            push_results = builder.push_services(services, show_progress=True, multi_platform=final_multi_platform, verbose=verbose)
            builder.show_build_results(push_results, "Push")
        else:
            # Build mode (with optional push after)
            services, final_multi_platform, will_push = show_main_menu(
                builder, 
                build_all=args.all if args.all else None,
                multi_platform=multi_platform if args.local else None
            )
            
            if services is None:
                builder.console.print("\n[yellow]Build cancelled.[/yellow]")
                return 0
            
            if not services:
                builder.console.print("\n[yellow]No services selected.[/yellow]")
                return 0
            
            platform_info = "multi-platform" if final_multi_platform else "current platform only"
            builder.console.print(f"\n[cyan]Building {len(services)} service(s) ({platform_info})...[/cyan]\n")
            build_results = builder.build_services(services, show_progress=True, multi_platform=final_multi_platform, verbose=verbose)
            builder.show_build_results(build_results, "Build")
            
            # Push after build if user selected it (multi-platform)
            if will_push and not args.build_only:
                services_to_push = [name for name in services if build_results.get(name, (False, ""))[0]]
                if services_to_push:
                    builder.console.print(f"\n[cyan]Pushing {len(services_to_push)} service(s) ({platform_info})...[/cyan]\n")
                    push_results = builder.push_services(services_to_push, show_progress=True, multi_platform=final_multi_platform, verbose=verbose)
                    builder.show_build_results(push_results, "Push")
    
    # Return exit code based on results
    all_results = {**build_results, **push_results} if push_results else build_results
    if all_results:
        failed = any(not success for success, _ in all_results.values())
        return 1 if failed else 0
    
    return 0


if __name__ == "__main__":
    sys.exit(main())

