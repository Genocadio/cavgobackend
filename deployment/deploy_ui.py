"""
Rich UI components for deployment interface.
Beautiful, polished terminal UI with progress bars and visual feedback.
"""

from typing import List, Dict, Optional, Callable, Tuple
from datetime import datetime
from rich.console import Console
from rich.progress import (
    Progress, SpinnerColumn, BarColumn, TextColumn, 
    TimeElapsedColumn, TimeRemainingColumn, MofNCompleteColumn
)
from rich.prompt import Confirm, Prompt
from rich.table import Table
from rich.panel import Panel
from rich.align import Align
from rich.live import Live
from rich.layout import Layout
from rich.text import Text
from rich.rule import Rule
from contextlib import contextmanager


class DeploymentUI:
    """Beautiful Rich UI for deployment interface."""
    
    def __init__(self, log_callback: Optional[Callable[[str], None]] = None):
        """Initialize deployment UI.
        
        Args:
            log_callback: Optional callback for log messages
        """
        self.console = Console()
        self.log_callback = log_callback
        self.progress: Optional[Progress] = None
        self.deployment_times: Dict[str, float] = {}
    
    def _log(self, message: str, level: str = "info"):
        """Add message to log.
        
        Args:
            message: Log message
            level: Log level (info, success, warning, error)
        """
        if self.log_callback:
            self.log_callback(message)
    
    def print_header(self):
        """Print beautiful deployment header."""
        header = Panel(
            Align.center(
                Text("🚀 CavGo Deployment System", style="bold bright_cyan"),
                vertical="middle"
            ),
            border_style="bright_cyan",
            padding=(1, 2)
        )
        self.console.print(header)
        self.console.print()
    
    def select_services_interactive(self, services: List[str], services_info: Optional[Dict] = None) -> List[str]:
        """Interactive service selection with beautiful UI.
        
        Args:
            services: List of all service names
            services_info: Optional dictionary mapping service names to ServiceInfo objects
            
        Returns:
            List of selected service names
        """
        self.console.print()
        self.console.print(Rule("[bold bright_cyan]Service Selection[/bold bright_cyan]", style="bright_cyan"))
        self.console.print()
        self.console.print("[dim]Select services to deploy:[/dim]\n")
        
        # Create beautiful selection table
        table = Table(
            show_header=True,
            header_style="bold bright_cyan",
            border_style="cyan",
            box=None,
            padding=(0, 2)
        )
        table.add_column("#", width=4, style="bright_yellow", justify="center")
        table.add_column("Service", style="bright_cyan", width=25)
        table.add_column("Image", style="dim white", width=40)
        
        for idx, service in enumerate(services, 1):
            # Get service info if available
            description = ""
            if services_info and service in services_info:
                service_info = services_info[service]
                image = getattr(service_info, 'image', '')
                if image:
                    # Extract repository/image name for display
                    if '/' in image:
                        parts = image.split('/')
                        image_name = parts[-1].split(':')[0] if ':' in parts[-1] else parts[-1]
                        description = f"[dim]{image_name}[/dim]"
                    else:
                        description = f"[dim]{image.split(':')[0]}[/dim]" if ':' in image else f"[dim]{image}[/dim]"
            
            if not description:
                description = "[dim]—[/dim]"
            
            table.add_row(
                f"[bright_yellow]{idx}[/bright_yellow]",
                f"[bright_cyan]{service}[/bright_cyan]",
                description
            )
        
        self.console.print(table)
        self.console.print()
        
        # Prompt for selection
        try:
            selection = Prompt.ask(
                "[bold bright_cyan]Enter service numbers[/bold bright_cyan] (comma-separated, e.g., 1,2,3) or [bold]'all'[/bold] for all services",
                default="all",
                console=self.console
            )
        except (KeyboardInterrupt, EOFError):
            raise  # Re-raise to be handled by caller
        
        if selection.lower() == "all":
            self.console.print("[green]✓[/green] Selected all services\n")
            return services
        
        # Parse selection
        try:
            numbers = [int(n.strip()) for n in selection.split(',')]
            selected = [services[n - 1] for n in numbers if 1 <= n <= len(services)]
            if not selected:
                self.console.print("[red]✗[/red] [red]No valid services selected[/red]")
                return []
            
            selected_list = ", ".join([f"[bright_cyan]{s}[/bright_cyan]" for s in selected])
            self.console.print(f"[green]✓[/green] Selected: {selected_list}\n")
            return selected
        except (ValueError, IndexError) as e:
            self.console.print(f"[red]✗[/red] [red]Invalid selection: {e}[/red]")
            return []
    
    def create_progress_bar(self) -> Progress:
        """Create beautiful progress bar component.
        
        Returns:
            Progress instance
        """
        return Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            BarColumn(
                bar_width=None,
                style="bright_cyan",
                complete_style="bright_green",
                finished_style="bright_green"
            ),
            MofNCompleteColumn(),
            TextColumn("[progress.percentage]{task.percentage:>3.0f}%", style="bright_yellow"),
            TimeElapsedColumn(),
            TimeRemainingColumn(),
            console=self.console,
            expand=True
        )
    
    @contextmanager
    def show_deployment_progress(self, services: List[str], 
                                 on_update: Optional[Callable] = None):
        """Show deployment progress with beautiful progress bars.
        
        Args:
            services: List of services to deploy
            on_update: Optional callback for progress updates
            
        Yields:
            Progress instance
        """
        self.console.print()
        self.console.print(Rule("[bold bright_cyan]Deployment Progress[/bold bright_cyan]", style="bright_cyan"))
        self.console.print()
        
        progress = self.create_progress_bar()
        
        # Add overall progress task
        overall_task = progress.add_task(
            "[bold bright_blue]Overall Deployment[/bold bright_blue]",
            total=len(services) * 2  # Pull + Start for each service
        )
        
        # Add per-service tasks
        service_tasks = {}
        for service in services:
            task_id = progress.add_task(
                f"  [cyan]📦 {service}[/cyan]",
                total=100
            )
            service_tasks[service] = task_id
        
        self.progress = progress
        self._overall_task = overall_task
        self._service_tasks = service_tasks
        self._services = services
        self._on_update = on_update
        
        # Use Live context to update in place
        with Live(progress, console=self.console, refresh_per_second=10, screen=False):
            yield progress
    
    def update_service_progress(self, service: str, step: str, progress_pct: int):
        """Update progress for a specific service.
        
        Args:
            service: Service name
            step: Current step (e.g., "Pulling image...", "Starting...")
            progress_pct: Progress percentage (0-100)
        """
        if not self.progress or service not in self._service_tasks:
            return
        
        task_id = self._service_tasks[service]
        step_icons = {
            "Pulling": "⬇️",
            "Starting": "▶️",
            "Complete": "✅",
            "Failed": "❌"
        }
        
        icon = step_icons.get(step.split()[0], "⚙️")
        self.progress.update(
            task_id,
            description=f"  [cyan]{icon} {service}[/cyan] - [yellow]{step}[/yellow]",
            completed=progress_pct
        )
    
    def complete_service(self, service: str, success: bool = True):
        """Mark a service as complete.
        
        Args:
            service: Service name
            success: Whether deployment was successful
        """
        if not self.progress or service not in self._service_tasks:
            return
        
        task_id = self._service_tasks[service]
        if success:
            self.progress.update(
                task_id,
                description=f"  [green]✅ {service}[/green] - [green]Deployed successfully[/green]",
                completed=100,
                style="bright_green"
            )
        else:
            self.progress.update(
                task_id,
                description=f"  [red]❌ {service}[/red] - [red]Deployment failed[/red]",
                completed=100,
                style="red"
            )
        
        # Advance overall progress
        if hasattr(self, '_overall_task'):
            self.progress.advance(self._overall_task)
    
    def log(self, message: str, level: str = "info"):
        """Add log message.
        
        Args:
            message: Log message
            level: Log level
        """
        self._log(message, level)
    
    def start_live(self):
        """Start live updating display."""
        pass
    
    def stop_live(self):
        """Stop live updating display."""
        if self.progress:
            self.progress.stop()
            self.progress = None
    
    def show_summary(self, results: Dict[str, bool], deployment_times: Optional[Dict[str, float]] = None):
        """Show beautiful deployment summary.
        
        Args:
            results: Dictionary mapping service names to success status
            deployment_times: Optional dictionary mapping service names to deployment times
        """
        self.console.print()
        self.console.print(Rule("[bold bright_green]Deployment Summary[/bold bright_green]", style="bright_green"))
        self.console.print()
        
        # Count success/failure
        successful = sum(1 for v in results.values() if v)
        failed = len(results) - successful
        
        # Create summary table
        table = Table(
            show_header=True,
            header_style="bold bright_green",
            border_style="green",
            box=None,
            padding=(0, 2),
            title=f"[bold]Deployed: [green]{successful}[/green] | Failed: [red]{failed}[/red][/bold]"
        )
        table.add_column("Service", style="bright_cyan", width=25)
        table.add_column("Status", width=25)
        table.add_column("Time", style="bright_yellow", width=12, justify="right")
        table.add_column("Details", style="dim", width=40)
        
        for service, success in results.items():
            if success:
                status = "[bold green]✓ Deployed[/bold green]"
            else:
                status = "[bold red]✗ Failed[/bold red]"
            
            time_str = "—"
            if deployment_times and service in deployment_times:
                time_val = deployment_times[service]
                if time_val < 1:
                    time_str = f"{time_val*1000:.0f}ms"
                else:
                    time_str = f"{time_val:.1f}s"
            
            table.add_row(
                f"[bright_cyan]{service}[/bright_cyan]",
                status,
                f"[bright_yellow]{time_str}[/bright_yellow]",
                "[dim]Ready for production[/dim]" if success else "[dim]Check logs for details[/dim]"
            )
        
        self.console.print(table)
        self.console.print()
        
        # Show success/failure message
        if failed == 0:
            success_panel = Panel(
                Align.center(
                    Text("🎉 All services deployed successfully!", style="bold bright_green"),
                    vertical="middle"
                ),
                border_style="bright_green",
                padding=(1, 2)
            )
            self.console.print(success_panel)
        else:
            warning_panel = Panel(
                Align.center(
                    Text(f"⚠️  {failed} service(s) failed to deploy", style="bold yellow"),
                    vertical="middle"
                ),
                border_style="yellow",
                padding=(1, 2)
            )
            self.console.print(warning_panel)
        
        self.console.print()
    
    def select_profile(self, profiles: List[Dict[str, str]]) -> Optional[Tuple[str, str]]:
        """Select a profile from the list.
        
        Args:
            profiles: List of profile dictionaries with keys: ip_address, profile_name, username
            
        Returns:
            Tuple of (ip_address, profile_name) or None if cancelled
        """
        if not profiles:
            return None
        
        self.console.print()
        self.console.print(Rule("[bold bright_cyan]Profile Selection[/bold bright_cyan]", style="bright_cyan"))
        self.console.print()
        self.console.print("[dim]Multiple profiles found. Select one to use:[/dim]\n")
        
        table = Table(
            show_header=True,
            header_style="bold bright_cyan",
            border_style="cyan",
            box=None,
            padding=(0, 2)
        )
        table.add_column("#", width=4, style="bright_yellow", justify="center")
        table.add_column("IP Address", style="bright_cyan", width=25)
        table.add_column("Profile", style="cyan", width=15)
        table.add_column("Username", style="white", width=20)
        
        for idx, profile in enumerate(profiles, 1):
            table.add_row(
                str(idx),
                profile['ip_address'],
                profile['profile_name'],
                profile['username'] or '—'
            )
        
        self.console.print(table)
        self.console.print()
        
        try:
            selection = Prompt.ask(
                "[bold bright_cyan]Enter profile number[/bold bright_cyan]",
                default="1"
            )
            
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
