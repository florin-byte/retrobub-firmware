//! On-demand CD images (`files.json`: `"on_demand": true, "disc": true`).
//!
//! The image (see [`super::disc`]: a `.cue` with its `.bin` files, a lone
//! `.bin` or an `.iso`) is served to the core as one raw image of
//! 2352-byte sectors, the sector at absolute LBA L (track 1 at 150) at byte
//! offset (L - 150) x 2352, through the framework's `FILE_GET_SIZE` and
//! `FILE_READ`; `DISC_GET_TOC` writes its table of contents
//! ([`super::disc::DiscImage::toc`]) to the core's memory. A core that only
//! knows the official requests reads a single-track image as it is.

use std::{
    collections::HashSet,
    fs::{self, File},
    io::{BufRead, BufReader},
    ops::DerefMut,
    path::Path,
    time::Duration,
};

use esp_idf_svc::hal::units::Hertz;

use crate::device::{
    drivers::fpga::{self, SpiCommand, MAX_SPI_READ_CLOCK},
    Device,
};

use super::{command, disc, CoreManager};

/// The absolute LBA of the image's first sector.
const FIRST_LBA: u64 = 150;

// A disc read takes the existing scratch buffer for one sector.


impl CoreManager {
    /// Replace the served image before sending commands so subsequent
    /// FILE_READ and DISC_GET_TOC requests see the new disc.
    pub(super) fn next_disc(&mut self, file_id: u16) {
        use super::{on_demand::notify, Stage};
        if self.stage != Stage::Running || !self.is_disc_file(file_id) {
            return;
        }
        let index = self.core_info.as_ref().unwrap().files.iter()
            .position(|f| f.id == file_id).unwrap();
        let Some(current) = self.selected_files[index].as_ref() else { return };
        let Some(folder) = current.parent() else { return };
        let chosen = (|| -> std::io::Result<Option<String>> {
            // Keep references only, not every directory entry or whole cue sheet.
            let mut references = HashSet::new();
            let mut line = String::new();
            for entry in fs::read_dir(folder)? {
                let entry = entry?;
                // `metadata()`, not `DirEntry::file_type()`: on ESP-IDF the latter reads d_type with
                // the generic newlib codes of std's libc (DT_REG = 8) while ESP-IDF gives 1, so every
                // file would look like a FIFO. macOS "._name" companions are binary: skipped.
                if entry.file_name().to_str().is_none_or(|n| n.starts_with("._"))
                    || !entry.path().extension().is_some_and(|s| s.eq_ignore_ascii_case("cue"))
                    || !entry.metadata()?.is_file()
                {
                    continue;
                }
                let mut reader = BufReader::with_capacity(512, File::open(entry.path())?);
                loop {
                    line.clear();
                    if reader.read_line(&mut line)? == 0 { break; }
                    if let Some(name) = disc::cue_file_name(&line) {
                        // A leading ./ still names a file in this directory.
                        let name = name.strip_prefix("./").unwrap_or(name);
                        references.insert(name.to_lowercase());
                    }
                }
            }
            let names = fs::read_dir(folder)?.filter_map(|entry| {
                let result = (|| {
                    let entry = entry?;
                    let Some(name) = entry.file_name().to_str().map(str::to_owned) else { return Ok(None) };
                    // Only possible discs are worth a stat; see the note on `metadata()` above.
                    let extension = name.rsplit_once('.').map_or("", |(_, e)| e);
                    if name.starts_with("._")
                        || !["cue", "iso", "bin"].iter().any(|e| extension.eq_ignore_ascii_case(e))
                        || !entry.metadata()?.is_file()
                    {
                        return Ok(None);
                    }
                    Ok(Some(name))
                })();
                match result {
                    Ok(Some(name)) => Some(Ok(name)),
                    Ok(None) => None,
                    Err(e) => Some(Err(e)),
                }
            });
            disc::next_disc_name(current, names, &references)
        })();
        let name = match chosen {
            Ok(Some(name)) => name,
            Ok(None) => {
                notify("No other disc in this folder".to_string());
                return;
            }
            Err(e) => {
                log::error!("Next disc directory {}: {e}", folder.display());
                notify("Cannot list disc folder".to_string());
                return;
            }
        };
        let path = folder.join(&name);
        let image = match disc::DiscImage::open(&path) {
            Ok(image) => image,
            Err(e) => {
                log::error!("Next disc {}: {e}", path.display());
                notify(format!("Cannot open {}", disc_label(&name)));
                return;
            }
        };
        image.log();
        log::info!("Next disc file {file_id}: {}", path.display());
        self.selected_files[index] = Some(path);
        self.open_disc = Some((file_id, image));
        let timeout = Duration::from_millis(3500);
        let result = self.run_core_command(
            &[command::FILE_WRITE_START, file_id as u32], &mut [], timeout,
        ).and_then(|()| self.run_core_command(
            &[command::FILE_WRITE_END, file_id as u32, 0, 0], &mut [], timeout,
        ));
        if let Err(e) = result {
            log::error!("Next disc notification {name}: {e}");
            notify(format!("Disc change failed: {}", disc_label(&name)));
        } else {
            log::info!("Next disc mounted: {name}");
            notify(format!("Disc: {}", disc_label(&name)));
        }
    }

    /// Whether the core's file `file_id` is an on-demand disc.
    pub(super) fn is_disc_file(&self, file_id: u16) -> bool {
        self.core_info
            .as_ref()
            .is_some_and(|core| core.files.iter().any(|f| f.id == file_id && f.on_demand && f.disc))
    }

