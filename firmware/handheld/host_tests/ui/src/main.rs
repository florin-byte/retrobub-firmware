//! Host harness for the firmware UI (GB-261): renders the firmware's own `.slint` screens on the PC
//! with the same Slint version, software renderer, pixel type and outline pass as the console, writes
//! one PNG per scene and prints a hash per scene. See README.md.
//!
//! Each scene runs in its own child process, so a panic (for example Slint's "Recursion detected"
//! on a binding loop, which only shows at run time) fails that scene and nothing else.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::rc::Rc;

use ::slint::platform::software_renderer::{LineBufferProvider, RepaintBufferType};
use ::slint::platform::{Key, WindowEvent};
use ::slint::{ComponentHandle, ModelRc, PhysicalSize, SharedString, VecModel, WindowSize};

// The firmware's own platform glue, pixel type and outline pass, compiled unchanged.
mod ui {
    #[path = "../../../../src/ui/slint.rs"]
    pub mod slint;
    #[path = "../../../../src/ui/outline.rs"]
    pub mod outline;
}

use ui::outline::Outline;
use ui::slint::{
    Argb1555, Backend, CheatCell, CoreListEntry, CoreSetting, CoreSettingType, CoreSubscreen,
    FileListEntry, HandheldPlatform, MainWindow, MinimalSoftwareWindow, ScreenId, SettingEntry,
    SettingType, SettingValue, Theme,
};

/// UI size of the rev2 console (`DISPLAY_WIDTH` / `DISPLAY_HEIGHT` in `src/ui/mod.rs`: 480 / 2 x 320 / 2).
const W: usize = 240;
const H: usize = 160;

/// Scene names, in the order they are run.
const SCENES: &[&str] = &[
    "main_menu",
    "main_menu_power",
    "main_menu_dark",
    "cores_empty",
    "cores_3",
    "cores_40",
    "cores_40_dark",
    "files_empty",
    "files_40",
    "settings",
    "settings_choice",
    "settings_dark",
    "game_menu",
    "game_settings",
    "game_cheats",
    "game_power",
    "setup",
    "tools",
    "about",
    "error",
];

/// Keeps every rendered line; with `outline`, lines go through the firmware's outline pass.
struct Frame {
    pixels: Vec<Argb1555>,
    line: Vec<Argb1555>,
    outline: Option<Outline>,
}

impl LineBufferProvider for &mut Frame {
    type TargetPixel = Argb1555;

    fn process_line(
        &mut self,
        line: usize,
        range: core::ops::Range<usize>,
        render_fn: impl FnOnce(&mut [Self::TargetPixel]),
    ) {
        let start = range.start;
        let buffer = &mut self.line[range];
        render_fn(buffer);
        if let Some(outline) = self.outline.as_mut() {
            outline.store(line, start, buffer);
        } else {
            self.pixels[line * W + start..line * W + start + buffer.len()].copy_from_slice(buffer);
        }
    }
}

impl Frame {
    fn new(outlined: bool) -> Self {
        Frame {
            pixels: vec![Argb1555::default(); W * H],
            line: vec![Argb1555::default(); W],
            outline: outlined.then(|| Outline::new(W, H)),
        }
    }

    /// Same as `FpgaLineRenderer::finish` in the firmware.
    fn finish(&mut self) {
        let Some(outline) = self.outline.as_mut() else { return };
        let Some((x0, x1, y0, y1)) = outline.take_area() else { return };
        for y in y0..y1 {
            for x in x0..x1 {
                self.pixels[y * W + x] = outline.outlined(x, y);
            }
        }
    }

    fn hash(&self) -> u64 {
        // FNV-1a over the 16-bit pixels
        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        for p in &self.pixels {
            for b in p.as_u16().to_le_bytes() {
                h ^= b as u64;
                h = h.wrapping_mul(0x0000_0100_0000_01b3);
            }
        }
        h
    }

