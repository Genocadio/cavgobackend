"""
Docker operations including Docker Hub API integration and image checking.
Handles comparing remote vs local images and detecting updates.
"""

import subprocess
import json
import re
import warnings
from typing import Optional, Tuple, Dict, List
import requests
from rich.console import Console

# Suppress urllib3 OpenSSL warning for LibreSSL compatibility
warnings.filterwarnings('ignore', category=UserWarning, module='urllib3')


class DockerChecker:
    """Handles Docker operations and image checking."""
    
    def __init__(self, dockerhub_token: Optional[str] = None, log_callback: Optional[callable] = None):
        """Initialize Docker checker.
        
        Args:
            dockerhub_token: Docker Hub API token (optional)
            log_callback: Optional callback function for logging
        """
        self.dockerhub_token = dockerhub_token
        self.log_callback = log_callback
        self.console = Console()
        self.dockerhub_api_base = "https://hub.docker.com/v2"
    
    def _log(self, message: str):
        """Log a message."""
        if self.log_callback:
            self.log_callback(message)
        else:
            self.console.print(f"[dim]{message}[/dim]")
    
    def _parse_image_name(self, image: str) -> Tuple[Optional[str], Optional[str], Optional[str]]:
        """Parse Docker image name into components.
        
        Args:
            image: Full image name (e.g., 'genoyves/cavgo-system:main')
            
        Returns:
            Tuple of (registry, repository, tag)
        """
        # Handle different formats
        if '/' in image:
            parts = image.split('/')
            if len(parts) == 2:
                # Format: username/repo:tag or registry/username/repo:tag
                if '.' in parts[0] or ':' in parts[0]:
                    # Likely registry/username/repo:tag
                    registry = parts[0]
                    repo_tag = parts[1]
                else:
                    # Likely username/repo:tag (Docker Hub)
                    registry = None
                    repo_tag = image
            else:
                # Format: registry/username/repo:tag
                registry = parts[0]
                repo_tag = '/'.join(parts[1:])
        else:
            registry = None
            repo_tag = image
        
        # Split repository and tag
        if ':' in repo_tag:
            repo, tag = repo_tag.rsplit(':', 1)
        else:
            repo = repo_tag
            tag = 'latest'
        
        return (registry, repo, tag)
    
    def get_image_digest_via_api(self, image: str) -> Optional[str]:
        """Get image digest using Docker Hub API.
        
        Args:
            image: Full image name
            
        Returns:
            Image digest or None if not found
        """
        if not self.dockerhub_token:
            return None
        
        try:
            registry, repo, tag = self._parse_image_name(image)
            
            # Only support Docker Hub for now
            if registry and registry != 'docker.io':
                return None
            
            # Docker Hub API endpoint
            url = f"{self.dockerhub_api_base}/repositories/{repo}/tags/{tag}"
            headers = {
                "Authorization": f"Bearer {self.dockerhub_token}",
                "Accept": "application/json"
            }
            
            self._log(f"Checking Docker Hub API for {image}...")
            response = requests.get(url, headers=headers, timeout=10)
            
            if response.status_code == 200:
                data = response.json()
                # Get digest from images array
                if 'images' in data and len(data['images']) > 0:
                    digest = data['images'][0].get('digest')
                    if digest:
                        self._log(f"✓ Got digest from Docker Hub API: {digest[:16]}...")
                        return digest
            else:
                self._log(f"Docker Hub API returned status {response.status_code}")
                
        except Exception as e:
            self._log(f"Error checking Docker Hub API: {e}")
        
        return None
    
    def get_image_digest_via_cli(self, image: str, remote_host: Optional[str] = None) -> Optional[str]:
        """Get image digest using Docker CLI.
        
        Args:
            image: Full image name
            remote_host: Optional remote host to check (via SSH)
            
        Returns:
            Image digest or None if not found
        """
        try:
            if remote_host:
                # Check on remote host via SSH
                command = f"docker image inspect {image} --format '{{{{index .RepoDigests 0}}}}' 2>/dev/null"
                # This would need SSH execution - for now return None
                # The caller should handle SSH execution
                return None
            else:
                # Check locally
                result = subprocess.run(
                    ["docker", "image", "inspect", image, "--format", "{{index .RepoDigests 0}}"],
                    capture_output=True,
                    text=True,
                    timeout=10
                )
                
                if result.returncode == 0 and result.stdout.strip():
                    digest = result.stdout.strip()
                    # Extract just the digest part (after @)
                    if '@' in digest:
                        digest = digest.split('@')[1]
                    self._log(f"✓ Got digest from Docker CLI: {digest[:16]}...")
                    return digest
                    
        except Exception as e:
            self._log(f"Error checking Docker CLI: {e}")
        
        return None
    
    def get_remote_digest(self, image: str, ssh_client=None) -> Optional[str]:
        """Get remote image digest (try API first, fallback to CLI).
        
        Args:
            image: Full image name
            ssh_client: Optional SSH client for remote operations
            
        Returns:
            Image digest or None
        """
        # Try API first
        digest = self.get_image_digest_via_api(image)
        if digest:
            return digest
        
        # Fallback to CLI
        if ssh_client:
            # Get digest from remote host
            exit_code, stdout, stderr = ssh_client.execute(
                f"docker image inspect {image} --format '{{{{index .RepoDigests 0}}}}' 2>/dev/null"
            )
            if exit_code == 0 and stdout.strip():
                digest = stdout.strip()
                if '@' in digest:
                    digest = digest.split('@')[1]
                return digest
        else:
            # Try local CLI
            digest = self.get_image_digest_via_cli(image)
            if digest:
                return digest
        
        return None
    
    def get_deployed_digest(self, container_name: str, ssh_client) -> Optional[str]:
        """Get digest of currently deployed container.
        
        Args:
            container_name: Name of the container
            ssh_client: SSH client for remote operations
            
        Returns:
            Image digest or None
        """
        try:
            # Get container's image ID
            exit_code, stdout, stderr = ssh_client.execute(
                f"docker inspect {container_name} --format '{{{{.Image}}}}' 2>/dev/null"
            )
            
            if exit_code != 0 or not stdout.strip():
                return None
            
            image_id = stdout.strip()
            
            # Get image digest from image ID
            exit_code, stdout, stderr = ssh_client.execute(
                f"docker image inspect {image_id} --format '{{{{index .RepoDigests 0}}}}' 2>/dev/null"
            )
            
            if exit_code == 0 and stdout.strip():
                digest_full = stdout.strip()
                if '@' in digest_full:
                    digest = digest_full.split('@')[1]
                    return digest
                    
        except Exception as e:
            self._log(f"Error getting deployed digest: {e}")
        
        return None
    
    def check_image_updated(self, service_name: str, image: str, container_name: Optional[str], 
                           ssh_client) -> Tuple[bool, Optional[str]]:
        """Check if image has been updated on Docker Hub.
        
        Args:
            service_name: Name of the service
            image: Full image name
            container_name: Name of the container (if deployed)
            ssh_client: SSH client for remote operations
            
        Returns:
            Tuple of (needs_update: bool, remote_digest: str)
        """
        self._log(f"Checking {service_name} ({image})...")
        
        # Get remote digest
        remote_digest = self.get_remote_digest(image, ssh_client)
        if not remote_digest:
            self._log(f"⚠ Could not determine remote digest for {service_name}")
            # Assume update needed if we can't check
            return (True, None)
        
        # Get deployed digest if container exists
        if container_name:
            deployed_digest = self.get_deployed_digest(container_name, ssh_client)
            if deployed_digest:
                if deployed_digest == remote_digest:
                    self._log(f"✓ {service_name} is up to date")
                    return (False, remote_digest)
                else:
                    self._log(f"✓ {service_name} has updates (digest changed)")
                    return (True, remote_digest)
        
        # No deployed container or can't get digest - assume update needed
        self._log(f"✓ {service_name} needs deployment (no existing container or digest)")
        return (True, remote_digest)
    
    def get_services_to_update(self, services: Dict[str, Dict], ssh_client, 
                               skip_portainer: bool = True) -> List[str]:
        """Get list of services that need updates.
        
        Args:
            services: Dictionary of service info (from config)
            ssh_client: SSH client for remote operations
            skip_portainer: Whether to skip portainer service
            
        Returns:
            List of service names that need updates
        """
        services_to_update = []
        
        for service_name, service_info in services.items():
            if skip_portainer and service_name == 'portainer':
                continue
            
            image = service_info.get('image')
            container_name = service_info.get('container_name')
            
            if not image:
                continue
            
            needs_update, _ = self.check_image_updated(
                service_name, image, container_name, ssh_client
            )
            
            if needs_update:
                services_to_update.append(service_name)
        
        return services_to_update

