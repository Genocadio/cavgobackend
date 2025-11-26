"""
Firebase credentials detection and deployment.
Handles finding Firebase credentials on host and copying to remote server.
"""

import json
import os
import subprocess
from pathlib import Path
from typing import Optional, Tuple
from rich.console import Console
from rich.prompt import Prompt


class FirebaseManager:
    """Manages Firebase credentials detection and deployment."""
    
    def __init__(self, log_callback: Optional[callable] = None):
        """Initialize Firebase manager.
        
        Args:
            log_callback: Optional callback function for logging
        """
        self.log_callback = log_callback
        self.console = Console()
        self.credentials_path: Optional[Path] = None
        self.target_path = "/opt/cavgo-data/firebase-credentials.json"
    
    def _log(self, message: str):
        """Log a message."""
        if self.log_callback:
            self.log_callback(message)
        else:
            self.console.print(f"[dim]{message}[/dim]")
    
    def detect_credentials(self) -> Optional[Path]:
        """Detect Firebase credentials file on host system.
        
        Returns:
            Path to credentials file or None if not found
        """
        # First, check environment variable
        env_path = os.environ.get('GOOGLE_APPLICATION_CREDENTIALS')
        if env_path:
            path = Path(env_path)
            if path.exists() and path.is_file():
                self._log(f"Found credentials in GOOGLE_APPLICATION_CREDENTIALS: {path}")
                self.credentials_path = path
                return path
        
        # Check common profile files
        profile_files = [
            Path.home() / ".bashrc",
            Path.home() / ".zshrc",
            Path.home() / ".profile",
            Path.home() / ".bash_profile"
        ]
        
        for profile_file in profile_files:
            if not profile_file.exists():
                continue
            
            try:
                # Try to source the profile and get the variable
                result = subprocess.run(
                    ['bash', '-c', f'source {profile_file} 2>/dev/null && echo $GOOGLE_APPLICATION_CREDENTIALS'],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                
                if result.returncode == 0 and result.stdout.strip():
                    env_path = result.stdout.strip()
                    path = Path(env_path)
                    if path.exists() and path.is_file():
                        self._log(f"Found credentials in {profile_file.name}: {path}")
                        self.credentials_path = path
                        return path
            except Exception:
                continue
        
        self._log("Firebase credentials not found on host system")
        return None
    
    def validate_credentials(self, credentials_path: Path) -> bool:
        """Validate Firebase credentials file.
        
        Args:
            credentials_path: Path to credentials file
            
        Returns:
            True if valid
        """
        try:
            # Check if it's a directory
            if credentials_path.is_dir():
                self._log(f"✗ GOOGLE_APPLICATION_CREDENTIALS points to a directory: {credentials_path}")
                return False
            
            # Check if file exists
            if not credentials_path.exists():
                self._log(f"✗ Credentials file does not exist: {credentials_path}")
                return False
            
            # Check if readable
            if not os.access(credentials_path, os.R_OK):
                self._log(f"✗ Credentials file is not readable: {credentials_path}")
                return False
            
            # Validate JSON
            try:
                with open(credentials_path, 'r') as f:
                    data = json.load(f)
                
                # Basic validation - check for required fields
                if not isinstance(data, dict):
                    self._log(f"✗ Credentials file is not a valid JSON object")
                    return False
                
                # Check for common Firebase service account fields
                if 'type' not in data or data.get('type') != 'service_account':
                    self._log(f"⚠ Warning: Credentials file may not be a Firebase service account")
                    # Don't fail, just warn
                
                self._log(f"✓ Credentials file is valid JSON")
                return True
                
            except json.JSONDecodeError as e:
                self._log(f"✗ Credentials file is not valid JSON: {e}")
                return False
                
        except Exception as e:
            self._log(f"✗ Error validating credentials: {e}")
            return False
    
    def check_remote_credentials(self, ssh_client) -> bool:
        """Check if Firebase credentials exist on remote server.
        
        Args:
            ssh_client: SSHClient instance
            
        Returns:
            True if credentials exist on remote
        """
        exit_code, stdout, stderr = ssh_client.execute(f"test -f {self.target_path}")
        return exit_code == 0
    
    def deploy_credentials(self, ssh_client, sudo_password: Optional[str] = None) -> Tuple[bool, str]:
        """Deploy Firebase credentials to remote server.
        
        Args:
            ssh_client: SSHClient instance
            sudo_password: Sudo password if needed
            
        Returns:
            Tuple of (success: bool, message: str)
        """
        if not self.credentials_path:
            # Try to detect again
            if not self.detect_credentials():
                return (False, "Firebase credentials not found on host system")
        
        if not self.validate_credentials(self.credentials_path):
            return (False, f"Invalid credentials file: {self.credentials_path}")
        
        # Copy file to remote /tmp first
        temp_remote_path = "/tmp/firebase-credentials.json"
        
        self._log(f"Copying credentials to remote server...")
        if not ssh_client.put_file(self.credentials_path, temp_remote_path):
            return (False, "Failed to copy credentials file to remote server")
        
        # Move to final location and set permissions
        commands = [
            f"mkdir -p /opt/cavgo-data",
            f"mv -f {temp_remote_path} {self.target_path}",
            f"chmod 444 {self.target_path}",
            f"chown 1001:1001 {self.target_path}"
        ]
        
        for cmd in commands:
            exit_code, stdout, stderr = ssh_client.execute(cmd, sudo=True, sudo_password=sudo_password)
            if exit_code != 0:
                return (False, f"Failed to set up credentials: {stderr}")
        
        self._log(f"✓ Firebase credentials deployed to {self.target_path}")
        return (True, f"Credentials deployed successfully")
    
    def handle_firebase_credentials(self, ssh_client, sudo_password: Optional[str] = None) -> Tuple[bool, str]:
        """Handle Firebase credentials deployment (main entry point).
        
        Args:
            ssh_client: SSHClient instance
            sudo_password: Sudo password if needed
            
        Returns:
            Tuple of (success: bool, message: str)
        """
        # Check host system first
        host_credentials = self.detect_credentials()
        
        if host_credentials:
            # Validate and deploy
            if not self.validate_credentials(host_credentials):
                return (False, f"Invalid credentials file on host: {host_credentials}")
            
            return self.deploy_credentials(ssh_client, sudo_password)
        
        # Host doesn't have credentials - check remote
        self._log("Checking for credentials on remote server...")
        if self.check_remote_credentials(ssh_client):
            self._log(f"✓ Firebase credentials found on remote server at {self.target_path}")
            return (True, "Using existing credentials on remote server")
        
        # Neither host nor remote has credentials - this is an error
        return (False, "Firebase credentials not found on host or remote system. Deployment stopped.")