    /// PNG (RGBA, stored deflate blocks: no compression library needed); alpha 0 where the overlay
    /// is transparent.
    fn write_png(&self, path: &Path) -> std::io::Result<()> {
        let mut raw = Vec::with_capacity(H * (1 + W * 4));
        for y in 0..H {
            raw.push(0);
            for p in &self.pixels[y * W..(y + 1) * W] {
                let a = if p.as_u16() & 0x8000 != 0 { 255 } else { 0 };
                raw.extend_from_slice(&[p.red(), p.green(), p.blue(), a]);
            }
        }
        let mut z = vec![0x78, 0x01];
        let mut chunks = raw.chunks(65535).peekable();
        while let Some(c) = chunks.next() {
            z.push(chunks.peek().is_none() as u8);
            z.extend_from_slice(&(c.len() as u16).to_le_bytes());
            z.extend_from_slice(&(!(c.len() as u16)).to_le_bytes());
            z.extend_from_slice(c);
        }
        let (mut s1, mut s2) = (1u32, 0u32);
        for b in &raw {
            s1 = (s1 + *b as u32) % 65521;
            s2 = (s2 + s1) % 65521;
        }
        z.extend_from_slice(&((s2 << 16) | s1).to_be_bytes());

        fn crc(data: &[u8]) -> u32 {
            let mut c = 0xffff_ffffu32;
            for b in data {
                c ^= *b as u32;
                for _ in 0..8 {
                    c = if c & 1 != 0 { 0xedb8_8320 ^ (c >> 1) } else { c >> 1 };
                }
            }
            !c
        }
        fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
            out.extend_from_slice(&(data.len() as u32).to_be_bytes());
            let start = out.len();
            out.extend_from_slice(kind);
            out.extend_from_slice(data);
            let c = crc(&out[start..]);
            out.extend_from_slice(&c.to_be_bytes());
        }
        let mut out = vec![0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];
        let mut ihdr = Vec::new();
        ihdr.extend_from_slice(&(W as u32).to_be_bytes());
        ihdr.extend_from_slice(&(H as u32).to_be_bytes());
        ihdr.extend_from_slice(&[8, 6, 0, 0, 0]);
        chunk(&mut out, b"IHDR", &ihdr);
        chunk(&mut out, b"IDAT", &z);
        chunk(&mut out, b"IEND", &[]);
        fs::File::create(path)?.write_all(&out)
    }
}

struct Harness {
    window: Rc<MinimalSoftwareWindow>,
    root: MainWindow,
}

impl Harness {
    fn new() -> Self {
        let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
        ::slint::platform::set_platform(Box::new(HandheldPlatform { window: window.clone() })).unwrap();
        window.set_size(WindowSize::Physical(PhysicalSize::new(W as u32, H as u32)));
        let root = MainWindow::new().unwrap();
        root.show().unwrap();
        // Key events only reach a focused item of an active window.
        window.dispatch_event(WindowEvent::WindowActiveChanged(true));
        let backend = root.global::<Backend>();
        backend.set_device_rev2(true);
        backend.set_firmware_version("host".into());
        backend.set_hardware_version("rev2".into());
        backend.set_hardware_serial("0000".into());
        backend.set_clock_text("12:34".into());
        backend.set_battery_level(80);
        Harness { window, root }
    }

