#!/usr/bin/env bash
# Builds the FAT image for the `system_data` partition (0x600000, 2 MiB) from a directory of system files
# (boot.bit.hs, gameboy.bit.hs, gba.bit.hs, ...). Same parameters as firmware/handheld/flash_system_data.py,
# but writes the image to a file instead of flashing it, so it can be checked before use.
#
# Usage: scripts/rev2/make_system_data.sh [input_dir] [output_image]
#   defaults: dist/rev2/system_data  ->  dist/rev2/system_data.fat.bin
# Needs ESP-IDF v5.4.3 (installed by the firmware build) and the Python package `construct`.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
input="${1:-$root/dist/rev2/system_data}"
output="${2:-$root/dist/rev2/system_data.fat.bin}"
idf="${IDF_PATH_543:-$HOME/.espressif/esp-idf/v5.4.3}"
python="${PYTHON:-python3}"

"$python" "$idf/components/fatfs/fatfsgen.py" \
    --output "$output" \
    --partition_size 0x200000 \
    --fat_count 1 \
    --long_name_support \
    "$input"

echo "Image: $output"
shasum -a 256 "$input"/*
