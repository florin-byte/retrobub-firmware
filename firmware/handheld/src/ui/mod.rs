pub mod buttons;
pub mod outline;
mod screenshot;
pub mod slint;
mod state;

use ::slint::platform::WindowAdapter;
use ::slint::{ComponentHandle, WindowSize};
use buttons::ButtonEvent;
pub use buttons::{Button, ButtonMap};
use static_cell::{ConstStaticCell, StaticCell};
use std::path::PathBuf;
use std::sync::{mpsc, OnceLock};
use std::time::Duration;
use std::{
    cell::{Cell, RefCell},
    rc::Rc,
    sync::mpsc::Receiver,
    time::Instant,
};

use ::slint::{
    platform::software_renderer::{LineBufferProvider, RepaintBufferType, TargetPixel},
    PhysicalSize, Timer, TimerMode,
};

use crate::device::{Device, DisplayMode};
use crate::input::{self, InputManager};
use crate::kvs;
pub use crate::ui::state::{CheatsPage, CheatsPageCell, CheatsPageRow, CoreSettingUiItem};
use crate::worker;
pub use state::notifications::Notification;

use self::{slint::Argb1555, slint::MinimalSoftwareWindow, state::UiState};

const SETUP_IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const DIM_BRIGHTNESS: f32 = 0.10;

fn active_idle_timeout() -> Option<Duration> {
    if kvs::keys::SETUP_STAGE.get().unwrap_or_default() == 0 {
        return Some(SETUP_IDLE_TIMEOUT);
    }
    match kvs::keys::DIM_TIMEOUT.get().unwrap_or(0) {
        0 => Some(Duration::from_secs(60)),
        1 => Some(Duration::from_secs(180)),
        2 => Some(Duration::from_secs(300)),
        _ => None,
    }
}

cfg_if::cfg_if! {
    if #[cfg(any(feature = "rev1", feature = "rev2"))] {
        const DISPLAY_WIDTH: usize = 480 / 2;
        const DISPLAY_HEIGHT: usize = 320 / 2;
    } else {
        const DISPLAY_WIDTH: usize = 720 / 2;
        const DISPLAY_HEIGHT: usize = 480 / 2;
    }
}

pub enum Message {
    /// The state of the buttons have changed.
    Button(ButtonMap),
    /// Battery status has changed.
    BatteryStatus { level: f32, is_charging: bool },
    /// Redraw the entire screen (e.g. after a display change)
    Redraw,
    /// Go to the "Game" screen
    EnterGame { wireless_link: bool },
    /// Enter the error screen, and show the given error
    FatalError(String),
    /// Internal input state changed
    InputState(input::InputState),
    /// Show a notification
    Notification(Notification),
    /// Docked
    DockBegin { serial: String, firmware: String },
    /// Dock ended
    DockEnd,
    /// Take a UI screenshot
    Screenshot,
    /// Core info listing
    CoreList(Vec<crate::core::CoreListEntry>),
    /// Open core file selection screen
    CoreFileSelectBegin { label: String, path: PathBuf },
    /// Update core file selection list
    CoreFileSelectList(Vec<(String, bool)>),
    /// Core file select error
    CoreFileSelectError(String),
    /// Core load error
    CoreLoadError(String),
    /// Core loading progress
    CoreLoadProgress(f32),
    /// Core settings
    CoreSettingsList(Vec<CoreSettingUiItem>),
    /// Whether the game has cheats (the in-game menu then has Cheats)
    CheatsAvailable(bool),
    /// The Cheats page
    CheatsPage(CheatsPage),
}

/// Send a message to the UI thread.
pub fn send(message: Message) {
    match SENDER.get() {
        Some(sender) => sender.send(message).unwrap(),
        None => log::error!("Dropping UI message"),
    }
}

static SENDER: OnceLock<mpsc::Sender<Message>> = OnceLock::new();

/// Line renderer that renders to the FPGA
struct FpgaLineRenderer<'a, 'b, 'c> {
    device: &'a mut Device<'b>,
    line_buffer: &'c mut [Argb1555],
    /// In-game overlay (GB-181): lines are kept and sent with the outline after the frame.
    outline: Option<&'c mut outline::Outline>,
}

impl FpgaLineRenderer<'_, '_, '_> {
    /// Send the area of the frame with the outline (nothing to do without it).
    fn finish(&mut self) {
        let Some(outline) = self.outline.as_deref_mut() else { return };
        let Some((x0, x1, y0, y1)) = outline.take_area() else { return };
        for y in y0..y1 {
            let buffer = &mut self.line_buffer[..x1 - x0];
            for (i, p) in buffer.iter_mut().enumerate() {
                *p = outline.outlined(x0 + i, y);
            }
            let slice = unsafe { std::slice::from_raw_parts(buffer.as_ptr() as *const u8, buffer.len() * 2) };
            let _ = self.device.fpga.write_overlay(((DISPLAY_WIDTH * y + x0) * 2) as u32, slice);
        }
    }
}

