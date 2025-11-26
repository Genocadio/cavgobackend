#!/usr/bin/env python3
"""
CavGo Deployment Script with Rich TUI
Deploys services to remote server with intelligent update detection.
"""

import argparse
import sys
import warnings
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Optional, Tuple
import signal

# Suppress urllib3 OpenSSL warning for LibreSSL compatibility
warnings.filterwarnings('ignore', category=UserWarning, module='urllib3')

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from rich.console import Console
from rich.prompt import Prompt, Confirm

from deployment.deploy_config import ConfigManager, DeploymentConfig
from deployment.deploy_credentials import CredentialManager
from deployment.deploy_ssh import SSHClient
from deployment.deploy_firebase import FirebaseManager
from deployment.deploy_docker import DockerChecker
from deployment.deploy_ui import DeploymentUI


class Deployer:
    """Main deployment orchestrator."""
    
    def __init__(self, config: DeploymentConfig, silent: bool = False, update_credentials: bool = False):
        """Initialize deployer.
        
        Args:
            config: Deployment configuration
            silent: Whether to run in silent mode
            update_credentials: Whether to update/overwrite existing credentials
        """
        self.config = config
        self.silent = silent
        self.update_credentials = update_credentials
        self.console = Console()
        
        # Initialize managers
        self.config_manager = ConfigManager(compose_file=config.compose_file)
        self.credential_manager = CredentialManager()
        self.ui = DeploymentUI(log_callback=self._log_to_file)
        self.ssh_client: Optional[SSHClient] = None
        self.firebase_manager = FirebaseManager(log_callback=self._log)
        self.docker_checker: Optional[DockerChecker] = None
        
        # Profile tracking
        self._selected_profile: Optional[str] = None
        self._selected_ip: Optional[str] = None
        
        # Logging
        self.log_file = None
        self._setup_logging()
    
    def _setup_logging(self):
        """Setup logging to file."""
        logs_dir = Path(__file__).parent / "logs"
        logs_dir.mkdir(exist_ok=True)
        
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.log_file = logs_dir / f"deploy-{timestamp}.log"
    
    def _log(self, message: str, level: str = "info"):
        """Log a message.
        
        Args:
            message: Log message
            level: Log level
        """
        timestamp = datetime.now().strftime("%H:%M:%S")
        log_entry = f"[{timestamp}] {message}"
        
        # Write to file
        if self.log_file:
            with open(self.log_file, 'a') as f:
                f.write(log_entry + "\n")
        
        # Add to UI
        self.ui.log(message, level)
        
        # Print to console with appropriate colors
        if level == "error":
            self.console.print(f"[red]{log_entry}[/red]")
        elif level == "warning":
            self.console.print(f"[yellow]{log_entry}[/yellow]")
        elif level == "success":
            self.console.print(f"[green]{log_entry}[/green]")
        else:
            self.console.print(f"[dim]{log_entry}[/dim]")
    
    def _log_to_file(self, message: str):
        """Log callback for file only."""
        if self.log_file:
            timestamp = datetime.now().strftime("%H:%M:%S")
            with open(self.log_file, 'a') as f:
                f.write(f"[{timestamp}] {message}\n")
    
    def _get_credentials(self) -> Dict[str, Optional[str]]:
        """Get credentials using selected profile or default.
        
        Returns:
            Dictionary of credentials
        """
        ip = self._selected_ip if self._selected_ip else self.config.target_host
        profile = self._selected_profile if self._selected_profile else "default"
        return self.credential_manager.get_all_credentials(ip, profile)
    
    def initialize(self):
        """Initialize deployment - load config, check SSH keys."""
        self._log("Initializing deployment...")
        
        # Load docker-compose file
        try:
            self.config_manager.load_compose_file()
            services = self.config_manager.extract_services()
            self._log(f"Loaded {len(services)} services from docker-compose file")
        except Exception as e:
            self._log(f"Failed to load docker-compose file: {e}", "error")
            return False
        
        # Cleanup expired credentials
        self.credential_manager.cleanup_expired()
        
        return True
    
    def authenticate(self) -> Optional[bool]:
        """Handle SSH authentication.
        
        Returns:
            True if authentication successful
            False if authentication failed
            None if interrupted (Ctrl+C) - should retry
        """
        try:
            self._log("Setting up SSH authentication...")
            
            # Handle profile selection if not using -u flag
            selected_profile = None
            selected_ip = self.config.target_host
            
            if not self.update_credentials:
                # Check for profiles for this IP
                profiles = self.credential_manager.list_profiles_for_ip(self.config.target_host)
                
                if len(profiles) > 1:
                    # Multiple profiles exist - show selection
                    all_profiles = self.credential_manager.list_all_profiles()
                    # Filter to profiles for this IP
                    ip_profiles = [p for p in all_profiles if p['ip_address'] == self.config.target_host]
                    
                    if ip_profiles:
                        result = self.ui.select_profile(ip_profiles)
                        if result is None:
                            self._log("Profile selection cancelled", "warning")
                            return None
                        selected_ip, selected_profile = result
                        self._log(f"Using profile: {selected_profile} for {selected_ip}", "success")
                elif len(profiles) == 1:
                    # Single profile - use it automatically
                    selected_profile = profiles[0]
                    self._log(f"Using profile: {selected_profile}", "success")
                # else: no profiles, proceed with normal flow
            
            # Get saved credentials (only if not using -u flag and profile selected)
            credentials = {}
            ssh_password = None
            
            if not self.update_credentials and selected_profile:
                credentials = self.credential_manager.get_all_credentials(selected_ip, selected_profile)
                ssh_password = credentials.get('ssh_password')
            elif not self.update_credentials:
                # Try default profile
                credentials = self.credential_manager.get_all_credentials(self.config.target_host)
                ssh_password = credentials.get('ssh_password')
            
            # Create SSH client - try key-based auth first (including ssh-agent)
            self.ssh_client = SSHClient(
                host=self.config.target_host,
                user=self.config.target_user,
                password=ssh_password,
                key_path=None,  # Let paramiko auto-detect keys
                retry_attempts=self.config.ssh_retry_attempts,
                retry_delay=self.config.ssh_retry_delay,
                log_callback=self._log
            )
            
            # Try connecting with keys first (including ssh-agent and auto-detected keys)
            self._log("Attempting SSH connection with keys (including ssh-agent)...")
            if self.ssh_client.connect(try_keys_first=True):
                self._log("✓ SSH connection established with keys", "success")
            else:
                # Key-based auth failed, try password
                self._log("Key-based authentication failed, trying password...")
                
                if not ssh_password:
                    if not self.silent:
                        try:
                            ssh_password = Prompt.ask("Enter SSH password", password=True)
                        except (KeyboardInterrupt, EOFError):
                            self._log("\n[Ctrl+C] Authentication cancelled", "warning")
                            return None
                    else:
                        self._log("SSH password required but not available in silent mode", "error")
                        return False
                
                # Update SSH client with password
                self.ssh_client.password = ssh_password
                self.ssh_client.client = None  # Reset connection
                
                if not self.ssh_client.connect(try_keys_first=False):
                    self._log("Failed to connect to remote server", "error")
                    return False
                
                self._log("✓ SSH connection established with password", "success")
            
            # Now check if sudo password is needed
            # Test if passwordless sudo works
            self._log("Checking if sudo password is required...")
            exit_code, stdout, stderr = self.ssh_client.execute("sudo -n true 2>&1")
            needs_sudo_password = (exit_code != 0)
            
            sudo_password = None
            if needs_sudo_password:
                self._log("Sudo password required, checking saved credentials...")
                sudo_password = credentials.get('sudo_password')
                
                # Try to authenticate with sudo password (with retry on failure)
                max_attempts = 3
                for attempt in range(max_attempts):
                    if not sudo_password:
                        if not self.silent:
                            try:
                                sudo_password = Prompt.ask("Enter sudo password", password=True)
                            except (KeyboardInterrupt, EOFError):
                                self._log("\n[Ctrl+C] Sudo authentication cancelled", "warning")
                                return None
                        else:
                            self._log("Sudo password required but not available in silent mode", "error")
                            return False
                    
                    # Test sudo with password
                    exit_code, stdout, stderr = self.ssh_client.execute("echo 'test'", sudo=True, sudo_password=sudo_password)
                    if exit_code == 0:
                        self._log("✓ Sudo authentication successful", "success")
                        break
                    else:
                        # Password incorrect
                        self._log("Sudo password incorrect", "error")
                        if attempt < max_attempts - 1:
                            if not self.silent:
                                self._log(f"Please try again ({attempt + 1}/{max_attempts})...")
                                sudo_password = None  # Clear to prompt again
                            else:
                                self._log("Sudo password incorrect and silent mode enabled", "error")
                                return False
                        else:
                            self._log("Maximum sudo password attempts exceeded", "error")
                            return False
            else:
                self._log("✓ Passwordless sudo available", "success")
            
            # Store sudo password for later use (even if None, for passwordless sudo)
            self._sudo_password = sudo_password
            self._selected_profile = selected_profile
            self._selected_ip = selected_ip
            
            # Handle credential saving
            # If -u flag is set, always ask to save (with auto-generated profile name)
            # Otherwise, only ask if credentials don't exist
            if self.update_credentials:
                # -u flag: always prompt to save
                if (ssh_password or sudo_password) and not self.silent:
                    try:
                        if Confirm.ask("Save credentials for future use?"):
                            # Auto-generate profile name
                            profile_name = self.credential_manager.auto_generate_profile_name(self.config.target_host)
                            self._log(f"Using profile name: {profile_name}", "success")
                            
                            # Optionally allow custom name
                            custom_name = Prompt.ask(
                                "Enter custom profile name (or press Enter to use auto-generated)",
                                default=profile_name
                            )
                            if custom_name:
                                profile_name = custom_name
                            
                            # Save credentials to new profile
                            self.credential_manager.save_credential(
                                self.config.target_host, 'username', self.config.target_user, profile_name
                            )
                            if ssh_password:
                                self.credential_manager.save_credential(
                                    self.config.target_host, 'ssh_password', ssh_password, profile_name
                                )
                            if sudo_password:
                                self.credential_manager.save_credential(
                                    self.config.target_host, 'sudo_password', sudo_password, profile_name
                                )
                            self._log(f"Credentials saved to profile: {profile_name}", "success")
                    except (KeyboardInterrupt, EOFError):
                        self._log("\n[Ctrl+C] Skipping credential save", "warning")
            else:
                # Normal flow: only save if credentials don't exist
                existing_creds = self.credential_manager.get_all_credentials(
                    selected_ip if selected_profile else self.config.target_host,
                    selected_profile if selected_profile else "default"
                )
                has_credentials = any([
                    existing_creds.get('username'),
                    existing_creds.get('ssh_password'),
                    existing_creds.get('sudo_password')
                ])
                
                if not has_credentials and (ssh_password or sudo_password) and not self.silent:
                    try:
                        if Confirm.ask("Save credentials for future use?"):
                            # Use selected profile or default
                            profile_name = selected_profile if selected_profile else "default"
                            
                            # Save credentials
                            self.credential_manager.save_credential(
                                self.config.target_host, 'username', self.config.target_user, profile_name
                            )
                            if ssh_password:
                                self.credential_manager.save_credential(
                                    self.config.target_host, 'ssh_password', ssh_password, profile_name
                                )
                            if sudo_password:
                                self.credential_manager.save_credential(
                                    self.config.target_host, 'sudo_password', sudo_password, profile_name
                                )
                            self._log("Credentials saved", "success")
                    except (KeyboardInterrupt, EOFError):
                        self._log("\n[Ctrl+C] Skipping credential save", "warning")
            
            return True
        except KeyboardInterrupt:
            self._log("\n[Ctrl+C] Authentication interrupted", "warning")
            return None
    
    def select_services(self) -> Optional[List[str]]:
        """Select services to deploy.
        
        Returns:
            List of selected service names, or None if cancelled (Ctrl+D)
        """
        services = list(self.config_manager.services.keys())
        
        if self.silent:
            # In silent mode, return all services (will be filtered by update check)
            return services
        
        # Interactive selection (no live display needed for selection)
        try:
            selected = self.ui.select_services_interactive(services, self.config_manager.services)
            return selected
        except (KeyboardInterrupt, EOFError):
            self._log("\n[Ctrl+D] Exiting...", "warning")
            return None
    
    def check_compose_file(self) -> bool:
        """Check and update docker-compose file if needed.
        
        Returns:
            True if successful
        """
        self._log("Checking docker-compose file...")
        
        # Get remote file content
        exit_code, stdout, stderr = self.ssh_client.execute(
            f"cat {self.config.remote_dir}/docker-compose.yml 2>/dev/null"
        )
        
        if exit_code != 0:
            # File doesn't exist, will be copied
            self._log("Remote docker-compose file not found, will copy")
            return True
        
        remote_content = stdout
        
        # Compare files
        different, local_hash = self.config_manager.compare_compose_files(remote_content)
        
        if different:
            self._log("Docker-compose files differ, copying local file...")
            local_content = self.config_manager.get_compose_file_content()
            
            # Copy to remote /tmp first
            temp_file = Path("/tmp/docker-compose-hub.yml")
            temp_file.write_text(local_content)
            
            if self.ssh_client.put_file(temp_file, "/tmp/docker-compose-hub.yml"):
                # Move to final location with sudo
                # Use stored sudo password
                sudo_password = getattr(self, '_sudo_password', None)
                if not sudo_password:
                    credentials = self._get_credentials()
                    sudo_password = credentials.get('sudo_password')
                
                exit_code, stdout, stderr = self.ssh_client.execute(
                    f"mv {self.config.remote_dir}/docker-compose.yml {self.config.remote_dir}/docker-compose.yml.bak 2>/dev/null; "
                    f"mv /tmp/docker-compose-hub.yml {self.config.remote_dir}/docker-compose.yml",
                    sudo=True,
                    sudo_password=sudo_password
                )
                
                if exit_code == 0:
                    self._log("✓ Docker-compose file updated", "success")
                    temp_file.unlink()
                    return True
                else:
                    self._log(f"Failed to move docker-compose file: {stderr}", "error")
                    temp_file.unlink()
                    return False
            else:
                self._log("Failed to copy docker-compose file", "error")
                temp_file.unlink()
                return False
        else:
            self._log("✓ Docker-compose files are identical")
            return True
    
    def handle_firebase(self) -> bool:
        """Handle Firebase credentials deployment.
        
        Returns:
            True if successful or skipped
        """
        self._log("Checking Firebase credentials...")
        
        # Get sudo password (use stored password from authentication)
        sudo_password = getattr(self, '_sudo_password', None)
        
        # If still no sudo password, try to get from credentials
        if not sudo_password:
            credentials = self._get_credentials()
            sudo_password = credentials.get('sudo_password')
        
        # If no sudo password, prompt for it
        if not sudo_password and not self.silent:
            sudo_password = Prompt.ask("Enter sudo password", password=True)
            self._sudo_password = sudo_password
        
        if not sudo_password:
            self._log("Sudo password required for Firebase setup", "error")
            return False
        
        success, message = self.firebase_manager.handle_firebase_credentials(
            self.ssh_client, sudo_password
        )
        
        if not success:
            self._log(f"Firebase credentials error: {message}", "error")
            if "not found on host or remote" in message.lower():
                self._log("Deployment stopped due to missing Firebase credentials", "error")
                return False
        
        self._log(message, "success" if success else "warning")
        return True
    
    def get_services_to_update(self, selected_services: List[str]) -> List[str]:
        """Get list of services that need updates.
        
        Args:
            selected_services: List of selected services
            
        Returns:
            List of services that need updates
        """
        if self.silent:
            # In silent mode, check Docker Hub for updates
            # Get Docker Hub token
            credentials = self._get_credentials()
            dockerhub_token = credentials.get('dockerhub_token')
            
            self.docker_checker = DockerChecker(
                dockerhub_token=dockerhub_token,
                log_callback=self._log
            )
            
            # Prepare service info
            services_info = {}
            for service_name in selected_services:
                if service_name == 'portainer':
                    continue  # Skip portainer in silent mode
                
                service = self.config_manager.services[service_name]
                services_info[service_name] = {
                    'image': service.image,
                    'container_name': service.container_name
                }
            
            # Check for updates
            services_to_update = self.docker_checker.get_services_to_update(
                services_info, self.ssh_client, skip_portainer=True
            )
            
            return services_to_update
        else:
            # In interactive mode, deploy all selected services
            return selected_services
    
    def deploy_services(self, services: List[str]) -> Optional[Dict[str, bool]]:
        """Deploy services with beautiful progress bars.
        
        Args:
            services: List of service names to deploy
            
        Returns:
            Dictionary mapping service names to success status, or None if interrupted
        """
        try:
            results = {}
            deployment_times = {}
            
            # Resolve dependencies
            resolved_services = self.config_manager.resolve_service_list(services)
            
            self._log(f"Deploying {len(resolved_services)} services...")
            
            # Get Docker Compose command
            exit_code, stdout, stderr = self.ssh_client.execute(
                "docker compose version >/dev/null 2>&1 && echo 'compose' || echo 'docker-compose'"
            )
            compose_cmd = "docker compose" if "compose" in stdout else "docker-compose"
            
            # Show progress bar
            progress = None
            if not self.silent:
                with self.ui.show_deployment_progress(resolved_services) as progress:
                    # Pull images
                    for service in resolved_services:
                        if service not in self.config_manager.services:
                            self._log(f"⚠ Service {service} not found in config, skipping", "warning")
                            self.ui.complete_service(service, False)
                            continue
                        
                        service_start_time = datetime.now()
                        service_info = self.config_manager.services[service]
                        image = service_info.image
                        
                        # Update progress
                        self.ui.update_service_progress(service, "Pulling image...", 0)
                        
                        self._log(f"Pulling {image}...")
                        exit_code, stdout, stderr = self.ssh_client.execute(
                            f"cd {self.config.remote_dir} && {compose_cmd} pull {service}"
                        )
                        
                        self.ui.update_service_progress(service, "Pulling image...", 50)
                        
                        if exit_code == 0:
                            self._log(f"✓ Pulled {service}", "success")
                            # Update progress for starting
                            self.ui.update_service_progress(service, "Starting container...", 60)
                            
                            # Start the service
                            start_exit_code, start_stdout, start_stderr = self.ssh_client.execute(
                                f"cd {self.config.remote_dir} && {compose_cmd} up -d {service}"
                            )
                            
                            if start_exit_code == 0:
                                self.ui.update_service_progress(service, "Starting container...", 90)
                                service_time = (datetime.now() - service_start_time).total_seconds()
                                deployment_times[service] = service_time
                                results[service] = True
                                self.ui.complete_service(service, True)
                            else:
                                self._log(f"✗ Failed to start {service}: {start_stderr}", "error")
                                service_time = (datetime.now() - service_start_time).total_seconds()
                                deployment_times[service] = service_time
                                results[service] = False
                                self.ui.complete_service(service, False)
                        else:
                            self._log(f"✗ Failed to pull {service}: {stderr}", "error")
                            service_time = (datetime.now() - service_start_time).total_seconds()
                            deployment_times[service] = service_time
                            results[service] = False
                            self.ui.complete_service(service, False)
                    
                    # Start remaining services in batch if needed (for services that were only pulled)
                    services_to_start = [s for s in resolved_services if s not in results]
                    if services_to_start:
                        for service in services_to_start:
                            self.ui.update_service_progress(service, "Starting container...", 60)
                        
                        self._log("Starting remaining services...")
                        service_list = " ".join(services_to_start)
                        exit_code, stdout, stderr = self.ssh_client.execute(
                            f"cd {self.config.remote_dir} && {compose_cmd} up -d {service_list}"
                        )
                        
                        if exit_code == 0:
                            self._log("✓ Services started", "success")
                            for service in services_to_start:
                                if service not in deployment_times:
                                    deployment_times[service] = 0.5  # Default time
                                results[service] = True
                                self.ui.complete_service(service, True)
                        else:
                            self._log(f"✗ Failed to start services: {stderr}", "error")
                            for service in services_to_start:
                                if service not in deployment_times:
                                    deployment_times[service] = 0.5
                                results[service] = False
                                self.ui.complete_service(service, False)
                    
                    self.console.print()
            else:
                # Silent mode - no progress bar
                for service in resolved_services:
                    if service not in self.config_manager.services:
                        self._log(f"⚠ Service {service} not found in config, skipping", "warning")
                        continue
                    
                    service_start_time = datetime.now()
                    service_info = self.config_manager.services[service]
                    image = service_info.image
                    
                    self._log(f"Pulling {image}...")
                    exit_code, stdout, stderr = self.ssh_client.execute(
                        f"cd {self.config.remote_dir} && {compose_cmd} pull {service}"
                    )
                    
                    if exit_code == 0:
                        self._log(f"✓ Pulled {service}", "success")
                        # Start the service
                        start_exit_code, start_stdout, start_stderr = self.ssh_client.execute(
                            f"cd {self.config.remote_dir} && {compose_cmd} up -d {service}"
                        )
                        
                        if start_exit_code == 0:
                            service_time = (datetime.now() - service_start_time).total_seconds()
                            deployment_times[service] = service_time
                            results[service] = True
                        else:
                            self._log(f"✗ Failed to start {service}: {start_stderr}", "error")
                            service_time = (datetime.now() - service_start_time).total_seconds()
                            deployment_times[service] = service_time
                            results[service] = False
                    else:
                        self._log(f"✗ Failed to pull {service}: {stderr}", "error")
                        service_time = (datetime.now() - service_start_time).total_seconds()
                        deployment_times[service] = service_time
                        results[service] = False
                
                # Start remaining services in batch if needed
                services_to_start = [s for s in resolved_services if s not in results]
                if services_to_start:
                    self._log("Starting remaining services...")
                    service_list = " ".join(services_to_start)
                    exit_code, stdout, stderr = self.ssh_client.execute(
                        f"cd {self.config.remote_dir} && {compose_cmd} up -d {service_list}"
                    )
                    
                    if exit_code == 0:
                        self._log("✓ Services started", "success")
                        for service in services_to_start:
                            if service not in deployment_times:
                                deployment_times[service] = 0.5
                            results[service] = True
                    else:
                        self._log(f"✗ Failed to start services: {stderr}", "error")
                        for service in services_to_start:
                            if service not in deployment_times:
                                deployment_times[service] = 0.5
                            results[service] = False
            
            # Store deployment times for summary
            self.ui.deployment_times = deployment_times
            
            return results
        except KeyboardInterrupt:
            self._log("\n[Ctrl+C] Deployment cancelled", "warning")
            return None
    
    def deploy(self) -> bool:
        """Main deployment workflow.
        
        Returns:
            True if deployment successful
        """
        try:
            # Initialize
            if not self.initialize():
                return False
            
            # Authenticate (with retry on Ctrl+C)
            while True:
                auth_result = self.authenticate()
                if auth_result is True:
                    break
                elif auth_result is None:
                    # Interrupted, ask if user wants to retry
                    if not self.silent:
                        try:
                            if not Confirm.ask("\nRetry authentication?", default=True):
                                return False
                        except (KeyboardInterrupt, EOFError):
                            self._log("\n[Ctrl+D] Exiting...", "warning")
                            return False
                    else:
                        return False
                else:
                    # Authentication failed
                    return False
            
            # Main deployment loop - allow returning to service selection on Ctrl+C
            while True:
                # Select services
                selected_services = self.select_services()
                if selected_services is None:
                    # Ctrl+D pressed - exit
                    return False
                if not selected_services:
                    self._log("No services selected", "warning")
                    if not self.silent:
                        try:
                            if not Confirm.ask("Select services again?", default=True):
                                return False
                        except (KeyboardInterrupt, EOFError):
                            self._log("\n[Ctrl+D] Exiting...", "warning")
                            return False
                    else:
                        return False
                
                # Setup remote directory
                # Use sudo password from authentication (stored in self._sudo_password)
                sudo_password = getattr(self, '_sudo_password', None)
                
                # If still no sudo password, try to get from credentials
                if not sudo_password:
                    credentials = self._get_credentials()
                    sudo_password = credentials.get('sudo_password')
                
                # If no sudo password in credentials, prompt for it
                if not sudo_password and not self.silent:
                    try:
                        sudo_password = Prompt.ask("Enter sudo password", password=True)
                        self._sudo_password = sudo_password  # Store for later use
                    except (KeyboardInterrupt, EOFError):
                        self._log("\n[Ctrl+C] Cancelled, returning to service selection...", "warning")
                        continue  # Return to service selection
                
                if not sudo_password:
                    self._log("Sudo password required but not available", "error")
                    return False
                
                try:
                    self._log("Setting up remote directories...")
                    exit_code, stdout, stderr = self.ssh_client.execute(
                        f"mkdir -p {self.config.remote_dir}",
                        sudo=True,
                        sudo_password=sudo_password
                    )
                    
                    if exit_code != 0:
                        self._log(f"Failed to create remote directory: {stderr}", "error")
                        return False
                except KeyboardInterrupt:
                    self._log("\n[Ctrl+C] Cancelled, returning to service selection...", "warning")
                    continue  # Return to service selection
                
                # Check compose file
                try:
                    if not self.check_compose_file():
                        return False
                except KeyboardInterrupt:
                    self._log("\n[Ctrl+C] Cancelled, returning to service selection...", "warning")
                    continue  # Return to service selection
                
                # Always copy init-multiple-dbs.sh if it exists
                try:
                    init_script = self.config_manager.base_dir / "init-multiple-dbs.sh"
                    if init_script.exists():
                        self._log("Copying init-multiple-dbs.sh...")
                        if self.ssh_client.put_file(init_script, f"{self.config.remote_dir}/init-multiple-dbs.sh"):
                            self._log("✓ init-multiple-dbs.sh copied", "success")
                        else:
                            self._log("⚠ Failed to copy init-multiple-dbs.sh", "warning")
                except KeyboardInterrupt:
                    self._log("\n[Ctrl+C] Cancelled, returning to service selection...", "warning")
                    continue  # Return to service selection
                
                # Handle Firebase
                try:
                    if not self.handle_firebase():
                        return False
                except KeyboardInterrupt:
                    self._log("\n[Ctrl+C] Cancelled, returning to service selection...", "warning")
                    continue  # Return to service selection
                
                # Get services to update
                services_to_update = self.get_services_to_update(selected_services)
                
                if not services_to_update:
                    self._log("No services need updates", "success")
                    if not self.silent:
                        self.console.print("\n[green]✓ All services are up to date![/green]\n")
                    # Ask if user wants to deploy more services
                    if not self.silent:
                        try:
                            if not Confirm.ask("\nDeploy more services?", default=False):
                                break
                        except (KeyboardInterrupt, EOFError):
                            self._log("\n[Ctrl+D] Exiting...", "warning")
                            break
                    else:
                        break
                
                # Deploy services
                if not self.silent:
                    self.console.print(f"\n[bold blue]Deploying {len(services_to_update)} service(s)...[/bold blue]\n")
                
                results = self.deploy_services(services_to_update)
                
                # Handle interruption during deployment
                if results is None:
                    # Deployment was cancelled with Ctrl+C
                    if not self.silent:
                        try:
                            if Confirm.ask("\nReturn to service selection?", default=True):
                                continue  # Go back to service selection
                            else:
                                return False
                        except (KeyboardInterrupt, EOFError):
                            self._log("\n[Ctrl+D] Exiting...", "warning")
                            return False
                    else:
                        return False
                
                # Show summary
                if not self.silent:
                    self.ui.show_summary(results, self.ui.deployment_times)
                
                # Check if all succeeded
                all_success = all(results.values())
                
                if all_success:
                    self._log("✓ Deployment completed successfully", "success")
                else:
                    failed = [s for s, success in results.items() if not success]
                    self._log(f"✗ Deployment completed with errors: {', '.join(failed)}", "error")
                
                # Ask if user wants to deploy more services
                if not self.silent:
                    try:
                        if not Confirm.ask("\nDeploy more services?", default=False):
                            break
                    except (KeyboardInterrupt, EOFError):
                        self._log("\n[Ctrl+D] Exiting...", "warning")
                        break
                else:
                    break
            
            return True
            
        except KeyboardInterrupt:
            # This should only catch interrupts not handled by inner try-except blocks
            self._log("\n[Ctrl+C] Deployment interrupted, exiting...", "warning")
            return False
        except Exception as e:
            self._log(f"Deployment failed: {e}", "error")
            import traceback
            self._log(traceback.format_exc(), "error")
            return False
        finally:
            if self.ssh_client:
                self.ssh_client.disconnect()


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(description="Deploy CavGo services")
    parser.add_argument("--host", default="api.gocavgo.com", help="Target host")
    parser.add_argument("--user", help="SSH username")
    parser.add_argument("-u", "--update-credentials", action="store_true", dest="update_credentials", help="Skip saved credentials and prompt for new ones")
    parser.add_argument("-d", "--credential-manager", action="store_true", dest="credential_manager", help="Launch credential manager UI")
    parser.add_argument("--silent", action="store_true", help="Silent mode (auto-detect updates)")
    parser.add_argument("--compose-file", default="docker-compose-hub.yml", help="Docker Compose file")
    
    args = parser.parse_args()
    
    # Handle credential manager mode
    if args.credential_manager:
        from deployment.deploy_credential_ui import CredentialManagerUI
        ui = CredentialManagerUI()
        ui.run()
        sys.exit(0)
    
    # Check for saved credentials first
    from deployment.deploy_credentials import CredentialManager
    credential_manager = CredentialManager()
    
    # Get username from default profile if not using -u flag
    saved_username = None
    if not args.update_credentials:
        saved_credentials = credential_manager.get_all_credentials(args.host, "default")
        saved_username = saved_credentials.get('username')
    
    # Get username if not provided
    if not args.user:
        if saved_username and not args.update_credentials:
            # Use saved username
            args.user = saved_username
            console = Console()
            console.print(f"[dim]Using saved username: [bright_cyan]{args.user}[/bright_cyan][/dim]")
        else:
            try:
                args.user = Prompt.ask("Enter SSH username")
            except (KeyboardInterrupt, EOFError):
                print("\n[Ctrl+D] Exiting...")
                sys.exit(0)
    else:
        # User provided username, check if it's different from saved
        if saved_username and args.user != saved_username and not args.update_credentials:
            # Username changed, set update flag
            args.update_credentials = True
    
    # Create config
    config = DeploymentConfig(
        target_host=args.host,
        target_user=args.user,
        compose_file=args.compose_file
    )
    
    # Create deployer
    deployer = Deployer(config, silent=args.silent, update_credentials=args.update_credentials)
    
    # Run deployment
    success = deployer.deploy()
    
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()

