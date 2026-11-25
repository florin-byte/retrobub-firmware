# UI host harness

Renders the firmware's own `.slint` screens on the PC and writes one PNG per scene, so that a UI
change can be looked at and compared before it goes to a console.

It compiles `res/ui/main.slint` with the same Slint version (1.12.1) and software renderer as the
firmware, and includes the firmware's `src/ui/slint.rs` (platform glue, `Argb1555` pixel) and
`src/ui/outline.rs` (outline of the in-game overlay) unchanged. The in-game scenes go through the
outline pass exactly as `FpgaLineRenderer` does; transparent pixels keep alpha 0 in the PNG.

Each scene runs in its own child process and, after its picture is taken, gets a key sequence that
walks past both ends of the list on screen. A panic fails the scene. This is what catches Slint's
"Recursion detected" binding loops, which the compiler does not report.

## Running

From `firmware/handheld`, with the PC's own target (the example is Windows; no network needed once the
firmware has been built):

    cargo run --offline --manifest-path host_tests/ui/Cargo.toml --target x86_64-pc-windows-msvc

Options after `--`:

- `--compare host_tests/ui/reference` compares the hashes with `reference.txt` in that folder, prints
  a `DIFF` line per scene that changed and exits with 1 if any did.
- `--write-reference` rewrites `reference/reference.txt` from this run. Do it only after looking at
  the pictures.
- `--scene <name>` runs one scene in this process (what the parent does for each scene).

Output: `out/<scene>.png` (ignored by git), 240x160, one line `SCENE <name> OK 240x160 hash=...` per
scene and a final `RESULT` line. Exit code 0 only if no scene failed and none differs.

`Cargo.lock` is a copy of the firmware's lock file: it pins the crates the firmware build already
fetched, which is what lets this crate resolve offline. Copy it again after the firmware's lock file
changes.

## Adding a scene

Add its name to `SCENES` and a branch to `setup()` in `src/main.rs`: set `Backend` properties, call
`invoke_set_screen`, then send keys with `h.keys(&[...])`. Two things to know:

- Conditional elements are created when the screen is first laid out and take the focus in their
  `init`. `keys()` renders once before each key for that reason; when a screen resets properties in
  `init` (the Cores screen does), render once with `h.render(false)` before setting them.
- Return `true` from `setup()` for scenes shown over a running game, so they are rendered through the
  outline pass.

## What it does not cover

- The LCD driver, the FPGA overlay (which keeps only 2 bits per pixel while a core runs) and the
  transfer to it.
- Memory use and stack depth on the ESP32, and timing.
- The firmware's Rust UI state (`src/ui/state`): values here are test data set by the harness, and
  callbacks are not connected. Key handling done in Rust is not exercised.
- The firmware's theme table: `*_dark` scenes use colors set by the harness, not a real theme.
- Animations over time (scrolling text is captured at its start position).
