# Cross-Platform Deployment: Core Logic + Platform Wrappers

## Your Requirement

You want:
- ✅ **Shared core logic** - One place for business logic
- ✅ **Platform-specific wrappers** - Bash for Linux/macOS, PowerShell for Windows
- ✅ **Same functionality** - Identical behavior across platforms

## Current State

You already have:
- `deploy-cavgo.sh` - Bash version (827 lines)
- `deploy-cavgo.ps1` - PowerShell version (duplicated logic)

**Problem**: Logic is duplicated, maintenance nightmare!

---

## Solution Architectures

### Option 1: **Go Core + Thin Wrappers** ⭐⭐⭐⭐⭐ (BEST)

**Architecture:**
```
deploy-cavgo-core (Go binary)
├── deploy-cavgo.sh (Bash wrapper - 50 lines)
└── deploy-cavgo.ps1 (PowerShell wrapper - 50 lines)
```

**Structure:**
```
deployment/
├── core/
│   ├── main.go           # Core deployment logic
│   ├── deploy.go         # Deployment operations
│   ├── ssh.go            # SSH operations
│   ├── docker.go         # Docker operations
│   └── config.go         # Configuration
├── deploy-cavgo.sh       # Unix wrapper (calls binary)
└── deploy-cavgo.ps1     # Windows wrapper (calls binary)
```

**Go Core Example:**
```go
// core/main.go
package main

import (
    "flag"
    "fmt"
    "os"
)

func main() {
    var (
        deployAll    = flag.Bool("all", false, "Deploy all services")
        selectMode   = flag.Bool("choose", false, "Interactive selection")
        cleanMode    = flag.Bool("clean", false, "Clean mode")
        targetHost   = flag.String("host", "api.gocavgo.com", "Target host")
    )
    flag.Parse()

    config := &Config{
        Host: *targetHost,
        DataDir: "/opt/cavgo-data",
        RemoteDir: "/opt/cavgo-system",
    }

    deployer := NewDeployer(config)
    
    if *cleanMode {
        if err := deployer.Clean(); err != nil {
            fmt.Fprintf(os.Stderr, "Error: %v\n", err)
            os.Exit(1)
        }
    }

    services, err := deployer.SelectServices(*deployAll, *selectMode)
    if err != nil {
        fmt.Fprintf(os.Stderr, "Error: %v\n", err)
        os.Exit(1)
    }

    if err := deployer.Deploy(services); err != nil {
        fmt.Fprintf(os.Stderr, "Error: %v\n", err)
        os.Exit(1)
    }
}
```

**Bash Wrapper (50 lines):**
```bash
#!/bin/bash
# Thin wrapper - just calls the Go binary

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_BINARY="$SCRIPT_DIR/core/deploy-cavgo-core"

# Build if needed
if [ ! -f "$CORE_BINARY" ]; then
    echo "Building deployment core..."
    cd "$SCRIPT_DIR/core" && go build -o deploy-cavgo-core .
fi

# Pass all arguments to core
exec "$CORE_BINARY" "$@"
```

**PowerShell Wrapper (50 lines):**
```powershell
# Thin wrapper - just calls the Go binary

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$CoreBinary = Join-Path $ScriptDir "core\deploy-cavgo-core.exe"

# Build if needed
if (-not (Test-Path $CoreBinary)) {
    Write-Host "Building deployment core..."
    Push-Location (Join-Path $ScriptDir "core")
    go build -o deploy-cavgo-core.exe .
    Pop-Location
}

# Pass all arguments to core
& $CoreBinary $args
```

**Advantages:**
- ✅ **Single source of truth** - All logic in Go
- ✅ **Cross-platform binary** - One binary for all platforms
- ✅ **Thin wrappers** - Just 50 lines each
- ✅ **Easy distribution** - Single executable
- ✅ **Type safety** - Compile-time checks
- ✅ **Fast** - Compiled performance
- ✅ **Already in your stack** - You use Go!

**Disadvantages:**
- ❌ **Compilation step** - Need to build binary
- ❌ **Development overhead** - More verbose than bash

**Build Script:**
```bash
#!/bin/bash
# build-cross-platform.sh

cd core
GOOS=linux GOARCH=amd64 go build -o ../deploy-cavgo-core-linux .
GOOS=windows GOARCH=amd64 go build -o ../deploy-cavgo-core-windows.exe .
GOOS=darwin GOARCH=amd64 go build -o ../deploy-cavgo-core-darwin .
GOOS=darwin GOARCH=arm64 go build -o ../deploy-cavgo-core-darwin-arm64 .
```

---

### Option 2: **Python Core + Thin Wrappers** ⭐⭐⭐⭐

