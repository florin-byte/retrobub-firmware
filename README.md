# Retro Bub firmware for the Game Bub

This is my firmware for the [Game Bub](https://github.com/elipsitz/gamebub). It includes Eli's 1.1.0-beta3 fixes with
the things I added and fixed. The repo also has the FPGA framework and the built-in cores (boot, Game Boy / Game Boy
Color and Game Boy Advance) with my fixes, so you can build all of it. It is not the official Game Bub firmware.

The ready-to-flash files are in the [releases](../../releases).

## Update prepared on 23 Oct 2026

I tested the beta3 integration on my rev2: startup, GB/GBA speaker and headphone audio, GB save/load with music,
GG/Lynx on-demand states and per-core Swap A/B, plus the requested SD-core runtime checks.
Those console results used MCU commit `3035e84`. The version-only correction reports `1.1.0-beta3`
(MCU commit `f3b5165`); the rebuilt binaries have not yet been reflashed for a startup/About check.
The FPGA images remain unchanged at `a04f4e1`. The new rev4 build meets timing and package checks,
but remains hardware-untested. Stock GG/Lynx containers are left for other users to test.

The firmware version in About now comes from package version `1.1.0-beta3`. On the first boot after an
update, the existing update notification can appear once. This metadata correction changes no emulation,
save-state or remapping logic and needs no new FPGA build.

This update includes the battery shutdown deadlock fix and 20/10/5% alerts, 10 MHz SPI read cap,
optional file selection, shorter headphone startup and the USB storage B-button fix from beta3.
The built-in GB/GBA cores also have the beta3 audio/core fixes. Physical GB cartridge reset, GBA Flash-save
specific behavior, controlled critical-battery shutdown and dock/gamepad behavior were not separately verified.

## What is new compared to Eli's firmware

Save states and saves:
- Save states for the cores on the SD card. Eli's firmware only moves files between the card and a core at the start
  and at the exit; I added "on demand" files that move while the game runs (`firmware/handheld/src/core/on_demand.rs`).
  A core uses them only with json (see below). My Lynx and Game Gear cores use it, other cores can too.
- Game Boy / Game Boy Color save states (State Slot 1-4, Save State, Load State in the core menu).
- Saves are written safely: first to a temporary file, then renamed, so a cut (battery, card) in the middle does not
  break the old save. Same for the core settings.
- The previous battery save is kept as `<rom>.sav.bak` when it changes, so a save that goes wrong can be recovered.
  This one is new, I am still testing it.
- A core that says its save is empty does not get an empty `.sav` on the card any more.

Game Boy / Game Boy Color core:
- Fixes from my older rev2 firmware: the HALT bug, IE / IF register bits, the P1 select bits, MBC1 RAM banking,
  the OAM DMA start, MBC1M multicarts (detected by the firmware). 49 of the 105 mooneye and blargg test roms pass,
  36 before.
- The cartridge clock (RTC) does not jump forward any more when the device clock is behind the save.

Game Boy Advance:
- The Classic NES Series and Famicom Mini cartridges work (the controls and the "Game Pak Error").

Cheats:
- Cheats chosen in the core menu, from a RetroArch `.cht` file next to the rom (same name as the rom). A core needs a
  cheat engine for it (my NES build has one).

Controls:
- Swap A/B in each rebuilt core's menu, saved per core, including Game Boy and GBA.
- The framework-v2 list uses `0xF1001208`; `0xF1001018` is video darken. Copy matching JSON files and
  bitstreams together. The old Swap A/B checkbox at `0x1018` must not be paired with a new framework-v2 bitstream.
- Menus keep their original controls. Handheld remapping and docked gamepad remapping are separate.
- The platform keeps the Nearest scaling filter for cores that use the aspect scaler.

Look and feel:
- Themes: Game Bub, DMG, GBC, GBA, NES and Sky (blue, after my Retro Bub case), each with a dark mode.
- My "Retro Bub" logo, also in the boot animation, in the colors of the theme.
- Long names of cores and files scroll, the core author stays visible, the lists scroll to the selected item.
- Status bar: a clock with your time zone (UTC offset, daylight saving for Europe or North America, 24 hour clock)
  and "CHG" while charging.
- Rom files are found also with capital extensions (`GAME.GBA`).
- On rev2 the tutorial at the first start shows a rev2 Game Bub (Eli's shows the rev4 one).

## Cores on the SD card

- Atari Lynx: https://github.com/florin-byte/gamebub-lynx
- Sega Game Gear: https://github.com/florin-byte/gamebub-gg

Use the matching new GG/Lynx archives with this firmware and choose the JSON files from
`sdcard/savestate-firmware/`. Their stock `sdcard/cores/` alternative uses a four-slot container written
at normal game exit; that stock path is left for other users to test. Individual `.ssN` files and stock
containers are separate, with no automatic conversion.

How the save states work: when you press Save State, the firmware sends the core `FILE_READ_START` for its State
file, the core writes the whole machine into its memory and answers with the size, the firmware reads it and writes
`<rom>.ss0` to `.ss3` next to the rom (slot 1 to 4). Load State is the same the other way (`FILE_WRITE_START`,
`FILE_WRITE_END`). In the core's json: a file with `"on_demand": true` in `files.json`, and in `settings.json` a list
with `"file_slots": <file id>` and two actions with `"file": <file id>` (one of them with `"load": true`). The Lynx
README explains it step by step.

Swap A/B for a rebuilt framework-v2 core: use `"hardware": { "button_remap": true }` in `core.json`.
The firmware adds one Off/On list before Reset if JSON does not already contain it. Off writes
`0x0B0A0908`, On writes `0x0A0B0908` at `0xF1001208`, setting ID `0x7FF1`. Old checkbox IDs/values are not reused.

## Install

Back up your SD core folders, settings and saves before updating. Use files for your board only.
The update has `retrobub-firmware-rev2.uf2` and `retrobub-firmware-rev4.uf2`; each contains the application
and matching built-in boot/GB/GBA images. UF2 pages do not include NVS, the bootloader or the DFU partition.

- rev4: install the rev4 UF2 in the existing Game Bub DFU mode, as in Eli's firmware-update guide.
  Keep a backup and a known-good firmware to return to; this new rev4 build is hardware-untested.
- rev2: use the espflash archive and its README. Hold Home, press Power with USB-C connected to enter
  download mode. Read the complete 8 MiB flash backup first. Flash the supplied ELF and system_data together;
  the instructions do not erase NVS. They assume the existing 1.1 partition layout (factory at 0x100000,
  system_data at 0x600000); migrating from the old 0.1 layout is a separate operation.

Check `system/` on the SD card before booting the update. If `boot.bit.hs`, `gameboy.bit.hs` or `gba.bit.hs`
exist there, they override the internal images. Back them up and replace them with the matching files from
the same board package, or move those three overrides off the card so the new internal images are used.
Do not remove BIOS files or other system files. The rev2 espflash archive includes matching replacements.
The UF2-only route can use the new internal images after retiring the three old overrides.

Update SD-core JSON files and bitstreams as matched sets from each core's maintainer. The new GG/Lynx
packages provide both stock and on-demand alternatives; choose the on-demand folder for this firmware.
No other maintainer's core is included in this firmware package. To return to a previous installation,
restore its matching firmware, built-in overrides and core files together; the rev2 full-flash restoration
command is in the espflash README. Saves/settings have their own backups.

## Building

Firmware (ESP-IDF 5.4.3, Rust with the `esp` toolchain from [espup](https://github.com/esp-rs/espup)), from
`firmware/handheld/`:

```bash
. ~/export-esp.sh
unset IDF_PATH IDF_PYTHON_ENV_PATH        # avoid an exported ESP-IDF 5.3.1 environment
cargo build --release --features rev4        # or rev2
```

Build from a git clone, the build needs the commit hash. Release build-inputs include the exact dependency
lock used for the shipped firmware; restore it to firmware/handheld/Cargo.lock and use --locked to keep
those dependency versions. The repository currently ignores Cargo.lock.

The built-in cores, from the root of the repo (Java 17, Python 3 with `edalize==0.6.7`, Vivado; I use 2025.2):

```bash
python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.boot.HandheldBoot --name boot --target gamebub_rev4
python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.gameboy.HandheldGameboy --name gameboy --target gamebub_rev4
python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.gba.HandheldGba --name gba --target gamebub_rev4
```

The accepted GBA rev4 implementation used Explore P&R on the original synthesis checkpoint.
Implementation directives can change timing without RTL changes; check setup and hold for every build.

Run Vivado in each bundle under `dist/vivado/` (`make` on Linux, `build.bat` on Windows), then make the compressed
`.bit.hs` files the firmware loads:

```bash
python3 scripts/rev2/finish_bitstream.py path/to/boot-gamebub_rev4.bit --name boot
```

The `.uf2` (firmware + built-in cores), from `firmware/handheld/`, with the three `.bit.hs` files in one folder
(needs `IDF_PATH` pointing to ESP-IDF 5.4.3 for the FAT image):

```bash
python3 generate_firmware_uf2.py --firmware target/xtensa-esp32s3-espidf/release/handheld --system-data path/to/folder --hw-revision 4 --output retrobub-firmware-rev4.uf2
```

## License and credits

- Eli's firmware and FPGA framework, also with my changes in his files, keep his licenses: GPL-3.0-only for the
  firmware and the scripts (`LICENSE-GPL-V3`), CERN-OHL-S-2.0 for `fpga/` (`LICENSE-CERN-OHL-S`).
- My own files (listed in `THIRD-PARTY.md`) are GPL-2.0-or-later (`LICENSE-GPL-V2`, `LICENSE-GPL-V3`). The firmware
  as a whole stays GPL-3.0-only, because it is Eli's GPL-3.0-only program.

Everybody whose code is in here, and under which license: `THIRD-PARTY.md`. Thanks to Eli for the Game Bub.

"Game Bub" is Eli's project and trademark, this is a community firmware. No bios and no games in here.
