#!/usr/bin/env bash
# GB-142: build/run four headless GB cores (two consoles, local link cable each).
# GB-146: the *-dual steps build the same consoles around the production DualCoreSync.
# GB-154: the *-gba steps build four headless GBA cores on the multiplayer cable.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SIM="$REPO/fpga/sim"
CABLE="$SIM/cable"
OUT="$CABLE/build"
generate() { # class, target dir, package (default gameboy)
  cd "$REPO/fpga"
  exec win-run gamebub-fpga-mill "$(basename "$REPO")" root.runMain "net.gamebub.core.${3:-gameboy}.$1" "--target-dir=sim/cable/$2"
}
build() { # top module, generated dir, main source, binary, jobs, extra verilator option
  local gen="$CABLE/$2"
  test -f "$gen/filelist.f" || { echo 'Generate RTL first.' >&2; exit 1; }
  mkdir -p "$OUT"
  # Windows-generated filelist entries use backslashes.
  python3 - "$gen" "$OUT/filelist-$1.f" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
with open(sys.argv[2], 'w') as out:
    for line in (root / 'filelist.f').read_text().splitlines():
        line = line.replace(chr(92), '/').strip()
        if line:
            out.write(str(root / line) + '\n')
PY
  verilator --cc -f "$OUT/filelist-$1.f" --top-module "$1" --exe \
    "$CABLE/$3" "$CABLE/framebuffer.cpp" \
    -CFLAGS "-std=c++20 -O3 -I$SIM" \
    -Wno-fatal -Wno-WIDTH -O3 --x-assign fast --x-initial fast ${6:-} -LDFLAGS -pthread --build -j "$5" \
    --Mdir "$OUT/obj-$1" -o "$OUT/$4"
}
case "${1:-}" in
  generate) generate SimCableGameboy generated ;;
  generate-dual) generate SimDualGameboy generated-dual ;;
  generate-gba) generate SimCableGba generated-gba gba ;;
  # GB-156: two consoles around the production HandheldGba (two cores each). The generator needs
  # GBA_DUAL_CORE=1 in the environment of mill itself (Windows side).
  generate-dual-gba) GBA_DUAL_CORE=1 generate SimDualGba generated-dual-gba gba ;;
  # BIN names the binary (default gba_dual_sim), so a running one is never replaced.
  build-dual-gba) build SimDualGba generated-dual-gba gba_dual_main.cpp "${BIN:-gba_dual_sim}" "${2:-4}" "--savable $CABLE/MMCME2_BASE.sv $CABLE/ODDR.sv $CABLE/xpm_cdc_handshake.sv" ;;
  run-dual-gba) shift; exec "$OUT/${BIN:-gba_dual_sim}" "$@" ;;
  build) build SimCableGameboy generated cable_main.cpp cable_sim "${2:-4}" ;;
  build-dual) build SimDualGameboy generated-dual dual_main.cpp dual_sim "${2:-4}" ;;
  build-gba) build SimCableGba generated-gba gba_cable_main.cpp gba_cable_sim "${2:-4}" --savable ;;
  run) shift; exec "$OUT/cable_sim" "$@" ;;
  run-dual) shift; exec "$OUT/dual_sim" "$@" ;;
  run-gba) shift; exec "$OUT/gba_cable_sim" "$@" ;;
  *) echo "Usage: $0 generate[-dual|-gba] | build[-dual|-gba] [jobs] | run[-dual|-gba] --bios PATH --rom PATH --out DIR [options]" >&2; exit 2 ;;
esac