    /// The disc in file `file_id`, opened on its first use.
    fn disc_image(&mut self, file_id: u16) -> Result<&mut disc::DiscImage, u32> {
        if self.open_disc.as_ref().is_some_and(|(id, _)| *id == file_id) {
            return Ok(&mut self.open_disc.as_mut().unwrap().1);
        }
        let core = self.core_info.as_ref().unwrap();
        let Some(i) = core.files.iter().position(|f| f.id == file_id) else {
            return Err(command::FILE_STATUS_ERR_INVALID_FILE);
        };
        let Some(path) = self.selected_files[i].as_ref() else {
            return Err(command::FILE_STATUS_ERR_INVALID_FILE);
        };
        let image = disc::DiscImage::open(path).map_err(|e| {
            log::error!("Disc {}: {e}", path.display());
            let text = if path.extension().is_some_and(|e| e.eq_ignore_ascii_case("chd")) {
                "CHD disc images are not supported; select a cue, bin or iso image"
            } else {
                "Cannot open the disc image"
            };
            crate::ui::send(crate::ui::Message::Notification(crate::ui::Notification::new_long(text.to_string())));
            command::FILE_STATUS_ERR_IO
        })?;
        image.log();
        Ok(&mut self.open_disc.insert((file_id, image)).1)
    }

    fn disc_transfer_speed(&self, file_id: u16) -> u32 {
        self.core_info
            .as_ref()
            .and_then(|core| core.files.iter().find(|f| f.id == file_id))
            .map_or(5000, |f| f.max_transfer_speed)
    }

    /// `FILE_GET_SIZE` of a disc: its raw image's size.
    pub(super) fn handle_disc_get_size(&mut self, file_id: u16, result: &mut [u32]) -> bool {
        match self.disc_image(file_id) {
            Ok(image) => {
                let size = (image.lead_out() as u64).saturating_sub(FIRST_LBA) * disc::SECTOR_SIZE as u64;
                result[0] = command::FILE_STATUS_OK;
                result[1] = size as u32;
                result[2] = (size >> 32) as u32;
                true
            }
            Err(status) => {
                result[0] = status;
                false
            }
        }
    }

    /// `FILE_READ` of a disc: bytes of its raw image, sector by sector.
    pub(super) fn handle_disc_read(&mut self, params: &[u32], result: &mut [u32]) -> bool {
        let file_id = params[0] as u16;
        let address = params[1];
        let offset = ((params[3] as u64) << 32) | (params[2] as u64);
        let length = params[4] as usize;
        result[0] = command::FILE_STATUS_OK;
        result[1] = 0;

        let write_clock = Hertz(self.disc_transfer_speed(file_id) * 1000 * 2);
        let image = match self.disc_image(file_id) {
            Ok(image) => image,
            Err(status) => {
                result[0] = status;
                return false;
            }
        };
        // The scratch buffer (internal RAM): a sector.
        let Some(mut scratch) = crate::bitstream::SCRATCH.take() else {
            log::warn!("Disc read: scratch buffer busy");
            result[0] = command::FILE_STATUS_ERR_IO;
            return false;
        };
        let Some(buffer) = scratch.deref_mut().get_mut(..disc::SECTOR_SIZE) else {
            result[0] = command::FILE_STATUS_ERR_IO;
            return false;
        };
        let sector: &mut [u8; disc::SECTOR_SIZE] = buffer.try_into().unwrap();
        let command = SpiCommand::new(fpga::FpgaSpiWordSize::Bits32);

        let mut transferred = 0;
        let read = image.read_at(offset, length, sector, |at, bytes| {
            let target = address.checked_add(at as u32)
                .ok_or(std::io::ErrorKind::InvalidInput)?;
            Device::lock().fpga.spi_write(Some(write_clock), command, target, bytes)
                .map_err(|_| std::io::Error::from(std::io::ErrorKind::Other))?;
            transferred += bytes.len();
            Ok(())
        });
        result[1] = transferred as u32;
        if let Err(e) = read {
            log::error!("Disc read: {e}");
            result[0] = command::FILE_STATUS_ERR_IO;
            return false;
        }
        true
    }

    /// `DISC_GET_TOC` (file, address): the disc's table of contents
    /// ([`disc::DiscImage::toc`]) to the core's memory.
    pub(super) fn handle_disc_get_toc(&mut self, params: &[u32], result: &mut [u32]) -> bool {
        let file_id = params[0] as u16;
        let address = params[1];
        if !self.is_disc_file(file_id) {
            result[0] = command::FILE_STATUS_ERR_INVALID_FILE;
            return false;
        }
        let image = match self.disc_image(file_id) {
            Ok(image) => image,
            Err(status) => {
                result[0] = status;
                return false;
            }
        };
        let toc = image.toc();
        let ok = Device::lock()
            .fpga
            .spi_write(
                Some(MAX_SPI_READ_CLOCK),
                SpiCommand::new(fpga::FpgaSpiWordSize::Bits32),
                address,
                &toc,
            )
            .is_ok();
        result[0] = if ok { command::FILE_STATUS_OK } else { command::FILE_STATUS_ERR_IO };
        ok
    }
}

/// A short UTF-8-safe disc title for notifications.
fn disc_label(name: &str) -> String {
    let stem = Path::new(name).file_stem().and_then(|s| s.to_str()).unwrap_or(name);
    let mut title: String = stem.chars().take(40).collect();
    if stem.chars().count() > 40 { title.push_str("..."); }
    title
}
