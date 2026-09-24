#!/usr/bin/env bash
# Regenerate every moonmeow launcher/shortcut/notification icon, vector drawable, banner and
# store image from store-assets/meow/moonmeow.svg.
# Run from anywhere:  bash store-assets/meow/build.sh
# Needs: rsvg-convert (librsvg) and python3 with Pillow. See build_icons.py for the outputs
# and why most of them overwrite upstream files in place.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v rsvg-convert >/dev/null || { echo "rsvg-convert (librsvg) not found" >&2; exit 1; }
python3 -c 'import PIL' 2>/dev/null || { echo "python3 Pillow not found" >&2; exit 1; }
exec python3 "$HERE/build_icons.py" "$@"
