"""
Interactive credential manager UI using Rich library.
Allows users to view, edit, delete, and test credentials.
"""

from typing import Optional, Dict, List, Tuple
from rich.console import Console
from rich.table import Table
from rich.panel import Panel
from rich.prompt import Prompt, Confirm
from rich import box

from deployment.deploy_credentials import CredentialManager
from deployment.deploy_ssh import test_ssh_credentials


class CredentialManagerUI:
    """Interactive UI for managing credentials."""
    
    def __init__(self):
        """Initialize credential manager UI."""
        self.console = Console()
        self.credential_manager = CredentialManager()
    
    def show_header(self):
        """Show credential manager header."""
        header = Panel(
            "[bold bright_cyan]🔐 Credential Manager[/bold bright_cyan]",
            border_style="bright_cyan",
            padding=(1, 2)
        )
        self.console.print(header)
        self.console.print()
    
    def list_all_profiles(self) -> List[Dict[str, str]]:
        """List all profiles and display them in a table.
        
        Returns:
            List of profile dictionaries
        """
        profiles = self.credential_manager.list_all_profiles()
        
        if not profiles:
            self.console.print("[yellow]No profiles found.[/yellow]\n")
            return []
        
        table = Table(
            show_header=True,
            header_style="bold bright_cyan",
            border_style="cyan",
            box=box.ROUNDED,
            padding=(0, 2)
        )
        table.add_column("#", width=4, style="bright_yellow", justify="center")
        table.add_column("IP Address", style="bright_cyan", width=25)
        table.add_column("Profile", style="cyan", width=15)
        table.add_column("Username", style="white", width=20)
        table.add_column("SSH Password", style="dim", width=12, justify="center")
        table.add_column("Sudo Password", style="dim", width=12, justify="center")
        
        for idx, profile in enumerate(profiles, 1):
            ip = profile['ip_address']
            profile_name = profile['profile_name']
            username = profile['username']
            
            # Check if passwords exist
            ssh_pwd = self.credential_manager.get_credential(ip, 'ssh_password', profile_name)
            sudo_pwd = self.credential_manager.get_credential(ip, 'sudo_password', profile_name)
            
            ssh_status = "[green]✓[/green]" if ssh_pwd else "[dim]—[/dim]"
            sudo_status = "[green]✓[/green]" if sudo_pwd else "[dim]—[/dim]"
            
            table.add_row(
                str(idx),
                ip,
                profile_name,
                username,
                ssh_status,
                sudo_status
            )
        
        self.console.print(table)
        self.console.print()
        return profiles
    
    def select_profile(self) -> Optional[Tuple[str, str]]:
        """Select a profile from the list.
        
        Returns:
            Tuple of (ip_address, profile_name) or None if cancelled
        """
        profiles = self.list_all_profiles()
        
        if not profiles:
            return None
        
        try:
            selection = Prompt.ask(
                "[bold bright_cyan]Select profile number[/bold bright_cyan] (or 'q' to quit)",
                default="q"
            )
            
            if selection.lower() == 'q':
                return None
            
            try:
                idx = int(selection) - 1
                if 0 <= idx < len(profiles):
                    profile = profiles[idx]
                    return (profile['ip_address'], profile['profile_name'])
                else:
                    self.console.print(f"[red]Invalid selection: {selection}[/red]\n")
                    return None
            except ValueError:
                self.console.print(f"[red]Invalid selection: {selection}[/red]\n")
                return None
        except (KeyboardInterrupt, EOFError):
            return None
    
    def show_profile_details(self, ip_address: str, profile_name: str):
        """Show detailed information about a profile.
        
        Args:
            ip_address: IP address
            profile_name: Profile name
        """
        credentials = self.credential_manager.get_all_credentials(ip_address, profile_name)
        
        self.console.print()
        self.console.print(Panel(
            f"[bold]Profile Details[/bold]\n\n"
            f"[cyan]IP Address:[/cyan] {ip_address}\n"
            f"[cyan]Profile Name:[/cyan] {profile_name}\n"
            f"[cyan]Username:[/cyan] {credentials.get('username') or '[dim]—[/dim]'}\n"
            f"[cyan]SSH Password:[/cyan] {'[green]✓ Set[/green]' if credentials.get('ssh_password') else '[dim]— Not set[/dim]'}\n"
            f"[cyan]Sudo Password:[/cyan] {'[green]✓ Set[/green]' if credentials.get('sudo_password') else '[dim]— Not set[/dim]'}",
            border_style="cyan",
            title=f"[bold cyan]{ip_address} / {profile_name}[/bold cyan]"
        ))
        self.console.print()
    
    def edit_profile_menu(self, ip_address: str, profile_name: str) -> bool:
        """Show edit menu for a profile.
        
        Args:
            ip_address: IP address
            profile_name: Profile name
            
        Returns:
            True if profile was modified
        """
        modified = False
        
        while True:
            self.show_profile_details(ip_address, profile_name)
            
            self.console.print("[bold]Actions:[/bold]")
            self.console.print("  1. Edit username")
            self.console.print("  2. Edit SSH password")
            self.console.print("  3. Edit sudo password")
            self.console.print("  4. Test connection")
            self.console.print("  5. Delete profile")
            self.console.print("  6. Back to main menu")
            self.console.print()
            
            try:
                choice = Prompt.ask(
                    "[bold bright_cyan]Select action[/bold bright_cyan]",
                    default="6"
                )
            except (KeyboardInterrupt, EOFError):
                return modified
            
            if choice == "1":
                if self.edit_username(ip_address, profile_name):
                    modified = True
            elif choice == "2":
                if self.edit_ssh_password(ip_address, profile_name):
                    modified = True
            elif choice == "3":
                if self.edit_sudo_password(ip_address, profile_name):
                    modified = True
            elif choice == "4":
                self.test_connection(ip_address, profile_name)
            elif choice == "5":
                if self.delete_profile(ip_address, profile_name):
                    return modified  # Profile deleted, exit menu
            elif choice == "6":
                return modified
            else:
                self.console.print(f"[red]Invalid choice: {choice}[/red]\n")
    
    def edit_username(self, ip_address: str, profile_name: str) -> bool:
        """Edit username for a profile.
        
        Args:
            ip_address: IP address
            profile_name: Profile name
            
        Returns:
            True if username was updated
        """
        try:
            new_username = Prompt.ask(
                "[bold bright_cyan]Enter new username[/bold bright_cyan]",
                default=""
            )
            
            if not new_username:
                self.console.print("[yellow]Username not changed[/yellow]\n")
                return False
            
            # Save username
            if self.credential_manager.save_credential(ip_address, 'username', new_username, profile_name):
                self.console.print(f"[green]✓ Username updated to: {new_username}[/green]\n")
                return True
            else:
                self.console.print("[red]✗ Failed to update username[/red]\n")
                return False
        except (KeyboardInterrupt, EOFError):
            return False
    
    def edit_ssh_password(self, ip_address: str, profile_name: str) -> bool:
        """Edit SSH password for a profile.
        
        Args:
            ip_address: IP address
            profile_name: Profile name
            
        Returns:
            True if password was updated
        """
        try:
            credentials = self.credential_manager.get_all_credentials(ip_address, profile_name)
            username = credentials.get('username') or Prompt.ask("[bold bright_cyan]Enter username[/bold bright_cyan]")
            
            new_password = Prompt.ask(
                "[bold bright_cyan]Enter new SSH password[/bold bright_cyan]",
                password=True
            )
            
            if not new_password:
                self.console.print("[yellow]Password not changed[/yellow]\n")
                return False
            
            # Test credentials before saving
            self.console.print("[dim]Testing credentials...[/dim]")
            success, error_msg = test_ssh_credentials(ip_address, username, new_password)
            
            if not success:
                self.console.print(f"[red]✗ Credential test failed: {error_msg}[/red]\n")
                if not Confirm.ask("[yellow]Save anyway?[/yellow]", default=False):
                    return False
            
            # Save password
            if self.credential_manager.save_credential(ip_address, 'ssh_password', new_password, profile_name):
                self.console.print("[green]✓ SSH password updated[/green]\n")
                return True
            else:
                self.console.print("[red]✗ Failed to update SSH password[/red]\n")
                return False
        except (KeyboardInterrupt, EOFError):
            return False
    
    def edit_sudo_password(self, ip_address: str, profile_name: str) -> bool:
        """Edit sudo password for a profile.
        
        Args:
            ip_address: IP address
            profile_name: Profile name
            
        Returns:
            True if password was updated
        """
        try:
            credentials = self.credential_manager.get_all_credentials(ip_address, profile_name)
            username = credentials.get('username')
            ssh_password = credentials.get('ssh_password')
            
            if not username:
                self.console.print("[red]✗ Username must be set before setting sudo password[/red]\n")
                return False
            
            new_sudo_password = Prompt.ask(
                "[bold bright_cyan]Enter new sudo password[/bold bright_cyan]",
                password=True
            )
            
            if not new_sudo_password:
                self.console.print("[yellow]Password not changed[/yellow]\n")
                return False
            
            # Test credentials before saving
            self.console.print("[dim]Testing credentials...[/dim]")
            success, error_msg = test_ssh_credentials(
                ip_address, username, ssh_password, new_sudo_password
            )
            
            if not success:
                self.console.print(f"[red]✗ Credential test failed: {error_msg}[/red]\n")
                if not Confirm.ask("[yellow]Save anyway?[/yellow]", default=False):
                    return False
            
            # Save password
            if self.credential_manager.save_credential(ip_address, 'sudo_password', new_sudo_password, profile_name):
                self.console.print("[green]✓ Sudo password updated[/green]\n")
                return True
            else:
                self.console.print("[red]✗ Failed to update sudo password[/red]\n")
                return False
        except (KeyboardInterrupt, EOFError):
            return False
    
    def test_connection(self, ip_address: str, profile_name: str):
        """Test SSH connection with saved credentials.
        
        Args:
            ip_address: IP address
            profile_name: Profile name
        """
        credentials = self.credential_manager.get_all_credentials(ip_address, profile_name)
        username = credentials.get('username')
        ssh_password = credentials.get('ssh_password')
        sudo_password = credentials.get('sudo_password')
        
        if not username:
            self.console.print("[red]✗ Username not set for this profile[/red]\n")
            return
        
        self.console.print("[dim]Testing connection...[/dim]")
        success, error_msg = test_ssh_credentials(ip_address, username, ssh_password, sudo_password)
        
        if success:
            self.console.print("[green]✓ Connection test successful[/green]\n")
        else:
            self.console.print(f"[red]✗ Connection test failed: {error_msg}[/red]\n")
    
    def delete_profile(self, ip_address: str, profile_name: str) -> bool:
        """Delete a profile after confirmation.
        
        Args:
            ip_address: IP address
            profile_name: Profile name
            
        Returns:
            True if profile was deleted
        """
        try:
            if not Confirm.ask(
                f"[red]Are you sure you want to delete profile '{profile_name}' for {ip_address}?[/red]",
                default=False
            ):
                return False
            
            if self.credential_manager.delete_profile(ip_address, profile_name):
                self.console.print(f"[green]✓ Profile '{profile_name}' deleted[/green]\n")
                return True
            else:
                self.console.print("[red]✗ Failed to delete profile[/red]\n")
                return False
        except (KeyboardInterrupt, EOFError):
            return False
    
    def create_new_profile(self):
        """Create a new profile."""
        try:
            ip_address = Prompt.ask("[bold bright_cyan]Enter IP address or hostname[/bold bright_cyan]")
            if not ip_address:
                self.console.print("[yellow]IP address required[/yellow]\n")
                return
            
            # Auto-generate profile name
            profile_name = self.credential_manager.auto_generate_profile_name(ip_address)
            self.console.print(f"[dim]Using profile name: {profile_name}[/dim]")
            
            custom_name = Prompt.ask(
                "[bold bright_cyan]Enter custom profile name[/bold bright_cyan] (or press Enter to use auto-generated)",
                default=profile_name
            )
            if custom_name:
                profile_name = custom_name
            
            username = Prompt.ask("[bold bright_cyan]Enter username[/bold bright_cyan]")
            if not username:
                self.console.print("[yellow]Username required[/yellow]\n")
                return
            
            # Save username to the same profile
            self.credential_manager.save_credential(ip_address, 'username', username, profile_name)
            
            # Optionally set SSH password (same profile)
            if Confirm.ask("[bold bright_cyan]Set SSH password now?[/bold bright_cyan]", default=True):
                ssh_password = Prompt.ask(
                    "[bold bright_cyan]Enter SSH password[/bold bright_cyan]",
                    password=True
                )
                if ssh_password:
                    # Test before saving
                    self.console.print("[dim]Testing credentials...[/dim]")
                    success, error_msg = test_ssh_credentials(ip_address, username, ssh_password)
                    if success:
                        self.credential_manager.save_credential(ip_address, 'ssh_password', ssh_password, profile_name)
                        self.console.print("[green]✓ SSH password saved[/green]")
                    else:
                        self.console.print(f"[red]✗ Credential test failed: {error_msg}[/red]")
                        if Confirm.ask("[yellow]Save anyway?[/yellow]", default=False):
                            self.credential_manager.save_credential(ip_address, 'ssh_password', ssh_password, profile_name)
            
            # Optionally set sudo password (same profile)
            if Confirm.ask("[bold bright_cyan]Set sudo password now?[/bold bright_cyan]", default=True):
                sudo_password = Prompt.ask(
                    "[bold bright_cyan]Enter sudo password[/bold bright_cyan]",
                    password=True
                )
                if sudo_password:
                    ssh_password = self.credential_manager.get_credential(ip_address, 'ssh_password', profile_name)
                    # Test before saving
                    self.console.print("[dim]Testing credentials...[/dim]")
                    success, error_msg = test_ssh_credentials(ip_address, username, ssh_password, sudo_password)
                    if success:
                        self.credential_manager.save_credential(ip_address, 'sudo_password', sudo_password, profile_name)
                        self.console.print("[green]✓ Sudo password saved[/green]")
                    else:
                        self.console.print(f"[red]✗ Credential test failed: {error_msg}[/red]")
                        if Confirm.ask("[yellow]Save anyway?[/yellow]", default=False):
                            self.credential_manager.save_credential(ip_address, 'sudo_password', sudo_password, profile_name)
            
            self.console.print(f"[green]✓ Profile '{profile_name}' created[/green]\n")
        except (KeyboardInterrupt, EOFError):
            self.console.print("\n[yellow]Profile creation cancelled[/yellow]\n")
    
    def reset_database(self):
        """Reset/erase all credentials."""
        try:
            if Confirm.ask(
                "[red]Are you sure you want to erase ALL credentials? This cannot be undone![/red]",
                default=False
            ):
                if self.credential_manager.reset_database():
                    self.console.print("[green]✓ All credentials erased[/green]\n")
                else:
                    self.console.print("[red]✗ Failed to erase credentials[/red]\n")
        except (KeyboardInterrupt, EOFError):
            self.console.print("\n[yellow]Reset cancelled[/yellow]\n")
    
    def run(self):
        """Run the credential manager UI."""
        self.show_header()
        
        while True:
            self.console.print("[bold]Main Menu:[/bold]")
            self.console.print("  1. List all profiles")
            self.console.print("  2. Select profile to edit/delete")
            self.console.print("  3. Create new profile")
            self.console.print("  4. Reset database (erase all)")
            self.console.print("  5. Exit")
            self.console.print()
            
            try:
                choice = Prompt.ask(
                    "[bold bright_cyan]Select option[/bold bright_cyan]",
                    default="5"
                )
            except (KeyboardInterrupt, EOFError):
                break
            
            if choice == "1":
                self.list_all_profiles()
            elif choice == "2":
                result = self.select_profile()
                if result:
                    ip_address, profile_name = result
                    modified = self.edit_profile_menu(ip_address, profile_name)
                    if modified:
                        self.console.print("[green]✓ Changes saved[/green]\n")
            elif choice == "3":
                self.create_new_profile()
            elif choice == "4":
                self.reset_database()
            elif choice == "5":
                break
            else:
                self.console.print(f"[red]Invalid choice: {choice}[/red]\n")


def main():
    """Main entry point for credential manager."""
    ui = CredentialManagerUI()
    ui.run()


if __name__ == "__main__":
    main()

