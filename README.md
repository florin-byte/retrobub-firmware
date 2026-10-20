# Retro Bub firmware for the Game Bub

This is my firmware for the [Game Bub](https://github.com/elipsitz/gamebub). It is Eli's firmware 1.1.0-beta2 with
the things I added and fixed. The repo also has the FPGA framework and the built-in cores (boot, Game Boy / Game Boy
Color and Game Boy Advance) with my fixes, so you can build all of it. It is not the official Game Bub firmware.

The ready-to-flash files are in the [releases](../../releases).

## Does it work?

- rev2: yes, this is what I use every day.
- rev4: it builds for rev4 and it should work, but it is not tested, because I don't own a rev4 Game Bub, only a
  rev2 one. If you try it, keep Eli's firmware file at hand to go back (see Install).

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

Look and feel:
- Themes: Game Bub, DMG, GBC, GBA and NES, each with a dark mode.
- My "Retro Bub" logo, also in the boot animation, in the colors of the theme.
- Long names of cores and files scroll, the core author stays visible, the lists scroll to the selected item.
- Status bar: a clock with your time zone (UTC offset, daylight saving for Europe or North America, 24 hour clock)
  and "CHG" while charging.
- Rom files are found also with capital extensions (`GAME.GBA`).
- On rev2 the tutorial at the first start shows a rev2 Game Bub (Eli's shows the rev4 one).

## Cores on the SD card

- Atari Lynx: https://github.com/florin-byte/gamebub-lynx
- Sega Game Gear: https://github.com/florin-byte/gamebub-gg

They work with Eli's firmware too, with this firmware you also get their save states (use the json files from their
`sdcard/savestate-firmware/` folder).

How the save states work: when you press Save State, the firmware sends the core `FILE_READ_START` for its State
file, the core writes the whole machine into its memory and answers with the size, the firmware reads it and writes
`<rom>.ss0` to `.ss3` next to the rom (slot 1 to 4). Load State is the same the other way (`FILE_WRITE_START`,
`FILE_WRITE_END`). In the core's json: a file with `"on_demand": true` in `files.json`, and in `settings.json` a list
with `"file_slots": <file id>` and two actions with `"file": <file id>` (one of them with `"load": true`). The Lynx
README explains it step by step.

## Install

The release has one file per board: `retrobub-firmware-rev2.uf2` and `retrobub-firmware-rev4.uf2`. Each has the
firmware and the built-in cores (boot, Game Boy, Game Boy Advance) for that board.

- rev4: like Eli's firmware updates, in DFU mode: the Game Bub shows up as a USB drive, you copy the `.uf2` file on it
  and it installs it and restarts. See Eli's guide for how to start DFU mode. The DFU checks the board in the file,
  so a rev2 file will not go on a rev4. To go back, do the same with Eli's `.uf2` (the releases of his GitHub
  repo, for example `gamebub-rev4_v1.0.2.uf2`).
- rev2: I flash it with `espflash` (download mode: hold Home, press Power, USB-C connected). The release has the
  firmware and the `system_data` image for that too.

## Building

Firmware (ESP-IDF 5.4.3, Rust with the `esp` toolchain from [espup](https://github.com/esp-rs/espup)), from
`firmware/handheld/`:

```bash
. ~/export-esp.sh
cargo build --release --features rev4        # or rev2
```

Build from a git clone, the build needs the commit hash.

The built-in cores, from the root of the repo (Java 17, Python 3 with `edalize==0.6.7`, Vivado; I use 2025.2):

```bash
python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.boot.HandheldBoot --name boot --target gamebub_rev4
python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.gameboy.HandheldGameboy --name gameboy --target gamebub_rev4
python3 scripts/rev2/make_vivado_bundle.py --core-class net.gamebub.core.gba.HandheldGba --name gba --target gamebub_rev4
```

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
