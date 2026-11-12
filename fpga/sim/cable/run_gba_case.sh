#!/usr/bin/env bash
# GB-154: run one GBA cable case. Usage: run_gba_case.sh NAME [gba_cable_sim options...]
# Mario Kart: Super Circuit unless GB154_ROM is set. Output: build/NAME (run.log, result.txt, frames.csv, *.png).
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
ROMS="/mnt/h/DevCentral/workspaces/SMS_GG_Game_Bub_Port/Arhiva/backups"
BIOS="$ROMS/System/gba.bios.bin"
ROM="${GB154_ROM:-$ROMS/roms/gba/12 Link Cable Multiplayer/Mario Kart - Super Circuit.gba}"
NAME="$1"; shift
OUT="$REPO/fpga/sim/cable/build/$NAME"
mkdir -p "$OUT"
START=$(date +%s)
"$REPO/fpga/sim/cable/build/gba_cable_sim" --bios "$BIOS" --rom "$ROM" --out "$OUT" "$@" > "$OUT/run.log" 2>&1
RC=$?
python3 "$REPO/fpga/sim/cable/ppm2png.py" "$OUT"/*.ppm > /dev/null 2>&1 && rm -f "$OUT"/*.ppm
echo "rc=$RC seconds=$(( $(date +%s) - START ))" > "$OUT/exit.txt"
cat "$OUT/exit.txt"
grep -E '^(RESULT|ERROR)' "$OUT/run.log"
exit $RC
