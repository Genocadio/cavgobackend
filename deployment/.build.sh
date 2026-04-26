#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

VENV_DIR=".venv"
REQ_FILE="requirements.txt"
REQ_HASH_FILE="$VENV_DIR/.requirements.sha256"

cleanup() {
    if [[ -n "${VIRTUAL_ENV:-}" ]] && declare -f deactivate >/dev/null 2>&1; then
        deactivate
    fi
}
trap cleanup EXIT

if [[ ! -d "$VENV_DIR" ]]; then
    echo "Creating virtual environment in $VENV_DIR..."
    python3 -m venv "$VENV_DIR"
fi

# shellcheck source=/dev/null
source "$VENV_DIR/bin/activate"

# Keep pip available and current in the environment.
python -m pip install --upgrade pip >/dev/null

if [[ ! -f "$REQ_FILE" ]]; then
    echo "Error: $REQ_FILE not found in $SCRIPT_DIR" >&2
    exit 1
fi

CURRENT_HASH="$(shasum -a 256 "$REQ_FILE" | awk '{print $1}')"
STORED_HASH=""
if [[ -f "$REQ_HASH_FILE" ]]; then
    STORED_HASH="$(cat "$REQ_HASH_FILE")"
fi

NEEDS_INSTALL=0
if [[ "$CURRENT_HASH" != "$STORED_HASH" ]]; then
    NEEDS_INSTALL=1
else
    if ! python -m pip check >/dev/null 2>&1; then
        NEEDS_INSTALL=1
    fi
fi

if [[ $NEEDS_INSTALL -eq 1 ]]; then
    echo "Installing/updating Python requirements..."
    pip install -r "$REQ_FILE"
    echo "$CURRENT_HASH" > "$REQ_HASH_FILE"
else
    echo "Python requirements are already satisfied."
fi

python build-images.py "$@"