    fn backend(&self) -> Backend<'_> {
        self.root.global::<Backend>()
    }

    fn pump(&self) {
        ::slint::platform::update_timers_and_animations();
    }

    fn key(&self, text: impl Into<SharedString>) {
        let text: SharedString = text.into();
        self.window.dispatch_event(WindowEvent::KeyPressed { text: text.clone() });
        self.pump();
        self.window.dispatch_event(WindowEvent::KeyReleased { text });
        self.pump();
    }

    fn keys(&self, list: &[&str]) {
        for k in list {
            // Conditional elements are created when the screen is laid out, and take the focus in
            // their `init`: render first (as the console's loop does between events), or the key is lost.
            let _ = self.render(false);
            match *k {
                "up" => self.key(Key::UpArrow),
                "down" => self.key(Key::DownArrow),
                "left" => self.key(Key::LeftArrow),
                "right" => self.key(Key::RightArrow),
                other => self.key(other),
            }
        }
    }

    /// Full redraw into a new frame. `outlined` = the in-game overlay path of the firmware.
    fn render(&self, outlined: bool) -> Frame {
        self.pump();
        let mut frame = Frame::new(outlined);
        self.window.renderer.set_repaint_buffer_type(RepaintBufferType::NewBuffer);
        self.window.request_redraw();
        self.window.draw_if_needed(|renderer| {
            renderer.render_by_line(&mut frame);
        });
        frame.finish();
        frame
    }

    /// Walk over both ends of whatever list is on screen, page, and leave again: any binding loop
    /// that depends on selection or scrolling panics here.
    fn stress(&self, outlined: bool) {
        let mut seq: Vec<&str> = vec!["up", "up"];
        seq.extend(std::iter::repeat("down").take(45));
        seq.extend(["l", "r", "r", "left", "right"]);
        seq.extend(std::iter::repeat("up").take(45));
        for (i, k) in seq.iter().enumerate() {
            self.keys(&[k]);
            if i % 8 == 0 {
                let _ = self.render(outlined);
            }
        }
        let _ = self.render(outlined);
    }

    fn dark(&self) {
        let t = self.root.global::<Theme>();
        let c = |v: u32| ::slint::Color::from_argb_encoded(0xff00_0000 | v);
        t.set_bg(c(0x101014));
        t.set_surface(c(0x202028));
        t.set_surface_alt(c(0x181820));
        t.set_text(c(0xf0f0f0));
        t.set_text_dim(c(0x909090));
        t.set_text_secondary(c(0xb0b0b0));
        t.set_border(c(0xf0f0f0));
        t.set_border_dim(c(0x808080));
        t.set_highlight(c(0x404858));
        t.set_status_bg(c(0x202028));
        t.set_icon(c(0xe0e0e0));
        self.root.invoke_set_color_scheme(true, true);
    }
}

fn cores(n: usize) -> ModelRc<CoreListEntry> {
    let v: Vec<CoreListEntry> = (0..n)
        .map(|i| CoreListEntry {
            core_id: format!("Test.Core{i}").into(),
            core_name: if i % 3 == 1 {
                format!("Core {i} with a very long name that does not fit the row").into()
            } else {
                format!("Core {i}").into()
            },
            core_author: format!("Author {i}").into(),
        })
        .collect();
    ModelRc::new(VecModel::from(v))
}

fn files(n: usize) -> ModelRc<FileListEntry> {
    let v: Vec<FileListEntry> = (0..n)
        .map(|i| FileListEntry {
            name: if i % 4 == 2 {
                format!("A very long file name number {i} (Europe) (En,Fr,De,Es,It) (Rev 1).bin").into()
            } else {
                format!("Game {i:02}.bin").into()
            },
            ..Default::default()
        })
        .collect();
    ModelRc::new(VecModel::from(v))
}

fn strings(list: &[&str]) -> ModelRc<SharedString> {
    ModelRc::new(VecModel::from(list.iter().map(|s| SharedString::from(*s)).collect::<Vec<_>>()))
}

