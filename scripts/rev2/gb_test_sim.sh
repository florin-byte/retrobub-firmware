#!/usr/bin/env bash
# Builds a headless Verilator simulator of the Game Boy core and runs test ROMs through it.
# Pass/fail detection: mooneye (Fibonacci registers at the end), blargg (serial output, or the
# result block in cartridge RAM at $A000).
#
# Usage (from the repository root):
#   scripts/rev2/gb_test_sim.sh <cgb boot ROM, 2304 bytes> <folder with .gb test ROMs> [jobs]
#
# Needs mill (fpga/), Verilator and SDL2 (pkg-config). The test ROMs are not part of this
# repository (mooneye-test-suite, blargg). Writes the simulator to build/gb-test-sim/ and the
# results to build/gb-test-sim/results.txt. The simulator writes <rom>.sav next to ROMs with
# cartridge RAM; they are deleted after each run.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
BIOS="$(cd "$(dirname "${1:?boot ROM path}")" && pwd)/$(basename "$1")"
ROMS="$(cd "${2:?test ROM folder}" && pwd)"
JOBS="${3:-8}"
OUT="$REPO/build/gb-test-sim"
SIM="$REPO/fpga/sim"

mkdir -p "$OUT"
(cd "$REPO/fpga" && ./mill -i --no-build-lock root.runMain net.gamebub.core.gameboy.SimGameboy \
    --target-dir="$OUT/generated")
(cd "$OUT/generated" && verilator --cc -f filelist.f --top-module SimGameboy --exe \
    "$SIM/test_main.cpp" "$SIM/gb/simulator.cpp" "$SIM/gb/cartridge.cpp" "$SIM/framebuffer.cpp" \
    -CFLAGS "-std=c++20 -O2 -I$SIM -I$SIM/gb $(pkg-config sdl2 --cflags)" \
    -LDFLAGS "$(pkg-config sdl2 --libs)" \
    -Wno-fatal -Wno-WIDTH -O3 --x-assign fast --x-initial fast --build -j "$JOBS" \
    --Mdir "$OUT/obj" -o "$OUT/test_sim" > "$OUT/verilator.log" 2>&1) \
    || { echo "Verilator build failed, see $OUT/verilator.log"; exit 1; }

run_one() {
    local rom="$1" frames=1500
    case "$rom" in *blargg*) frames=5000 ;; esac
    rm -f "${rom%.gb}.sav"
    local line
    line=$("$OUT/test_sim" "$BIOS" "$rom" "$frames" 2>/dev/null | grep -aE '^(PASS|FAIL)' || true)
    rm -f "${rom%.gb}.sav"
    echo "${line:-NONE  $(basename "$rom")}  ${rom#"$ROMS"/}"
}
export -f run_one
export OUT BIOS ROMS

find "$ROMS" -name '*.gb' | sort | xargs -P "$JOBS" -I{} bash -c 'run_one "$@"' _ {} \
    | sort -k4 > "$OUT/results.txt"
echo "$(grep -c '^PASS' "$OUT/results.txt") PASS, $(grep -vc '^PASS' "$OUT/results.txt") not passing" \
    "($(wc -l < "$OUT/results.txt") ROMs), details in $OUT/results.txt"
