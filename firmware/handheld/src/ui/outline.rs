//! GB-181: the 1 px black outline of the in-game overlay, computed after rendering.
//!
//! While a core runs, the FPGA keeps the overlay in 2 bits per pixel: transparent, black, gray or
//! white (`HandheldTop.scala`: the alpha bit and the two top bits of red). The in-game menu is drawn
//! over the game with light text, icons and frames that need a black outline to stay readable.
//! Drawing that outline in Slint (a copy of every text in an outline font, eight shifted copies of
//! every icon, three rectangles per frame) doubled the memory of the in-game Settings panel and ran
//! the console out of memory (`rust_oom` while growing the renderer's scene list).
//!
//! Instead, the line renderer keeps the class of every pixel of the screen (2 bits, 9.6 KB at
//! 240x160) and, after each frame, sends the rendered area grown by one pixel with every transparent
//! pixel that touches (8 neighbours) a gray or white one turned black. On the overlay of the boot
//! bitstream (full color, the main UI) it is off.

use std::sync::atomic::{AtomicBool, Ordering};

use ::slint::platform::software_renderer::TargetPixel;

use super::slint::Argb1555;

static ENABLED: AtomicBool = AtomicBool::new(false);

/// On while a core's bitstream (2-bit overlay) is loaded. A full redraw must follow a change, so that
/// the kept classes match the screen again.
pub fn set_enabled(on: bool) {
    ENABLED.store(on, Ordering::Relaxed);
}

pub fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

const TRANSPARENT: u8 = 0;
const BLACK: u8 = 1;
const GRAY: u8 = 2;
const WHITE: u8 = 3;

/// The overlay color the FPGA keeps for a pixel (same rule as `HandheldTop.scala`).
fn class(p: Argb1555) -> u8 {
    let v = p.as_u16();
    if v & 0x8000 == 0 {
        TRANSPARENT
    } else {
        [BLACK, GRAY, GRAY, WHITE][((v >> 13) & 3) as usize]
    }
}

fn pixel(class: u8) -> Argb1555 {
    match class {
        BLACK => Argb1555::from_rgb(0, 0, 0),
        GRAY => Argb1555::from_rgb(0x80, 0x80, 0x80),
        WHITE => Argb1555::from_rgb(0xFF, 0xFF, 0xFF),
        _ => Argb1555::background(),
    }
}

pub struct Outline {
    width: usize,
    height: usize,
    /// 2 bits per pixel, row-major.
    classes: Vec<u8>,
    /// Rendered area of the current frame: x start, x end, y start, y end (ends exclusive).
    area: Option<(usize, usize, usize, usize)>,
}

impl Outline {
    pub fn new(width: usize, height: usize) -> Self {
        Outline { width, height, classes: vec![0; (width * height + 3) / 4], area: None }
    }

    fn get(&self, x: usize, y: usize) -> u8 {
        let i = y * self.width + x;
        (self.classes[i >> 2] >> ((i & 3) * 2)) & 3
    }

    fn set(&mut self, x: usize, y: usize, class: u8) {
        let i = y * self.width + x;
        let shift = (i & 3) * 2;
        self.classes[i >> 2] = (self.classes[i >> 2] & !(3 << shift)) | (class << shift);
    }

    /// Keep the classes of a rendered line segment.
    pub fn store(&mut self, line: usize, start: usize, pixels: &[Argb1555]) {
        for (i, p) in pixels.iter().enumerate() {
            self.set(start + i, line, class(*p));
        }
        let end = start + pixels.len();
        let a = self.area.get_or_insert((start, end, line, line + 1));
        a.0 = a.0.min(start);
        a.1 = a.1.max(end);
        a.2 = a.2.min(line);
        a.3 = a.3.max(line + 1);
    }

    /// The area to send for this frame: the rendered area grown by one pixel (its outline changes too).
    pub fn take_area(&mut self) -> Option<(usize, usize, usize, usize)> {
        let (x0, x1, y0, y1) = self.area.take()?;
        Some((x0.saturating_sub(1), (x1 + 1).min(self.width), y0.saturating_sub(1), (y1 + 1).min(self.height)))
    }

    /// The pixel to send: transparent next to gray or white becomes black.
    pub fn outlined(&self, x: usize, y: usize) -> Argb1555 {
        let c = self.get(x, y);
        if c != TRANSPARENT {
            return pixel(c);
        }
        let (xs, xe) = (x.saturating_sub(1), (x + 2).min(self.width));
        let (ys, ye) = (y.saturating_sub(1), (y + 2).min(self.height));
        for ny in ys..ye {
            for nx in xs..xe {
                if self.get(nx, ny) >= GRAY {
                    return pixel(BLACK);
                }
            }
        }
        pixel(TRANSPARENT)
    }
}