fn system_settings() -> ModelRc<SettingEntry> {
    let mut v = vec![
        SettingEntry {
            name: "Theme".into(),
            r#type: SettingType::List,
            choices: strings(&["Game Bub", "Retro Bub", "Dark", "A theme with a long name"]),
            value: SettingValue { int_value: 1, ..Default::default() },
        },
        SettingEntry {
            name: "24-hour clock".into(),
            r#type: SettingType::Checkbox,
            value: SettingValue { bool_value: true, ..Default::default() },
            ..Default::default()
        },
        SettingEntry { name: "Date and time".into(), r#type: SettingType::Datetime, ..Default::default() },
        SettingEntry { name: "About".into(), r#type: SettingType::Subpage, ..Default::default() },
    ];
    for i in 0..12 {
        v.push(SettingEntry {
            name: format!("Option {i} with a label long enough to need scrolling").into(),
            r#type: SettingType::Checkbox,
            value: SettingValue { bool_value: i % 2 == 0, ..Default::default() },
            ..Default::default()
        });
    }
    ModelRc::new(VecModel::from(v))
}

fn core_settings() -> ModelRc<CoreSetting> {
    let mut v = vec![
        CoreSetting { id: 10, label: "Next disc".into(), r#type: CoreSettingType::Action, ..Default::default() },
        CoreSetting {
            id: 0,
            label: "Region".into(),
            r#type: CoreSettingType::List,
            value: 2,
            choices: strings(&["Japan", "Asia NTSC", "North America", "Europe", "Auto"]),
        },
        CoreSetting { id: 2, label: "Fast".into(), r#type: CoreSettingType::Checkbox, value: 1, ..Default::default() },
    ];
    for i in 0..9 {
        v.push(CoreSetting {
            id: 20 + i,
            label: format!("Setting {i} with a long label over the game").into(),
            r#type: CoreSettingType::Checkbox,
            value: i % 2,
            ..Default::default()
        });
    }
    ModelRc::new(VecModel::from(v))
}

fn cheats() -> ModelRc<CheatCell> {
    let v: Vec<CheatCell> = (0..24)
        .map(|i| CheatCell {
            id: i,
            text: format!("Cheat {i} long description").into(),
            on: i % 5 == 0,
            radio: i % 7 == 3,
        })
        .collect();
    ModelRc::new(VecModel::from(v))
}

/// Sets the scene up. Returns true when the firmware would render it through the outline pass
/// (in-game overlay of a core bitstream).
fn setup(h: &Harness, scene: &str) -> bool {
    let b = h.backend();
    let mut overlay = false;
    match scene {
        "main_menu" | "main_menu_power" | "main_menu_dark" => {
            h.root.invoke_set_screen(ScreenId::MainMenu);
            h.root.invoke_end_boot_transparency();
            if scene == "main_menu_dark" {
                h.dark();
            }
            h.pump();
            if scene == "main_menu_power" {
                h.keys(&["Power"]);
            }
        }
        "cores_empty" | "cores_3" | "cores_40" | "cores_40_dark" => {
            b.set_core_list(cores(match scene {
                "cores_empty" => 0,
                "cores_3" => 3,
                _ => 40,
            }));
            h.root.invoke_set_screen(ScreenId::Cores);
            let _ = h.render(false);
            b.set_core_subscreen(CoreSubscreen::List);
            if scene == "cores_40_dark" {
                h.dark();
            }
            h.pump();
            if scene != "cores_empty" {
                h.keys(&["down", "down"]);
            }
        }
        "files_empty" | "files_40" => {
            b.set_core_list(cores(3));
            h.root.invoke_set_screen(ScreenId::Cores);
            // The Cores screen clears the file list and its sub-screen in `init`, which runs when it
            // is first laid out: lay it out, then fill in.
            let _ = h.render(false);
            b.set_core_file_select_label("ROM".into());
            b.set_core_file_select_path("/sdcard/roms/saturn/".into());
            b.set_core_file_select_list(files(if scene == "files_empty" { 0 } else { 40 }));
            b.set_core_subscreen(CoreSubscreen::FileSelect);
            h.pump();
            if scene == "files_40" {
                h.keys(&["down", "down", "down"]);
            }
        }
        "settings" | "settings_choice" | "settings_dark" => {
            b.set_settings(system_settings());
            h.root.invoke_set_screen(ScreenId::Settings);
            if scene == "settings_dark" {
                h.dark();
            }
            h.pump();
            if scene == "settings_choice" {
                h.keys(&["a"]);
            } else {
                h.keys(&["down", "down"]);
            }
        }
        "game_menu" | "game_settings" | "game_cheats" | "game_power" => {
            overlay = true;
            b.set_core_settings(core_settings());
            b.set_cheats_available(true);
            b.set_cheat_cells(cheats());
            b.set_cheat_details(strings(&["Detail line one", "Detail line two"]));
            b.set_cheats_on(5);
            h.root.invoke_set_screen(ScreenId::Game);
            h.pump();
            match scene {
                "game_power" => h.keys(&["Power"]),
                "game_menu" => h.keys(&["Home"]),
                // menu rows: Return, Settings, Cheats, Reset, Exit Core
                "game_settings" => h.keys(&["Home", "down", "a", "down", "down"]),
                _ => h.keys(&["Home", "down", "down", "a", "down"]),
            }
        }
        "setup" => h.root.invoke_set_screen(ScreenId::Setup),
        "tools" => h.root.invoke_set_screen(ScreenId::Tools),
        "about" => h.root.invoke_set_screen(ScreenId::About),
        "error" => {
            b.set_error_text("Something failed with a message long enough to wrap over more than one line".into());
            h.root.invoke_set_screen(ScreenId::Error);
        }
        other => panic!("unknown scene {other}"),
    }
    h.pump();
    overlay
}

fn run_scene(scene: &str, out: &Path) {
    let h = Harness::new();
    let overlay = setup(&h, scene);
    let frame = h.render(overlay);
    frame.write_png(&out.join(format!("{scene}.png"))).unwrap();
    let hash = frame.hash();
    // After the picture: key stress on the same screen. A panic here fails the scene.
    h.stress(overlay);
    println!("SCENE {scene} OK {W}x{H} hash={hash:016x}");
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let out = manifest.join("out");
    fs::create_dir_all(&out).unwrap();

    if let Some(i) = args.iter().position(|a| a == "--scene") {
        run_scene(&args[i + 1], &out);
        return;
    }

    let compare = args.iter().position(|a| a == "--compare").map(|i| PathBuf::from(&args[i + 1]));
    let write_ref = args.iter().any(|a| a == "--write-reference");
    let exe = std::env::current_exe().unwrap();
    let mut lines = Vec::new();
    let mut failed = Vec::new();
    for scene in SCENES {
        let o = Command::new(&exe).args(["--scene", scene]).output().unwrap();
        let text = String::from_utf8_lossy(&o.stdout);
        match text.lines().find(|l| l.starts_with("SCENE ")) {
            Some(l) if o.status.success() => {
                println!("{l}");
                lines.push(l.to_string());
            }
            _ => {
                let err = String::from_utf8_lossy(&o.stderr);
                let why = err.lines().find(|l| l.contains("panicked") || l.contains("Recursion")).unwrap_or("no output");
                let detail = err.lines().skip_while(|l| !l.contains("panicked")).nth(1).unwrap_or("");
                println!("SCENE {scene} FAIL {why} {detail}");
                failed.push(scene.to_string());
            }
        }
    }

    let mut differ = Vec::new();
    if let Some(dir) = compare {
        let dir = if dir.is_absolute() { dir } else { std::env::current_dir().unwrap().join(dir) };
        let reference = fs::read_to_string(dir.join("reference.txt")).expect("reference.txt");
        for l in &lines {
            let name = l.split_whitespace().nth(1).unwrap();
            match reference.lines().find(|r| r.split_whitespace().nth(1) == Some(name)) {
                Some(r) if r.trim() == l.trim() => {}
                Some(_) => differ.push(format!("{name} (changed)")),
                None => differ.push(format!("{name} (not in the reference)")),
            }
        }
        for d in &differ {
            println!("DIFF {d}");
        }
    }
    if write_ref {
        let dir = manifest.join("reference");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("reference.txt"), lines.join("\n") + "\n").unwrap();
        println!("reference written: {} scenes", lines.len());
    }
    println!("RESULT scenes={} failed={} differ={}", lines.len(), failed.len(), differ.len());
    if !failed.is_empty() || !differ.is_empty() {
        std::process::exit(1);
    }
}
