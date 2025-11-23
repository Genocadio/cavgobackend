"""
Configuration management for deployment script.
Handles constants, configuration loading, and Docker Compose file parsing.
"""

import hashlib
import yaml
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass


@dataclass
class ServiceInfo:
    """Information about a Docker service."""
    name: str
    image: str
    container_name: Optional[str] = None
    depends_on: List[str] = None
    
    def __post_init__(self):
        if self.depends_on is None:
            self.depends_on = []


@dataclass
class DeploymentConfig:
    """Main deployment configuration."""
    target_host: str
    target_user: str
    data_dir: str = "/opt/cavgo-data"
    remote_dir: str = "/opt/cavgo-system"
    compose_file: str = "docker-compose-hub.yml"
    ssh_retry_attempts: int = 3
    ssh_retry_delay: int = 5
    credential_expiry_days: int = 3
    log_lines_keep: int = 100


class ConfigManager:
    """Manages configuration and Docker Compose file operations."""
    
    def __init__(self, base_dir: Optional[Path] = None, compose_file: str = "docker-compose-hub.yml"):
        """Initialize configuration manager.
        
        Args:
            base_dir: Base directory (default: parent of deployment folder)
            compose_file: Name of docker-compose file
        """
        if base_dir is None:
            # Default to parent directory of deployment folder
            self.base_dir = Path(__file__).parent.parent.absolute()
        else:
            self.base_dir = Path(base_dir).absolute()
        
        self.compose_file = self.base_dir / compose_file
        self.compose_data: Optional[Dict] = None
        self.services: Dict[str, ServiceInfo] = {}
        
    def load_compose_file(self) -> Dict:
        """Load and parse docker-compose YAML file.
        
        Returns:
            Parsed YAML data as dictionary
        """
        if not self.compose_file.exists():
            raise FileNotFoundError(f"Docker Compose file not found: {self.compose_file}")
        
        try:
            with open(self.compose_file, 'r') as f:
                self.compose_data = yaml.safe_load(f)
            return self.compose_data
        except yaml.YAMLError as e:
            raise ValueError(f"Failed to parse YAML file: {e}")
        except Exception as e:
            raise IOError(f"Failed to read compose file: {e}")
    
    def extract_services(self) -> Dict[str, ServiceInfo]:
        """Extract service information from docker-compose file.
        
        Returns:
            Dictionary mapping service names to ServiceInfo objects
        """
        if self.compose_data is None:
            self.load_compose_file()
        
        services = {}
        compose_services = self.compose_data.get('services', {})
        
        for service_name, service_config in compose_services.items():
            # Get image name
            image = service_config.get('image', '')
            if not image:
                continue  # Skip services without images
            
            # Get container name
            container_name = service_config.get('container_name')
            
            # Get dependencies
            depends_on = []
            deps = service_config.get('depends_on', {})
            if isinstance(deps, dict):
                depends_on = list(deps.keys())
            elif isinstance(deps, list):
                depends_on = [d if isinstance(d, str) else d.get('service', '') for d in deps]
            
            services[service_name] = ServiceInfo(
                name=service_name,
                image=image,
                container_name=container_name,
                depends_on=depends_on
            )
        
        self.services = services
        return services
    
    def get_service_dependencies(self, service_name: str, include_self: bool = True) -> List[str]:
        """Get all dependencies for a service (recursive).
        
        Args:
            service_name: Name of the service
            include_self: Whether to include the service itself in the result
            
        Returns:
            List of service names including dependencies
        """
        if not self.services:
            self.extract_services()
        
        if service_name not in self.services:
            return [service_name] if include_self else []
        
        dependencies = set()
        if include_self:
            dependencies.add(service_name)
        
        def collect_deps(name: str):
            if name in self.services:
                for dep in self.services[name].depends_on:
                    if dep not in dependencies:
                        dependencies.add(dep)
                        collect_deps(dep)
        
        collect_deps(service_name)
        return list(dependencies)
    
    def resolve_service_list(self, selected_services: List[str]) -> List[str]:
        """Resolve service list with dependencies.
        
        Args:
            selected_services: List of selected service names or ['all']
            
        Returns:
            Resolved list of services including dependencies
        """
        if not self.services:
            self.extract_services()
        
        if 'all' in selected_services or len(selected_services) == 0:
            # Return all services in dependency order
            all_services = list(self.services.keys())
            # Sort by dependencies (services with no deps first)
            sorted_services = []
            remaining = set(all_services)
            
            while remaining:
                # Find services with no unresolved dependencies
                ready = [s for s in remaining 
                        if not any(d in remaining for d in self.services[s].depends_on)]
                if not ready:
                    # Circular dependency or error, just add remaining
                    sorted_services.extend(remaining)
                    break
                sorted_services.extend(sorted(ready))
                remaining -= set(ready)
            
            return sorted_services
        
        # Include dependencies for selected services
        resolved = set()
        for service in selected_services:
            deps = self.get_service_dependencies(service, include_self=True)
            resolved.update(deps)
        
        # Sort by dependencies
        all_resolved = list(resolved)
        sorted_services = []
        remaining = set(all_resolved)
        
        while remaining:
            ready = [s for s in remaining 
                    if s in self.services and 
                    not any(d in remaining for d in self.services[s].depends_on)]
            if not ready:
                sorted_services.extend(remaining)
                break
            sorted_services.extend(sorted(ready))
            remaining -= set(ready)
        
        return sorted_services
    
    def get_file_hash(self, file_path: Path) -> str:
        """Calculate SHA256 hash of a file.
        
        Args:
            file_path: Path to file
            
        Returns:
            Hexadecimal hash string
        """
        sha256 = hashlib.sha256()
        with open(file_path, 'rb') as f:
            for chunk in iter(lambda: f.read(4096), b''):
                sha256.update(chunk)
        return sha256.hexdigest()
    
    def compare_compose_files(self, remote_content: str) -> Tuple[bool, Optional[str]]:
        """Compare local and remote docker-compose files.
        
        Args:
            remote_content: Content of remote docker-compose file
            
        Returns:
            Tuple of (are_different: bool, local_hash: str)
        """
        if not self.compose_file.exists():
            return True, None
        
        local_hash = self.get_file_hash(self.compose_file)
        
        # Calculate remote hash
        remote_hash = hashlib.sha256(remote_content.encode('utf-8')).hexdigest()
        
        return local_hash != remote_hash, local_hash
    
    def get_compose_file_content(self) -> str:
        """Get content of local docker-compose file.
        
        Returns:
            File content as string
        """
        with open(self.compose_file, 'r') as f:
            return f.read()
    
    def get_service_image(self, service_name: str) -> Optional[str]:
        """Get image name for a service.
        
        Args:
            service_name: Name of the service
            
        Returns:
            Image name or None if not found
        """
        if not self.services:
            self.extract_services()
        
        if service_name in self.services:
            return self.services[service_name].image
        return None


