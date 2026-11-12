#!/usr/bin/env bash
# GB-146: run one Tetris DX case on the dual-core consoles. Usage: run_dual.sh NAME [dual_sim options...]
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
BIOS="/mnt/h/DevCentral/workspaces/Chromatic GameBub/sources/Gameboy_MiSTer/BootROMs/bin/cgb_boot.bin"
ROM="${GB146_ROM:-/mnt/h/DevCentral/workspaces/SMS_GG_Game_Bub_Port/Arhiva/backups/roms/gbc/03 Puzzle Logic/Tetris DX (JU) [C][!].gbc}"
NAME="$1"; shift
OUT="$REPO/fpga/sim/cable/build/$NAME"
mkdir -p "$OUT"
START=$(date +%s)
"$REPO/fpga/sim/cable/build/dual_sim" --bios "$BIOS" --rom "$ROM" --out "$OUT" "$@" > "$OUT/run.log" 2>&1
RC=$?
echo "rc=$RC seconds=$(( $(date +%s) - START ))" > "$OUT/exit.txt"
cat "$OUT/exit.txt"
grep -E '^(RESULT|ERROR)' "$OUT/run.log"
exit $RC
