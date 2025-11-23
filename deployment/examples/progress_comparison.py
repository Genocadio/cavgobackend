#!/usr/bin/env python3
"""
Python (Rich) Progress Bar Example
Beautiful, concise, and powerful
"""

from rich.progress import Progress, SpinnerColumn, BarColumn, TextColumn, TimeElapsedColumn
from rich.console import Console
from rich.panel import Panel
from rich.table import Table
from rich.prompt import Confirm, Prompt
import time

console = Console()

def deploy_with_python_rich():
    """Example deployment with Rich - this is what you'd write"""
    
    services = ["cavgomain", "cavgotrips", "cavgobooking", "cavgomqt"]
    
    # Interactive selection
    console.print("\n[bold blue]Select services to deploy:[/bold blue]")
    selected = Prompt.ask(
        "Services (comma-separated or 'all')",
        default="all"
    )
    
    if selected != "all":
        services = [s.strip() for s in selected.split(",")]
    
    # Confirmation
    if not Confirm.ask(f"\n[bold]Deploy {len(services)} services?[/bold]"):
        console.print("[yellow]Cancelled[/yellow]")
        return
    
    # Beautiful progress display
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
        TimeElapsedColumn(),
        console=console,
    ) as progress:
        # Overall progress
        main_task = progress.add_task(
            "[bold blue]Overall Deployment Progress[/bold blue]",
            total=len(services)
        )
        
        # Per-service tasks
        service_tasks = {}
        for service in services:
            task_id = progress.add_task(
                f"[cyan]{service}[/cyan]",
                total=100
            )
            service_tasks[service] = task_id
        
        # Deploy each service
        for service in services:
            task_id = service_tasks[service]
            
            # Step 1: Pull image
            progress.update(task_id, description="[yellow]Pulling image...[/yellow]")
            time.sleep(0.5)  # Simulate work
            progress.update(task_id, advance=30)
            
            # Step 2: Start container
            progress.update(task_id, description="[yellow]Starting container...[/yellow]")
            time.sleep(0.3)
            progress.update(task_id, advance=40)
            
            # Step 3: Health check
            progress.update(task_id, description="[yellow]Health check...[/yellow]")
            time.sleep(0.2)
            progress.update(task_id, advance=30)
            
            # Done
            progress.update(task_id, description="[green]✓ Deployed successfully[/green]")
            progress.advance(main_task)
    
    # Summary table
    console.print("\n")
    table = Table(title="[bold green]Deployment Summary[/bold green]")
    table.add_column("Service", style="cyan", no_wrap=True)
    table.add_column("Status", style="green")
    table.add_column("Time", style="yellow")
    table.add_column("Image", style="dim")
    
    for service in services:
        table.add_row(
            service,
            "[green]✓ Deployed[/green]",
            "2.3s",
            f"genoyves/cavgo-system:{service}"
        )
    
    console.print(table)
    console.print("\n[bold green]🎉 Deployment completed successfully![/bold green]\n")

if __name__ == "__main__":
    deploy_with_python_rich()


