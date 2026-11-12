#!/usr/bin/env bash
# GB-156: run one case on two consoles with the production HandheldGba (two cores each).
# Usage: run_gba_dual.sh NAME [gba_dual_sim options...]
# Mario Kart: Super Circuit unless GB156_ROM is set; GB156_BIN names the simulator binary.
# Output: build/NAME (run.log, result.txt, frames.csv, *.png).
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
ROMS="/mnt/h/DevCentral/workspaces/SMS_GG_Game_Bub_Port/Arhiva/backups"
BIOS="$ROMS/System/gba.bios.bin"
ROM="${GB156_ROM:-$ROMS/roms/gba/12 Link Cable Multiplayer/Mario Kart - Super Circuit.gba}"
NAME="$1"; shift
OUT="$REPO/fpga/sim/cable/build/$NAME"
mkdir -p "$OUT"
START=$(date +%s)
"$REPO/fpga/sim/cable/build/${GB156_BIN:-gba_dual_sim}" --bios "$BIOS" --rom "$ROM" --out "$OUT" "$@" > "$OUT/run.log" 2>&1
RC=$?
python3 "$REPO/fpga/sim/cable/ppm2png.py" "$OUT"/*.ppm > /dev/null 2>&1 && rm -f "$OUT"/*.ppm
echo "rc=$RC seconds=$(( $(date +%s) - START ))" > "$OUT/exit.txt"
cat "$OUT/exit.txt"
grep -E '^(RESULT|ERROR)' "$OUT/run.log"
exit $RC
