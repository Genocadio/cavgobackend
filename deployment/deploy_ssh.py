"""
SSH operations with retry logic using Paramiko.
Handles SSH connections, command execution, and SCP file transfers.
"""

import time
import os
from pathlib import Path
from typing import Optional, Tuple, Callable
import paramiko
from scp import SCPClient
from rich.console import Console


def test_ssh_credentials(host: str, username: str, password: Optional[str] = None,
                         sudo_password: Optional[str] = None, timeout: int = 10) -> Tuple[bool, str]:
    """Test SSH credentials without saving them.
    
    Args:
        host: Remote hostname or IP
        username: SSH username
        password: SSH password (if not using keys)
        sudo_password: Optional sudo password to test
        timeout: Connection timeout in seconds
        
    Returns:
        Tuple of (success: bool, error_message: str)
    """
    client = None
    try:
        # Create SSH client
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        
        # Try to connect
        try:
            client.connect(
                hostname=host,
                username=username,
                password=password,
                timeout=timeout,
                look_for_keys=password is None,  # Only look for keys if no password provided
                allow_agent=True
            )
        except paramiko.AuthenticationException:
            return False, "SSH authentication failed"
        except paramiko.SSHException as e:
            return False, f"SSH connection error: {str(e)}"
        except Exception as e:
            return False, f"Connection error: {str(e)}"
        
        # Test basic command execution
        try:
            stdin, stdout, stderr = client.exec_command("echo 'test'", timeout=5)
            exit_code = stdout.channel.recv_exit_status()
            if exit_code != 0:
                return False, f"Command execution failed with exit code {exit_code}"
        except Exception as e:
            return False, f"Command execution error: {str(e)}"
        
        # Test sudo if password provided
        if sudo_password:
            try:
                # Test sudo with password
                command = f"printf '%s\\n' '{sudo_password}' | sudo -S echo 'sudo_test'"
                stdin, stdout, stderr = client.exec_command(command, timeout=5)
                exit_code = stdout.channel.recv_exit_status()
                stdout_text = stdout.read().decode('utf-8').strip()
                
                if exit_code != 0 or 'sudo_test' not in stdout_text:
                    error_msg = stderr.read().decode('utf-8').strip()
                    return False, f"Sudo authentication failed: {error_msg}"
            except Exception as e:
                return False, f"Sudo test error: {str(e)}"
        
        return True, "Credentials validated successfully"
        
    except Exception as e:
        return False, f"Unexpected error: {str(e)}"
    finally:
        if client:
            try:
                client.close()
            except:
                pass