**Architecture:**
```
deploy-cavgo-core.py (Python module)
├── deploy-cavgo.sh (Bash wrapper - 30 lines)
└── deploy-cavgo.ps1 (PowerShell wrapper - 30 lines)
```

**Python Core Example:**
```python
# core/deploy.py
import argparse
import sys
from deployer import Deployer
from config import Config

def main():
    parser = argparse.ArgumentParser(description='Deploy CavGo System')
    parser.add_argument('--all', action='store_true', help='Deploy all services')
    parser.add_argument('--choose', action='store_true', help='Interactive selection')
    parser.add_argument('--clean', action='store_true', help='Clean mode')
    parser.add_argument('--host', default='api.gocavgo.com', help='Target host')
    
    args = parser.parse_args()
    
    config = Config(
        host=args.host,
        data_dir='/opt/cavgo-data',
        remote_dir='/opt/cavgo-system'
    )
    
    deployer = Deployer(config)
    
    if args.clean:
        deployer.clean()
    
    services = deployer.select_services(args.all, args.choose)
    deployer.deploy(services)

if __name__ == '__main__':
    sys.exit(main())
```

**Bash Wrapper (30 lines):**
```bash
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_SCRIPT="$SCRIPT_DIR/core/deploy.py"

# Ensure Python 3
if ! command -v python3 &> /dev/null; then
    echo "Error: python3 not found"
    exit 1
fi

# Pass all arguments
exec python3 "$CORE_SCRIPT" "$@"
```

**PowerShell Wrapper (30 lines):**
```powershell
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$CoreScript = Join-Path $ScriptDir "core\deploy.py"

# Ensure Python 3
if (-not (Get-Command python3 -ErrorAction SilentlyContinue)) {
    Write-Error "Error: python3 not found"
    exit 1
}

# Pass all arguments
python3 $CoreScript $args
```

**Advantages:**
- ✅ **No compilation** - Just run Python
- ✅ **Rapid development** - Fast iteration
- ✅ **Rich ecosystem** - Libraries for everything
- ✅ **Cross-platform** - Python runs everywhere
- ✅ **Easy to read** - Python is very readable

**Disadvantages:**
- ❌ **Runtime dependency** - Need Python installed
- ❌ **Slower** - Interpreted language
- ❌ **Package management** - Need requirements.txt, venv

---

### Option 3: **Hybrid: Go Core + Shell Helpers** ⭐⭐⭐⭐

**Architecture:**
```
deploy-cavgo-core (Go binary for complex logic)
├── helpers/
│   ├── ssh-helper.sh
│   ├── docker-helper.sh
│   └── config-helper.sh
├── deploy-cavgo.sh (Bash orchestrator)
└── deploy-cavgo.ps1 (PowerShell orchestrator)
```

**Use Go for:**
- Complex logic (image digest comparison)
- State management
- Error handling
- Configuration parsing

**Use Shell for:**
- Simple command execution
- File operations
- Environment variable access

**Advantages:**
- ✅ **Best of both worlds** - Go for logic, shell for commands
- ✅ **Flexible** - Can mix and match
- ✅ **Leverages strengths** - Each tool does what it's best at

**Disadvantages:**
- ❌ **More complex** - Two languages to maintain
- ❌ **Integration overhead** - Need to coordinate between Go and shell

---

### Option 4: **Pure Go (No Wrappers)** ⭐⭐⭐

**Architecture:**
```
deploy-cavgo (Single Go binary)
```

**Usage:**
```bash
# Linux/macOS
./deploy-cavgo --all

# Windows
.\deploy-cavgo.exe --all
```

**Advantages:**
- ✅ **Simplest** - One binary, no wrappers
- ✅ **Consistent** - Same binary everywhere
- ✅ **Easy distribution** - Just copy the binary

**Disadvantages:**
- ❌ **No platform-specific optimizations** - Can't leverage shell features
- ❌ **Less familiar** - Team might prefer shell scripts

---

## Comparison Table

| Feature | Go Core + Wrappers | Python Core + Wrappers | Pure Go | Hybrid |
|---------|-------------------|------------------------|---------|--------|
| **Development Speed** | Medium | Fast | Medium | Slow |
| **Runtime Dependency** | None (binary) | Python 3 | None | None |
| **Cross-Platform** | ✅ Excellent | ✅ Excellent | ✅ Excellent | ✅ Good |
| **Code Reuse** | ✅ 100% | ✅ 100% | ✅ 100% | ⚠️ Partial |
| **Maintenance** | ✅ Easy | ✅ Easy | ✅ Easy | ❌ Complex |
| **Performance** | ✅ Fast | ⚠️ Medium | ✅ Fast | ✅ Fast |
| **Distribution** | ✅ Single binary | ❌ Python + deps | ✅ Single binary | ⚠️ Multiple files |
| **Learning Curve** | Medium | Low | Medium | High |

