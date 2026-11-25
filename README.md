# Retro Bub firmware for the Game Bub

This is my firmware for the [Game Bub](https://github.com/elipsitz/gamebub). It includes Eli's changes up to firmware
1.0.3 with the things I added and fixed. The repo also has the FPGA framework and the built-in cores (boot, Game Boy / Game Boy
Color and Game Boy Advance) with my fixes, so you can build all of it. It is not the official Game Bub firmware.

The ready-to-flash files are in the [releases](../../releases).

## Update v0.6.0 (25 Nov 2026)

This one brings Eli's newer firmware work (his `v1.1-beta` branch up to `5fea463`, firmware 1.0.3) and adds disc
images, which the [Sega Saturn core](https://github.com/florin-byte/gamebub-saturn) needs.

From Eli:
- New lists in every menu, built only for the rows on the screen, and a small popup for choosing a value instead of
  the old list box. L and R scroll the core list by pages.
- The screen dims after a while without input (Settings).
- The power and volume buttons are handled in the firmware's Rust code.
- A core can ask for a minimum firmware version (`min_firmware` in `core.json`). Stock firmware requires the field;
  mine also loads cores that do not have it, so the cores you have keep working.
- SD card transfers aligned for DMA and a larger fast-seek map, for big and fragmented files.

Mine:
- **Disc images for cores with a CD drive** (`"disc": true` in `files.json`): `.cue` with one or many `.bin` files,
  a single `.bin`, or `.iso`; data and audio tracks; the table of contents for the core; changing the disc from the
  menu (Next disc). The firmware does the disc so the core does not have to, see the Saturn core's README. CHD images
  are not supported.
- The in-game Settings keep their look over the game (outlined rows, the value changed in place with Left / Right);
  Eli's popup is used in the menus outside the game.
- Saves are still written to a temporary file first and the previous one kept as `.bak`.
- A core with a clock gets the console's date and time before every start (`"rtc": { "address": "0x.." }` in
  `core.json`, the layout MiSTer cores take). The Saturn core uses it.

The built-in cores (boot, Game Boy / Game Boy Color, Game Boy Advance) and the Dual Core Link files are the ones
from v0.5.0, not rebuilt: only the firmware changed. The FPGA sources in this repo do have Eli's newer framework
(vibration in the input interface, a wider frame buffer read), which those bitstreams do not use yet.

I tested the rev2 build on my console: the menus in and out of a game, volume and brightness, games on several
cores, and the Saturn core. The rev4 file is built from the same source, but nobody has run it yet.

## Update v0.5.1 (19 Nov 2026)

- Fix: opening the in-game Settings could crash the console (out of memory), for example over every Game Gear game.
  The black outline of the in-game menu was drawn by copies of every text, icon and frame, which more than doubled
  the memory of the Settings panel. Now the firmware adds the outline itself after drawing: every transparent pixel
  next to a light one becomes black. The menu looks the same (frames are no longer cut by a neighbour's outline),
  and the Settings panel needs about a third less memory and less than half the peak while drawing.
- The FPGA scaler shared with the SD card cores handles Integer scaling of the game window (Game Gear v0.5.1).
  The built-in cores do not use it, so they are the same as in v0.5.0.

I tested the rev2 build on my two consoles (the in-game menu and Settings no longer crash). The rev4
files are built from the same source, but nobody has run them yet.

## Update of 19 Nov 2026 (v0.5.0)

This one moves everything to Eli's 1.1.0-beta4 and fixes what I found while testing it.

From Eli's beta4:
- The new in-game menu with a sidebar, and core load errors shown on the screen instead of a silent failure.
- A new input protocol for gamepads through the dock, and a setting to keep the built-in screen when docked.
- The cores on the SD card are checked before loading: `core.json`, `files.json`, `settings.json` and the bitstream.
  File names in a core's JSON must be inside the core folder, and settings at framework addresses are refused.
- Up to 16 files per core, natural sorting in the file lists, faster seeking in big files.
- FPGA: no more tearing from conflicting framebuffer writes, HDMI frame switching fix, 64 MB SDRAM on rev4,
  GBA 2 MHz serial fix.

Mine:
- The in-game menu is drawn over the darkened game, with a black outline under the text so it stays readable.
- In the in-game Settings the label no longer runs into its value.
- The lists keep a scrolling text only on the selected row. Before, every row had one, and after a GBA game the
  console could run out of memory and crash in a folder list.
- The Run Core screen fits long names.
- GBA Config Override finds `<game>.json` again.
- Swap A/B is added by the firmware to cores that ask for it (`hardware.button_remap`), since beta4 refuses the entry
  in their `settings.json`.

**The cores on the SD card must be updated too.** With beta4, older JSON files can fail to load ("invalid filename"
or a setting at a framework address). Use the new Game Gear (v0.5.0) and Lynx (v0.3.0) releases. The Lynx boot ROM
now goes in `cores/Retro-Bub.Lynx/lynxboot.img`. The Dual Core Link files in `system/` must also be the new ones from
this release.

I tested the rev2 build on my two consoles. The rev4 files are built from the same source, but nobody has run them yet.

## Update prepared on 12 Nov 2026

This one adds wireless link for Game Boy, Game Boy Color and Game Boy Advance, and a Cheats page.

- **Wireless link**: two Game Bubs play link games over the air, no cable and no pairing. Start the game with Play,
  press Home, open Wireless link and choose Start searching on both. GB/GBC uses the same protocol as the ModRetro
  Chromatic (Shiny Relay). GBA is between two Game Bubs.
- **Dual Core Link**: each console runs both players' games and only the buttons travel over the air, so the game runs
  at full speed. It needs two files on the SD card (`system/gameboy-dual.bit.hs`, `system/gba-dual.bit.hs`, they are in
  the release) because the two-core bitstreams do not fit inside the console. Tick Dual Core Link in Settings > Core,
  then the game asks Play or Dual Core Link. If your saves are different you can play without them; the files on the
  card are not touched. What you do in a Dual Core Link session is not saved.
- **Cheats**: a Cheats page in the game menu. Put `.cht` files next to the ROM or in `/cheats/<system>/` on the card.

I do not have a rev4. The rev4 files are built from the same source and should work, but nobody has run them yet, so
please tell me how it goes.

I also found an old bug in the Game Boy CPU: an interrupt that arrived exactly while another one was being
acknowledged was lost. With the wireless link this froze a game about once a minute. It is fixed in this update
(`fpga/src/main/scala/gameboy/cpu/Cpu.scala`).

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

Wireless link (new on 12 Nov 2026):
- Wireless link for GB, GBC and GBA over ESP-NOW (`firmware/handheld/components/cwl`, `firmware/handheld/src/link`).
  The radio starts only when you ask for it and it is released when you leave the game.
- Dual Core Link for GB, GBC and GBA: a second, hidden core in the bitstream runs the other player's game, and the two
  consoles keep the buttons in step frame by frame. Games with a clock or a sensor, and real cartridges, fall back to
  the ordinary Wireless link.
- In-game Cheats page, and cheat files from `/cheats` on the card.

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
The firmware adds one Off/On list before Reset (beta4 refuses the entry in `settings.json`, the address is a framework one). Off writes
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