class SSHClient:
    """SSH client with retry logic and credential management."""
    
    def __init__(self, host: str, user: str, password: Optional[str] = None,
                 key_path: Optional[Path] = None, retry_attempts: int = 3,
                 retry_delay: int = 5, log_callback: Optional[Callable[[str], None]] = None):
        """Initialize SSH client.
        
        Args:
            host: Remote hostname or IP
            user: SSH username
            password: SSH password (if not using keys)
            key_path: Path to SSH private key
            retry_attempts: Number of retry attempts
            retry_delay: Delay between retries (seconds)
            log_callback: Optional callback function for logging
        """
        self.host = host
        self.user = user
        self.password = password
        self.key_path = key_path
        self.retry_attempts = retry_attempts
        self.retry_delay = retry_delay
        self.log_callback = log_callback
        self.client: Optional[paramiko.SSHClient] = None
        self.console = Console()
    
    def _log(self, message: str):
        """Log a message."""
        if self.log_callback:
            self.log_callback(message)
        else:
            self.console.print(f"[dim]{message}[/dim]")
    
    def _check_ssh_keys(self) -> bool:
        """Check if SSH keys are available.
        
        Returns:
            True if SSH keys found
        """
        if self.key_path and self.key_path.exists():
            return True
        
        # Check default SSH key locations
        ssh_dir = Path.home() / ".ssh"
        default_keys = [
            ssh_dir / "id_rsa",
            ssh_dir / "id_ed25519",
            ssh_dir / "id_ecdsa",
            ssh_dir / "id_dsa"
        ]
        
        for key_file in default_keys:
            if key_file.exists():
                self.key_path = key_file
                return True
        
        return False
    
    def _mask_password_in_command(self, command: str, password: Optional[str] = None) -> str:
        """Mask password in command string for logging.
        
        Args:
            command: Command string that may contain password
            password: Password to mask (if None, tries to detect)
            
        Returns:
            Command string with password masked
        """
        if not password:
            # Try to detect password in printf pattern: printf '%s\n' 'password' | ...
            import re
            # Match: printf '%s\n' '...' | sudo
            pattern = r"(printf '%s\\n' ')([^']+)(' \| sudo)"
            match = re.search(pattern, command)
            if match:
                password = match.group(2)
        
        if password:
            # Replace password with asterisks
            # The password in the command might be escaped, so we need to handle both cases
            masked = '*' * len(password)
            # Try direct replacement first
            command = command.replace(f"'{password}'", f"'{masked}'")
            # Also try with escaped quotes (in case password contains quotes)
            escaped_pwd = password.replace("'", "'\\''")
            if escaped_pwd != password:
                escaped_masked = '*' * len(password)
                command = command.replace(f"'{escaped_pwd}'", f"'{escaped_masked}'")
        
        return command
    
    def connect(self, try_keys_first: bool = True) -> bool:
        """Connect to remote host with retry logic.
        
        Args:
            try_keys_first: If True, try key-based auth first (including ssh-agent)
                           If False, only try password auth
        
        Returns:
            True if connection successful
        """
        for attempt in range(1, self.retry_attempts + 1):
            try:
                self._log(f"SSH connection attempt {attempt}/{self.retry_attempts} to {self.user}@{self.host}")
                
                self.client = paramiko.SSHClient()
                self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
                
                if try_keys_first:
                    # Try key-based authentication first (including ssh-agent and auto-detected keys)
                    try:
                        # Use paramiko's automatic key detection
                        # This will try:
                        # 1. Keys specified in SSH config
                        # 2. Keys in ~/.ssh/ (id_rsa, id_ed25519, etc.)
                        # 3. ssh-agent
                        connect_kwargs = {
                            'hostname': self.host,
                            'username': self.user,
                            'timeout': 10,
                            'look_for_keys': True,  # Auto-detect keys in ~/.ssh/
                            'allow_agent': True,    # Use ssh-agent
                        }
                        
                        # If specific key path provided, use it
                        if self.key_path and self.key_path.exists():
                            connect_kwargs['key_filename'] = str(self.key_path)
                            connect_kwargs['look_for_keys'] = False
                        
                        self.client.connect(**connect_kwargs)
                        self._log(f"✓ Connected using SSH key authentication")
                        return True
                    except Exception as e:
                        self._log(f"SSH key authentication failed: {e}")
                        # Fall through to password auth if key fails
                
                # Try password authentication
                if self.password:
                    try:
                        self.client.connect(
                            hostname=self.host,
                            username=self.user,
                            password=self.password,
                            timeout=10,
                            allow_agent=False,
                            look_for_keys=False
                        )
                        self._log(f"✓ Connected using password authentication")
                        return True
                    except Exception as e:
                        self._log(f"Password authentication failed: {e}")
                        raise
                else:
                    raise ValueError("No SSH key or password provided")
                    
            except Exception as e:
                self._log(f"Connection attempt {attempt} failed: {e}")
                if attempt < self.retry_attempts:
                    self._log(f"Retrying in {self.retry_delay} seconds...")
                    time.sleep(self.retry_delay)
                else:
                    self._log(f"✗ All connection attempts failed")
                    return False
        
        return False
    
    def disconnect(self):
        """Close SSH connection."""
        if self.client:
            self.client.close()
            self.client = None
    
    def execute(self, command: str, sudo: bool = False, sudo_password: Optional[str] = None) -> Tuple[int, str, str]:
        """Execute command on remote host with retry logic.
        
        Args:
            command: Command to execute
            sudo: Whether to execute with sudo
            sudo_password: Sudo password if needed
            
        Returns:
            Tuple of (exit_code, stdout, stderr)
        """
        if not self.client:
            if not self.connect():
                return (1, "", "Not connected")
        
        if sudo:
            if sudo_password:
                # Use sudo -S to read password from stdin
                # Properly escape the command for bash -c
                # Replace single quotes with: '\'' (end quote, escaped quote, start quote)
                escaped_command = command.replace("'", "'\\''")
                # Escape the password itself if it contains special characters
                escaped_password = sudo_password.replace("'", "'\\''")
                # Use printf instead of echo for better password handling
                # The format: printf '%s\n' 'password' | sudo -S bash -c 'command'
                command = f"printf '%s\\n' '{escaped_password}' | sudo -S bash -c '{escaped_command}'"
                # Mask the password in the command (use escaped_password since that's what's in the command)
                masked_command = self._mask_password_in_command(command, escaped_password)
                self._log(f"Using sudo with password")
            else:
                # No password provided - this will fail but we'll try anyway
                self._log("Warning: No sudo password provided, command may fail")
                command = f"sudo {command}"
                masked_command = command
        
        for attempt in range(1, self.retry_attempts + 1):
            try:
                # Log masked command (or original if no password)
                log_command = masked_command if sudo and sudo_password else command
                self._log(f"Executing: {log_command[:100]}...")
                stdin, stdout, stderr = self.client.exec_command(command, timeout=30)
                
                exit_code = stdout.channel.recv_exit_status()
                stdout_text = stdout.read().decode('utf-8')
                stderr_text = stderr.read().decode('utf-8')
                
                if exit_code == 0:
                    self._log(f"✓ Command executed successfully")
                else:
                    self._log(f"Command exited with code {exit_code}")
                
                return (exit_code, stdout_text, stderr_text)
                
            except Exception as e:
                self._log(f"Command execution attempt {attempt} failed: {e}")
                if attempt < self.retry_attempts:
                    self._log(f"Retrying in {self.retry_delay} seconds...")
                    time.sleep(self.retry_delay)
                    # Reconnect if needed
                    if not self.client.get_transport() or not self.client.get_transport().is_active():
                        self.connect()
                else:
                    return (1, "", str(e))
        
        return (1, "", "All retry attempts failed")
    
    def put_file(self, local_path: Path, remote_path: str) -> bool:
        """Copy file to remote host with retry logic.
        
        Args:
            local_path: Local file path
            remote_path: Remote file path
            
        Returns:
            True if successful
        """
        if not self.client:
            if not self.connect():
                return False
        
        if not local_path.exists():
            self._log(f"✗ Local file not found: {local_path}")
            return False
        
        for attempt in range(1, self.retry_attempts + 1):
            try:
                self._log(f"Copying {local_path.name} to {remote_path} (attempt {attempt}/{self.retry_attempts})")
                
                with SCPClient(self.client.get_transport()) as scp:
                    scp.put(str(local_path), remote_path)
                
                self._log(f"✓ File copied successfully")
                return True
                
            except Exception as e:
                self._log(f"File transfer attempt {attempt} failed: {e}")
                if attempt < self.retry_attempts:
                    self._log(f"Retrying in {self.retry_delay} seconds...")
                    time.sleep(self.retry_delay)
                    # Reconnect if needed
                    if not self.client.get_transport() or not self.client.get_transport().is_active():
                        self.connect()
                else:
                    self._log(f"✗ All file transfer attempts failed")
                    return False
        
        return False
    
    def get_file(self, remote_path: str, local_path: Path) -> bool:
        """Copy file from remote host with retry logic.
        
        Args:
            remote_path: Remote file path
            local_path: Local file path
            
        Returns:
            True if successful
        """
        if not self.client:
            if not self.connect():
                return False
        
        for attempt in range(1, self.retry_attempts + 1):
            try:
                self._log(f"Copying {remote_path} to {local_path} (attempt {attempt}/{self.retry_attempts})")
                
                with SCPClient(self.client.get_transport()) as scp:
                    scp.get(remote_path, str(local_path))
                
                self._log(f"✓ File copied successfully")
                return True
                
            except Exception as e:
                self._log(f"File transfer attempt {attempt} failed: {e}")
                if attempt < self.retry_attempts:
                    self._log(f"Retrying in {self.retry_delay} seconds...")
                    time.sleep(self.retry_delay)
                    # Reconnect if needed
                    if not self.client.get_transport() or not self.client.get_transport().is_active():
                        self.connect()
                else:
                    self._log(f"✗ All file transfer attempts failed")
                    return False
        
        return False
    
    def test_connection(self) -> bool:
        """Test SSH connection.
        
        Returns:
            True if connection successful
        """
        if not self.client:
            return self.connect()
        
        try:
            stdin, stdout, stderr = self.client.exec_command("echo 'test'", timeout=5)
            exit_code = stdout.channel.recv_exit_status()
            return exit_code == 0
        except Exception:
            return False
    
    def __enter__(self):
        """Context manager entry."""
        self.connect()
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit."""
        self.disconnect()