---

## Recommendation: **Go Core + Thin Wrappers** ⭐⭐⭐⭐⭐

### Why This is Best:

1. **Single Source of Truth**
   - All logic in Go (one place)
   - Wrappers are just 30-50 lines each
   - No code duplication

2. **Cross-Platform Binary**
   - Build once, run anywhere
   - No runtime dependencies
   - Fast execution

3. **Already in Your Stack**
   - You use Go for `cavgotrips` and `cavgobooking`
   - Team already knows Go
   - Consistent with codebase

4. **Easy Distribution**
   ```bash
   # Build for all platforms
   ./build-cross-platform.sh
   
   # Result:
   deploy-cavgo-core-linux
   deploy-cavgo-core-windows.exe
   deploy-cavgo-core-darwin
   deploy-cavgo-core-darwin-arm64
   ```

5. **Thin Wrappers**
   - Bash: Just calls binary, handles PATH
   - PowerShell: Just calls binary, handles PATH
   - No logic duplication

### Implementation Structure:

```
deployment/
├── core/
│   ├── main.go              # Entry point
│   ├── deployer.go          # Main deployment logic
│   ├── ssh.go               # SSH operations
│   ├── docker.go            # Docker operations
│   ├── config.go            # Configuration
│   ├── services.go          # Service management
│   ├── firebase.go           # Firebase credentials
│   ├── backup.go             # Backup operations
│   └── go.mod               # Dependencies
├── deploy-cavgo.sh          # Bash wrapper (50 lines)
├── deploy-cavgo.ps1         # PowerShell wrapper (50 lines)
├── build-cross-platform.sh  # Build script
└── README.md                 # Documentation
```

### Example Go Core Structure:

```go
// core/deployer.go
package main

type Deployer struct {
    config   *Config
    ssh      *SSHClient
    docker   *DockerClient
    services []Service
}

func NewDeployer(config *Config) *Deployer {
    return &Deployer{
        config: config,
        ssh:    NewSSHClient(config),
        docker: NewDockerClient(config),
    }
}

func (d *Deployer) Deploy(services []string) error {
    // 1. Setup directories
    if err := d.setupDirectories(); err != nil {
        return err
    }
    
    // 2. Handle Firebase credentials
    if err := d.handleFirebaseCredentials(); err != nil {
        return err
    }
    
    // 3. Copy files
    if err := d.copyFiles(); err != nil {
        return err
    }
    
    // 4. Pull images
    if err := d.pullImages(services); err != nil {
        return err
    }
    
    // 5. Start services
    if err := d.startServices(services); err != nil {
        return err
    }
    
    return nil
}
```

---

## Migration Path

### Phase 1: Extract Core Logic (Week 1)
1. Identify shared logic from both scripts
2. Create Go core structure
3. Implement core deployment functions

### Phase 2: Create Wrappers (Week 1)
1. Create thin Bash wrapper
2. Create thin PowerShell wrapper
3. Test on both platforms

### Phase 3: Migrate Features (Week 2)
1. Migrate SSH operations
2. Migrate Docker operations
3. Migrate service detection
4. Migrate Firebase handling

### Phase 4: Testing & Polish (Week 2)
1. Test on Linux
2. Test on macOS
3. Test on Windows
4. Update documentation

---

## Alternative: Python if You Prefer

If you prefer Python over Go:

**Advantages:**
- ✅ Faster development
- ✅ No compilation step
- ✅ Rich ecosystem
- ✅ Easy to read

**Trade-offs:**
- ❌ Need Python installed
- ❌ Slower execution
- ❌ Package management

**Still recommend Go** because:
- You already use Go in your codebase
- Single binary is easier to distribute
- Better performance
- No runtime dependencies

---

## Final Recommendation

**Go Core + Thin Wrappers** is the best solution because:

1. ✅ **Single source of truth** - All logic in one place
2. ✅ **Cross-platform** - Works on Linux, macOS, Windows
3. ✅ **Already in your stack** - You use Go!
4. ✅ **Easy distribution** - Single binary per platform
5. ✅ **Thin wrappers** - Just 50 lines each
6. ✅ **No duplication** - Write once, run everywhere

**Next Steps:**
1. Create `deployment/core/` directory
2. Start with Go core structure
3. Create thin wrappers
4. Migrate logic incrementally

Would you like me to create a proof-of-concept Go core with wrappers?


