"""
Encrypted credential management using SQLite and Fernet encryption.
Stores SSH passwords, sudo passwords, and Docker Hub tokens per IP address.
"""

import sqlite3
import os
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional, Dict, List
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
import base64


class CredentialManager:
    """Manages encrypted credentials stored in SQLite database."""
    
    def __init__(self, db_path: Optional[Path] = None, key_path: Optional[Path] = None):
        """Initialize credential manager.
        
        Args:
            db_path: Path to SQLite database (default: deployment/credentials.db)
            key_path: Path to master encryption key (default: ~/.cavgo-deploy-key)
        """
        if db_path is None:
            db_path = Path(__file__).parent / "credentials.db"
        self.db_path = Path(db_path)
        
        if key_path is None:
            key_path = Path.home() / ".cavgo-deploy-key"
        self.key_path = Path(key_path)
        
        self.fernet: Optional[Fernet] = None
        self._initialize_database()
        self._load_or_create_key()
    
    def _initialize_database(self):
        """Initialize SQLite database with required tables."""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        # Check if table exists and what columns it has
        cursor.execute("""
            SELECT name FROM sqlite_master 
            WHERE type='table' AND name='credentials'
        """)
        table_exists = cursor.fetchone() is not None
        
        if table_exists:
            # Check existing columns
            cursor.execute("PRAGMA table_info(credentials)")
            columns = [row[1] for row in cursor.fetchall()]
            
            # Check if this is the old schema (with separate encrypted columns)
            has_old_schema = 'ssh_password_encrypted' in columns or 'sudo_password_encrypted' in columns
            has_new_schema = 'encrypted_value' in columns and 'credential_type' in columns
            
            if has_old_schema and not has_new_schema:
                # Migrate from old schema to new schema
                self._log("Migrating database from old schema to new schema...")
                
                # Create new table with profile_name support
                cursor.execute("""
                    CREATE TABLE credentials_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ip_address TEXT NOT NULL,
                        profile_name TEXT NOT NULL DEFAULT 'default',
                        credential_type TEXT NOT NULL,
                        encrypted_value BLOB NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP,
                        UNIQUE(ip_address, profile_name, credential_type)
                    )
                """)
                
                # Migrate data from old columns to new structure
                cursor.execute("SELECT ip_address, ssh_password_encrypted, sudo_password_encrypted, docker_hub_token_encrypted, created_at FROM credentials")
                old_rows = cursor.fetchall()
                
                for row in old_rows:
                    ip_address = row[0]
                    ssh_pwd = row[1]
                    sudo_pwd = row[2]
                    docker_token = row[3]
                    created = row[4] if len(row) > 4 else None
                    
                    # Convert old encrypted strings to bytes if needed
                    def to_bytes(value):
                        if value is None:
                            return None
                        if isinstance(value, str):
                            return value.encode('utf-8')
                        return value
                    
                    # Insert migrated data with default profile
                    if ssh_pwd:
                        cursor.execute("""
                            INSERT INTO credentials_new (ip_address, profile_name, credential_type, encrypted_value, created_at)
                            VALUES (?, 'default', 'ssh_password', ?, ?)
                        """, (ip_address, to_bytes(ssh_pwd), created))
                    
                    if sudo_pwd:
                        cursor.execute("""
                            INSERT INTO credentials_new (ip_address, profile_name, credential_type, encrypted_value, created_at)
                            VALUES (?, 'default', 'sudo_password', ?, ?)
                        """, (ip_address, to_bytes(sudo_pwd), created))
                    
                    if docker_token:
                        cursor.execute("""
                            INSERT INTO credentials_new (ip_address, profile_name, credential_type, encrypted_value, created_at)
                            VALUES (?, 'default', 'dockerhub_token', ?, ?)
                        """, (ip_address, to_bytes(docker_token), created))
                
                # Drop old table and rename new one
                cursor.execute("DROP TABLE credentials")
                cursor.execute("ALTER TABLE credentials_new RENAME TO credentials")
                
                self._log("Database migration completed")
            else:
                # Add missing columns if needed (for new schema)
                if 'credential_type' not in columns:
                    try:
                        cursor.execute("ALTER TABLE credentials ADD COLUMN credential_type TEXT")
                    except sqlite3.OperationalError:
                        pass
                
                if 'encrypted_value' not in columns:
                    try:
                        cursor.execute("ALTER TABLE credentials ADD COLUMN encrypted_value BLOB")
                    except sqlite3.OperationalError:
                        pass
                
                if 'created_at' not in columns:
                    try:
                        cursor.execute("ALTER TABLE credentials ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
                    except sqlite3.OperationalError:
                        pass
                
                if 'updated_at' not in columns:
                    try:
                        cursor.execute("ALTER TABLE credentials ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
                    except sqlite3.OperationalError:
                        pass
                
                if 'expires_at' not in columns:
                    try:
                        cursor.execute("ALTER TABLE credentials ADD COLUMN expires_at TIMESTAMP")
                    except sqlite3.OperationalError:
                        pass
                
                # Add profile_name column if it doesn't exist (migration from schema without profiles)
                if 'profile_name' not in columns:
                    try:
                        self._log("Adding profile_name column to credentials table...")
                        # Add column with default value
                        cursor.execute("ALTER TABLE credentials ADD COLUMN profile_name TEXT NOT NULL DEFAULT 'default'")
                        # Update existing rows to have 'default' profile
                        cursor.execute("UPDATE credentials SET profile_name = 'default' WHERE profile_name IS NULL")
                        # Drop and recreate unique constraint to include profile_name
                        cursor.execute("DROP INDEX IF EXISTS idx_ip_type")
                        # SQLite doesn't support dropping unique constraints directly, so we need to recreate the table
                        # But first, let's check if we can just add the new index
                        cursor.execute("""
                            CREATE UNIQUE INDEX IF NOT EXISTS idx_ip_profile_type 
                            ON credentials(ip_address, profile_name, credential_type)
                        """)
                        self._log("Profile support added to credentials table")
                    except sqlite3.OperationalError as e:
                        # If unique constraint exists, we need to recreate table
                        self._log(f"Recreating table with profile support: {e}")
                        # Create new table with profile support
                        cursor.execute("""
                            CREATE TABLE credentials_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                ip_address TEXT NOT NULL,
                                profile_name TEXT NOT NULL DEFAULT 'default',
                                credential_type TEXT NOT NULL,
                                encrypted_value BLOB NOT NULL,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                expires_at TIMESTAMP,
                                UNIQUE(ip_address, profile_name, credential_type)
                            )
                        """)
                        # Copy existing data with default profile
                        cursor.execute("""
                            INSERT INTO credentials_new 
                            (ip_address, profile_name, credential_type, encrypted_value, created_at, updated_at, expires_at)
                            SELECT ip_address, 'default', credential_type, encrypted_value, created_at, updated_at, expires_at
                            FROM credentials
                        """)
                        cursor.execute("DROP TABLE credentials")
                        cursor.execute("ALTER TABLE credentials_new RENAME TO credentials")
                        self._log("Table recreated with profile support")
        else:
            # Create credentials table from scratch with profile support
            cursor.execute("""
                CREATE TABLE credentials (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ip_address TEXT NOT NULL,
                    profile_name TEXT NOT NULL DEFAULT 'default',
                    credential_type TEXT NOT NULL,
                    encrypted_value BLOB NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMP,
                    UNIQUE(ip_address, profile_name, credential_type)
                )
            """)
        
        # Create index for faster lookups
        cursor.execute("""
            CREATE INDEX IF NOT EXISTS idx_ip_profile_type 
            ON credentials(ip_address, profile_name, credential_type)
        """)
        
        conn.commit()
        conn.close()
    
    def _log(self, message: str):
        """Log a message (for migration)."""
        # Simple print for migration messages
        print(f"[Migration] {message}")
    
    def _load_or_create_key(self):
        """Load encryption key or create a new one."""
        if self.key_path.exists():
            # Load existing key
            with open(self.key_path, 'rb') as f:
                key = f.read()
            self.fernet = Fernet(key)
        else:
            # Generate new key
            key = Fernet.generate_key()
            with open(self.key_path, 'wb') as f:
                f.write(key)
            # Set restrictive permissions (readable only by owner)
            os.chmod(self.key_path, 0o600)
            self.fernet = Fernet(key)
    
    def _encrypt(self, value: str) -> bytes:
        """Encrypt a string value.
        
        Args:
            value: String to encrypt
            
        Returns:
            Encrypted bytes
        """
        return self.fernet.encrypt(value.encode('utf-8'))
    
    def _decrypt(self, encrypted: bytes) -> str:
        """Decrypt encrypted bytes.
        
        Args:
            encrypted: Encrypted bytes
            
        Returns:
            Decrypted string
        """
        return self.fernet.decrypt(encrypted).decode('utf-8')
    
    def save_credential(self, ip_address: str, credential_type: str, value: str, 
                       profile_name: str = "default", expiry_days: int = 3) -> bool:
        """Save encrypted credential for an IP address and profile.
        
        Args:
            ip_address: IP address or hostname
            credential_type: Type of credential ('username', 'ssh_password', 'sudo_password', 'dockerhub_token')
            value: Credential value to encrypt and store
            profile_name: Profile name (default: "default")
            expiry_days: Number of days until credential expires
            
        Returns:
            True if saved successfully
        """
        try:
            encrypted = self._encrypt(value)
            expires_at = datetime.now() + timedelta(days=expiry_days)
            
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute("""
                INSERT OR REPLACE INTO credentials 
                (ip_address, profile_name, credential_type, encrypted_value, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (ip_address, profile_name, credential_type, encrypted, datetime.now(), expires_at))
            
            conn.commit()
            conn.close()
            return True
        except Exception as e:
            print(f"Error saving credential: {e}")
            return False
    
    def get_credential(self, ip_address: str, credential_type: str, profile_name: str = "default") -> Optional[str]:
        """Get decrypted credential for an IP address and profile.
        
        Args:
            ip_address: IP address or hostname
            credential_type: Type of credential ('username', 'ssh_password', 'sudo_password', 'dockerhub_token')
            profile_name: Profile name (default: "default")
            
        Returns:
            Decrypted credential value or None if not found/expired
        """
        try:
            # Ensure database is properly initialized
            self._initialize_database()
            
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            # Verify table structure
            cursor.execute("PRAGMA table_info(credentials)")
            columns = [row[1] for row in cursor.fetchall()]
            
            if 'encrypted_value' not in columns:
                # Database not migrated properly, try migration again
                conn.close()
                self._initialize_database()
                conn = sqlite3.connect(self.db_path)
                cursor = conn.cursor()
            
            cursor.execute("""
                SELECT encrypted_value, expires_at 
                FROM credentials 
                WHERE ip_address = ? AND profile_name = ? AND credential_type = ?
            """, (ip_address, profile_name, credential_type))
            
            row = cursor.fetchone()
            conn.close()
            
            if not row:
                return None
            
            encrypted, expires_at_str = row
            expires_at = datetime.fromisoformat(expires_at_str)
            
            # Check if expired
            if datetime.now() > expires_at:
                # Delete expired credential
                self.delete_credential(ip_address, credential_type, profile_name)
                return None
            
            try:
                return self._decrypt(encrypted)
            except Exception as e:
                # Old encrypted values might not be compatible with new encryption
                # Delete the invalid credential so user can re-enter
                print(f"Warning: Could not decrypt {credential_type} for {ip_address}/{profile_name} (may be from old encryption). Please re-enter.")
                self.delete_credential(ip_address, credential_type, profile_name)
                return None
        except Exception as e:
            print(f"Error getting credential: {e}")
            return None
    
    def delete_credential(self, ip_address: str, credential_type: str, profile_name: str = "default") -> bool:
        """Delete credential for an IP address and profile.
        
        Args:
            ip_address: IP address or hostname
            credential_type: Type of credential
            profile_name: Profile name (default: "default")
            
        Returns:
            True if deleted successfully
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute("""
                DELETE FROM credentials 
                WHERE ip_address = ? AND profile_name = ? AND credential_type = ?
            """, (ip_address, profile_name, credential_type))
            
            conn.commit()
            conn.close()
            return True
        except Exception as e:
            print(f"Error deleting credential: {e}")
            return False
    
    def get_all_credentials(self, ip_address: str, profile_name: str = "default") -> Dict[str, Optional[str]]:
        """Get all credentials for an IP address and profile.
        
        Args:
            ip_address: IP address or hostname
            profile_name: Profile name (default: "default")
            
        Returns:
            Dictionary mapping credential types to values
        """
        credentials = {}
        for cred_type in ['username', 'ssh_password', 'sudo_password', 'dockerhub_token']:
            credentials[cred_type] = self.get_credential(ip_address, cred_type, profile_name)
        return credentials
    
    def has_credentials(self, ip_address: str, profile_name: Optional[str] = None) -> bool:
        """Check if any credentials exist for an IP address (and optionally profile).
        
        Args:
            ip_address: IP address or hostname
            profile_name: Optional profile name. If None, checks for any profile.
            
        Returns:
            True if credentials exist
        """
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        if profile_name:
            cursor.execute("""
                SELECT COUNT(*) FROM credentials 
                WHERE ip_address = ? AND profile_name = ? AND expires_at > ?
            """, (ip_address, profile_name, datetime.now().isoformat()))
        else:
            cursor.execute("""
                SELECT COUNT(*) FROM credentials 
                WHERE ip_address = ? AND expires_at > ?
            """, (ip_address, datetime.now().isoformat()))
        
        count = cursor.fetchone()[0]
        conn.close()
        
        return count > 0
    
    def cleanup_expired(self):
        """Remove expired credentials from database."""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute("""
                DELETE FROM credentials 
                WHERE expires_at < ?
            """, (datetime.now().isoformat(),))
            
            deleted = cursor.rowcount
            conn.commit()
            conn.close()
            
            return deleted
        except Exception as e:
            print(f"Error cleaning up expired credentials: {e}")
            return 0
    
    def list_all_profiles(self) -> List[Dict[str, str]]:
        """List all profiles across all IP addresses.
        
        Returns:
            List of dictionaries with keys: ip_address, profile_name, username
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute("""
                SELECT DISTINCT ip_address, profile_name 
                FROM credentials 
                WHERE expires_at > ?
                ORDER BY ip_address, profile_name
            """, (datetime.now().isoformat(),))
            
            # Use a set to track unique profiles (in case DISTINCT doesn't work as expected)
            seen_profiles = set()
            profiles = []
            
            for row in cursor.fetchall():
                ip_address, profile_name = row
                profile_key = (ip_address, profile_name)
                
                # Skip if we've already seen this profile
                if profile_key in seen_profiles:
                    continue
                
                seen_profiles.add(profile_key)
                
                # Get username for this profile
                username = self.get_credential(ip_address, 'username', profile_name)
                profiles.append({
                    'ip_address': ip_address,
                    'profile_name': profile_name,
                    'username': username or '—'
                })
            
            conn.close()
            return profiles
        except Exception as e:
            print(f"Error listing profiles: {e}")
            return []
    
    def list_profiles_for_ip(self, ip_address: str) -> List[str]:
        """List all profile names for a specific IP address.
        
        Args:
            ip_address: IP address or hostname
            
        Returns:
            List of profile names
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute("""
                SELECT DISTINCT profile_name 
                FROM credentials 
                WHERE ip_address = ? AND expires_at > ?
                ORDER BY profile_name
            """, (ip_address, datetime.now().isoformat()))
            
            profiles = [row[0] for row in cursor.fetchall()]
            conn.close()
            return profiles
        except Exception as e:
            print(f"Error listing profiles for IP: {e}")
            return []
    
    def delete_profile(self, ip_address: str, profile_name: str) -> bool:
        """Delete all credentials for a profile.
        
        Args:
            ip_address: IP address or hostname
            profile_name: Profile name to delete
            
        Returns:
            True if deleted successfully
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute("""
                DELETE FROM credentials 
                WHERE ip_address = ? AND profile_name = ?
            """, (ip_address, profile_name))
            
            deleted = cursor.rowcount
            conn.commit()
            conn.close()
            return deleted > 0
        except Exception as e:
            print(f"Error deleting profile: {e}")
            return False
    
    def auto_generate_profile_name(self, ip_address: str) -> str:
        """Auto-generate next available profile name for an IP address.
        
        Generates: default, default1, default2, etc.
        
        Args:
            ip_address: IP address or hostname
            
        Returns:
            Available profile name
        """
        existing_profiles = self.list_profiles_for_ip(ip_address)
        
        # Check if "default" is available
        if "default" not in existing_profiles:
            return "default"
        
        # Find next available number
        counter = 1
        while True:
            profile_name = f"default{counter}"
            if profile_name not in existing_profiles:
                return profile_name
            counter += 1
    
    def reset_database(self) -> bool:
        """Reset/erase all credentials from database.
        
        Returns:
            True if reset successfully
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            
            cursor.execute("DELETE FROM credentials")
            
            conn.commit()
            conn.close()
            return True
        except Exception as e:
            print(f"Error resetting database: {e}")
            return False

