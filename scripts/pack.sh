#!/usr/bin/env bash
# Source package: ./scripts/pack.sh [-o output-directory] [-n archive-name]
# Includes current tracked and non-ignored source; shared credential exclusions are in pack.py.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${PYTHON:-python3}" "$SCRIPT_DIR/pack.py" "$@"