impl<'a, 'b, 'c> LineBufferProvider for &mut FpgaLineRenderer<'a, 'b, 'c> {
    type TargetPixel = Argb1555;

    fn process_line(
        &mut self,
        line: usize,
        range: core::ops::Range<usize>,
        render_fn: impl FnOnce(&mut [Self::TargetPixel]),
    ) {
        let start = ((DISPLAY_WIDTH * line) + range.start) * 2;
        let range_start = range.start;
        let buffer = &mut self.line_buffer[range];
        render_fn(buffer);

        if let Some(outline) = self.outline.as_deref_mut() {
            outline.store(line, range_start, buffer);
            return;
        }

        let slice = {
            let len = buffer.len() * 2;
            unsafe { std::slice::from_raw_parts(buffer.as_ptr() as *const u8, len) }
        };
        let _ = self.device.fpga.write_overlay(start as u32, slice);
    }
}

static UI_CELL: StaticCell<UI> = StaticCell::new();
static FRAMEBUFFER: ConstStaticCell<[Argb1555; DISPLAY_WIDTH]> =
    ConstStaticCell::new([Argb1555::TRANSPARENT; DISPLAY_WIDTH]);
static LCD_LINE_BUFFER: ConstStaticCell<[u8; DISPLAY_WIDTH * 3 * 2]> =
    ConstStaticCell::new([0; DISPLAY_WIDTH * 3 * 2]);

#[allow(unused)]
pub struct UI {
    framebuffer: &'static mut [Argb1555],
    lcd_line_buffer: &'static mut [u8],
    /// Classes of the in-game overlay, for its outline (GB-181).
    outline: outline::Outline,
    window: Rc<MinimalSoftwareWindow>,
    message_queue: Receiver<Message>,
    root: slint::MainWindow,
    state: &'static RefCell<UiState>,
    button_event_detector: buttons::ButtonEventDetector,
    idle_timer: Timer,
    idle_timeout: Option<Duration>,
    is_dimmed: Rc<Cell<bool>>,
    home_pressed: bool,
    home_consumed: bool,
    #[cfg(feature = "link-diag")]
    cheats_render_probe: bool,
}

impl UI {
    pub fn init(device: &mut Device) -> &'static mut Self {
        // Same row as the logo of the main menu (status bar 16 + padding 4 + centering 22, see
        // main_menu.slint), so that the logo does not move when the UI takes over.
        device
            .fpga
            .write_u32(crate::bitstream::boot::REG_LOGO_Y, 42)
            .unwrap();
        // Draw the logo animation in the colors of the UI theme.
        let logo = state::boot_logo_colors();
        for (reg, color) in [
            (crate::bitstream::boot::REG_LOGO_BG, logo.bg),
            (crate::bitstream::boot::REG_LOGO_FACE, logo.face),
            (crate::bitstream::boot::REG_LOGO_SHADOW, logo.shadow),
            (crate::bitstream::boot::REG_LOGO_GLOW, logo.glow),
        ] {
            device.fpga.write_u32(reg, color).unwrap();
        }
        device.refresh_display_mode().unwrap();
        // Start the boot animation after a delay.
        Timer::single_shot(Duration::from_millis(500), || {
            let animation = 1 | (6 << 2); // 6: 0.66 seconds
            Device::lock()
                .fpga
                .write_u32(crate::bitstream::boot::REG_LOGO_ANIM, animation)
                .unwrap(); // Start animation (no loop)
        });

        let idle_timer = Timer::default();

        let (sender, receiver) = mpsc::channel::<Message>();
        SENDER.set(sender).expect("UI already initialized");

        let framebuffer = FRAMEBUFFER.take();
        framebuffer.fill(Argb1555::from_rgb(0, 0, 0));
        let lcd_line_buffer = LCD_LINE_BUFFER.take();

        let window = MinimalSoftwareWindow::new(RepaintBufferType::ReusedBuffer);
        ::slint::platform::set_platform(Box::new(slint::HandheldPlatform {
            window: window.clone(),
        }))
        .unwrap();
        window.set_size(WindowSize::Physical(PhysicalSize::new(
            DISPLAY_WIDTH as u32,
            DISPLAY_HEIGHT as u32,
        )));

        let root = slint::MainWindow::new().unwrap();
        let state = UiState::new(&root, device);

        let ui = UI_CELL.init_with(|| UI {
            framebuffer,
            lcd_line_buffer,
            outline: outline::Outline::new(DISPLAY_WIDTH, DISPLAY_HEIGHT),
            window,
            message_queue: receiver,
            state,
            root,
            button_event_detector: buttons::ButtonEventDetector::new(),
            idle_timer,
            idle_timeout: None,
            is_dimmed: Rc::new(Cell::new(false)),
            home_pressed: false,
            home_consumed: false,
            #[cfg(feature = "link-diag")]
            cheats_render_probe: false,
        });
        ui.reset_idle_timer();
        ui
    }

    fn restore_brightness(&mut self) {
        if self.is_dimmed.replace(false) {
            Device::lock().set_brightness(kvs::keys::BRIGHTNESS.get().unwrap());
        }

    }

    fn reset_idle_timer(&mut self) {
        let desired = active_idle_timeout();
        if desired == self.idle_timeout {
            if self.idle_timeout.is_some() {
                self.idle_timer.restart();
            }
            return;
        }

        self.idle_timeout = desired;
        match desired {
            Some(timeout) => {
                let is_dimmed = self.is_dimmed.clone();
                self.idle_timer
                    .start(TimerMode::SingleShot, timeout, move || {
                        if kvs::keys::SETUP_STAGE.get().unwrap_or_default() == 0 {
                            worker::send(worker::Message::IdleTimerExpired);
                            return;
                        }
                        let mut device = Device::lock();
                        if device.get_display_mode() == DisplayMode::Internal {
                            let brightness = kvs::keys::BRIGHTNESS.get().unwrap();
                            let dimmed = brightness.min(DIM_BRIGHTNESS);
                            if dimmed < brightness {
                                device.set_brightness(dimmed);
                                is_dimmed.set(true);
                            }
                        }
                    });
            }
            None => {
                self.idle_timer.stop();
            }
        }
    }

    fn handle_button_event(&mut self, event: ButtonEvent) {
        match event {
            ButtonEvent::Pressed(Button::Home, repeat) => {
                if !repeat {
                    self.home_pressed = true;
                    self.home_consumed = false;
                }
                return;
            }
            ButtonEvent::Released(Button::Home) => {
                self.home_pressed = false;
                if std::mem::take(&mut self.home_consumed) {
                    return;
                }
            }
            ButtonEvent::Pressed(button @ (Button::VolUp | Button::VolDown), _) => {
                let setting = if self.home_pressed {
                    self.home_consumed = true;
                    state::LevelSetting::Brightness
                } else {
                    state::LevelSetting::Volume
                };
                self.state
                    .borrow()
                    .adjust_level(setting, button == Button::VolUp);
                return;
            }
            ButtonEvent::Released(Button::VolUp | Button::VolDown) => {
                return;
            }
            ButtonEvent::Pressed(Button::Power, _) => {
                return;
            }
            ButtonEvent::Released(Button::Power) => {
                // Close any auto-closing popup synchronously before toggling PowerMenu so
                // focus stays consistent.
                self.window
                    .dispatch_event(::slint::platform::WindowEvent::KeyPressed {
                        text: ::slint::platform::Key::Escape.into(),
                    });
                self.root.invoke_toggle_power_menu();
                return;
            }
            _ => {}
        }
        self.window.dispatch_event(event.into());
    }

    pub fn run(&mut self) -> ! {
        // Set this thread (UI) to higher priority than background threads.
        unsafe { esp_idf_svc::sys::vTaskPrioritySet(std::ptr::null_mut(), 10) };

        let mut pending_message = None;
        loop {
            // Process messages.
            while let Some(message) = pending_message {
                self.dispatch_message(message);
                pending_message = self.message_queue.try_recv().ok();
            }
            for button_event in self.button_event_detector.update(None) {
                self.restore_brightness();
                self.handle_button_event(button_event);
                self.reset_idle_timer();
            }

            ::slint::platform::update_timers_and_animations();

            // Render UI if needed.
            self.window.draw_if_needed(|renderer| {
                #[cfg(feature = "link-diag")]
                let probe_cheats = std::mem::take(&mut self.cheats_render_probe);
                #[cfg(feature = "link-diag")]
                if probe_cheats {
                    crate::link::log_memory_now("cheats-before-render");
                }

                let mut device = Device::lock();

                let render_start = Instant::now();
                {
                    let mut line_renderer = FpgaLineRenderer {
                        device: &mut device,
                        line_buffer: self.framebuffer,
                        outline: if outline::enabled() { Some(&mut self.outline) } else { None },
                    };
                    renderer.render_by_line(&mut line_renderer);
                    line_renderer.finish();
                };
                #[cfg(feature = "link-diag")]
                if probe_cheats {
                    crate::link::log_memory_now("cheats-after-render");
                }
                let render_duration = render_start.elapsed();
                log::info!("Render + display {}ms", render_duration.as_millis() as u32,);

                // If we changed the repaint buffer type to force a redraw, change it back.
                if renderer.repaint_buffer_type() == RepaintBufferType::NewBuffer {
                    renderer.set_repaint_buffer_type(RepaintBufferType::ReusedBuffer);
                    // For some reason, this doesn't get called automatically after the first render.
                    self.window.request_redraw();
                }
            });

            // Trigger a timer to wake us up for button repeat events.
            if let Some(wakeup) = self.button_event_detector.next_wakeup_time() {
                Timer::single_shot(wakeup.saturating_duration_since(Instant::now()), || ());
            }

            // Sleep until the next animation, timer, or event.
            if !self.window.has_active_animations() {
                match ::slint::platform::duration_until_next_timer_update() {
                    Some(duration) => {
                        pending_message = self.message_queue.recv_timeout(duration).ok();
                    }
                    None => {
                        pending_message = self.message_queue.recv().ok();
                    }
                }
            }
        }
    }

    /// Handle a message sent to the UI thread.
    fn dispatch_message(&mut self, message: Message) {
        match message {
            Message::Button(state) => {
                self.restore_brightness();
                for button_event in self.button_event_detector.update(Some(state)) {
                    self.handle_button_event(button_event);
                }
                self.reset_idle_timer();
            }
            Message::BatteryStatus { level, is_charging } => {
                self.state.borrow_mut().update_battery_level(level, is_charging);
            }
            Message::Redraw => {
                log::info!("Refreshing screen");
                self.window.request_redraw();

                // Force renderer to clear the dirty region and re-render everything.
                self.window
                    .renderer
                    .set_repaint_buffer_type(RepaintBufferType::NewBuffer);
            }
            Message::EnterGame { wireless_link } => {
                self.root.global::<slint::Backend>().set_link_page_open(false);
                self.root.global::<slint::Backend>().set_game_wireless_link(wireless_link);
                self.root
                    .global::<slint::Backend>()
                    .set_core_is_loading(false);
                self.root.invoke_set_screen(slint::ScreenId::Game);
            }
            Message::CoreLoadProgress(progress) => {
                self.root
                    .global::<slint::Backend>()
                    .set_core_is_loading(true);
                self.root
                    .global::<slint::Backend>()
                    .set_core_progress(progress * 100.0);
            }
            Message::FatalError(error) => {
                self.root
                    .global::<slint::Backend>()
                    .set_error_text(error.into());
                self.root.invoke_set_screen(slint::ScreenId::Error);
            }
            Message::InputState(state) => InputManager::lock().update_state(state),
            Message::Notification(notification) => {
                self.state
                    .borrow_mut()
                    .queue_notification(self.state, notification);
            }
            Message::DockBegin { serial, firmware } => {
                self.restore_brightness();
                self.reset_idle_timer();
                let backend = self.root.global::<slint::Backend>();
                backend.set_dock_serial(serial.into());
                backend.set_dock_firmware_version(firmware.into());
                backend.set_docked(true);
            }
            Message::DockEnd => {
                self.restore_brightness();
                self.reset_idle_timer();
                self.root.global::<slint::Backend>().set_docked(false);
            }
            Message::Screenshot => {
                match screenshot::save_ui_screenshot(&self.window, self.framebuffer) {
                    Ok(f) => log::info!("Saved UI screenshot to {f}"),
                    Err(e) => log::error!("Screenshot error: {e}"),
                }
            }
            Message::CoreList(list) => {
                self.state.borrow_mut().cores_list(list);
            }
            Message::CoreFileSelectBegin { label, path } => {
                self.state.borrow_mut().cores_file_select_begin(label, path);
            }
            Message::CoreFileSelectList(files) => {
                self.state.borrow_mut().cores_file_select_list(files);
            }
            Message::CoreFileSelectError(error) => {
                self.state.borrow_mut().cores_file_select_set_error(error);
            }
            Message::CoreLoadError(error) => {
                self.state.borrow_mut().cores_set_error(error);
            }
            Message::CoreSettingsList(items) => {
                self.state.borrow_mut().game_settings_list(items);
            }
            Message::CheatsAvailable(available) => {
                self.state.borrow_mut().game_cheats_available(available);
            }
            Message::CheatsPage(page) => {
                #[cfg(feature = "link-diag")]
                {
                    crate::link::log_memory_now("cheats-before-model");
                    self.cheats_render_probe = true;
                }
                self.state.borrow_mut().game_cheats_page(page);
            }
        }
    }
}
