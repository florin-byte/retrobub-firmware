#[cfg(test)]
#[path = "../../../src/core/disc.rs"]
mod disc;

#[cfg(test)]
#[path = "../../../src/core/diag_log.rs"]
mod diag_log;

#[cfg(test)]
mod diag_log_tests {
    use super::diag_log::validate_diag_log;
    use serde_json::json;

    #[test]
    fn accepts_valid_entries() {
        let entries = validate_diag_log(&json!([
            {"name": "ctrl", "address": "0xF40"},
            {"name": "ticks", "address": "0xF58"}
        ]))
        .unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].name, "ctrl");
        assert_eq!(entries[0].address, 0xF40);
    }

    #[test]
    fn rejects_name_longer_than_twelve_characters() {
        assert!(validate_diag_log(&json!([
            {"name": "thirteen_chars", "address": "0x480"}
        ]))
        .is_err());
    }

    #[test]
    fn rejects_unaligned_address() {
        assert!(validate_diag_log(&json!([
            {"name": "ctrl", "address": "0x481"}
        ]))
        .is_err());
    }

    #[test]
    fn rejects_more_than_twenty_four_entries() {
        let entries = (0..25)
            .map(|index| json!({"name": format!("d{index}"), "address": "0x480"}))
            .collect::<Vec<_>>();
        assert!(validate_diag_log(&json!(entries)).is_err());
    }
}

#[cfg(test)]
mod tests {
    use super::disc::{DiscImage, SECTOR_SIZE};
    use std::{fs, path::{Path, PathBuf}};

    fn fixture(name: &str) -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures").join(name)
    }

    fn read(image: &mut DiscImage, offset: u64, length: usize) -> Vec<u8> {
        let mut bytes = vec![0xa5; length];
        let mut sector = [0xa5; SECTOR_SIZE];
        image.read_at(offset, length, &mut sector, |at, data| {
            bytes[at..at + data.len()].copy_from_slice(data);
            Ok(())
        }).unwrap();
        bytes
    }

    fn check_generated(name: &str) {
        let dir = fixture(name);
        let mut image = DiscImage::open(&dir.join("test.cue")).unwrap();
        assert_eq!(image.toc(), fs::read(dir.join("toc.bin")).unwrap());
        assert_eq!(image.lead_out(), 1650);
        let physical = if name == "single" {
            fs::read(dir.join("disc.bin")).unwrap()
        } else {
            ["data.bin", "left.bin", "right.bin"].iter()
                .flat_map(|p| fs::read(dir.join(p)).unwrap()).collect()
        };
        // Independent mapping: virtual pregap is inserted at logical sector 1050.
        let mut expected = physical[..1050 * SECTOR_SIZE].to_vec();
        expected.extend(vec![0; 150 * SECTOR_SIZE]);
        expected.extend_from_slice(&physical[1050 * SECTOR_SIZE..]);
        expected.extend(vec![0; 5 * SECTOR_SIZE]);
        for (i, want) in expected.chunks_exact(SECTOR_SIZE).enumerate() {
            assert_eq!(read(&mut image, (i * SECTOR_SIZE) as u64, SECTOR_SIZE), want, "sector {i}");
        }
        // Full unaligned transfer crosses all sectors, files, stored and virtual gaps.
        assert_eq!(read(&mut image, 17, expected.len() - 31), expected[17..expected.len() - 14]);
        for boundary in [600, 750, 1050, 1200, 1500] {
            let offset = boundary * SECTOR_SIZE - 19;
            assert_eq!(read(&mut image, offset as u64, 3 * SECTOR_SIZE + 43),
                       expected[offset..offset + 3 * SECTOR_SIZE + 43]
            );
        }
        // Seek back and forth forces rotation of the secondary file handle.
        for index in [1300, 780, 2, 1499, 751, 1] {
            assert_eq!(read(&mut image, (index * SECTOR_SIZE) as u64, SECTOR_SIZE),
                       expected[index * SECTOR_SIZE..(index + 1) * SECTOR_SIZE]);
        }
        let mut sector = [1; SECTOR_SIZE];
        image.read_sector(0, &mut sector).unwrap();
        assert_eq!(sector, [0; SECTOR_SIZE]);
        assert_eq!(read(&mut image, u64::MAX - 10, 10), vec![0; 10]);
        assert!(image.read_at(u64::MAX, 2, &mut sector, |_, _| Ok(())).is_err());
    }

    #[test]
    fn generated_multi() { check_generated("multi"); }
    #[test]
    fn generated_single() { check_generated("single"); }

    // Each test owns a separate directory, including on parallel Windows test runs.
    fn case(name: &str) -> PathBuf {
        let path = fixture("cases").join(name);
        fs::create_dir_all(&path).unwrap();
        path
    }

    fn edc(bytes: &[u8]) -> u32 {
        let mut value = 0u32;
        for &b in bytes {
            value ^= b as u32;
            for _ in 0..8 {
                value = (value >> 1) ^ if value & 1 != 0 { 0xd8018001 } else { 0 };
            }
        }
        value
    }

    fn check_iso(mut image: DiscImage, payload: &[u8]) {
        let raw = read(&mut image, 0, 2 * SECTOR_SIZE);
        for i in 0..2 {
            let sector = &raw[i * SECTOR_SIZE..(i + 1) * SECTOR_SIZE];
            assert_eq!(&sector[..12], &[0, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 0]);
            assert_eq!(&sector[12..16], &[0, 2, i as u8, 1]);
            assert_eq!(&sector[16..2064], &payload[i * 2048..(i + 1) * 2048]);
            assert_eq!(u32::from_le_bytes(sector[2064..2068].try_into().unwrap()), edc(&sector[..2064]));
            assert_eq!(&sector[2068..2076], &[0; 8]);
            assert!(sector[2076..2248].iter().any(|&b| b != 0));
            assert!(sector[2248..].iter().any(|&b| b != 0));
        }
    }

    #[test]
    fn iso_and_mode1_2048() {
        let dir = case("iso");
        let payload: Vec<_> = (0..4096).map(|i| (i * 23 + 3) as u8).collect();
        fs::write(dir.join("data.iso"), &payload).unwrap();
        check_iso(DiscImage::open(&dir.join("data.iso")).unwrap(), &payload);
        fs::write(dir.join("test.cue"), "FILE \"data.iso\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n").unwrap();
        check_iso(DiscImage::open(&dir.join("test.cue")).unwrap(), &payload);
    }

    #[test]
    fn raw_bin_rounds_down_and_mode2_is_unchanged() {
        let dir = case("raw");
        let raw: Vec<_> = (0..2 * SECTOR_SIZE + 17).map(|i| (i * 7) as u8).collect();
        fs::write(dir.join("raw.bin"), &raw).unwrap();
        let mut image = DiscImage::open(&dir.join("raw.bin")).unwrap();
        assert_eq!(image.lead_out(), 152);
        assert_eq!(read(&mut image, 0, 2 * SECTOR_SIZE), raw[..2 * SECTOR_SIZE]);
        assert_eq!(read(&mut image, (2 * SECTOR_SIZE) as u64, SECTOR_SIZE), vec![0; SECTOR_SIZE]);
        fs::write(dir.join("mode2.bin"), &raw[..2 * SECTOR_SIZE]).unwrap();
        fs::write(dir.join("test.cue"), "FILE \"mode2.bin\" BINARY\n TRACK 01 MODE2/2352\n INDEX 01 00:00:00\n").unwrap();
        let mut image = DiscImage::open(&dir.join("test.cue")).unwrap();
        assert_eq!(read(&mut image, 0, 2 * SECTOR_SIZE), raw[..2 * SECTOR_SIZE]);
    }

    #[test]
    fn malformed_images_return_errors() {
        let dir = case("errors");
        fs::write(dir.join("bad.bin"), [0; 2353]).unwrap();
        for text in [
            "FILE \"missing.bin\" BINARY\n TRACK 01 AUDIO\n INDEX 01 00:00:00\n",
            "FILE \"bad.bin\" BINARY\n TRACK 01 AUDIO\n INDEX 01 00:00:00\n",
            "FILE \"bad.bin\" BINARY\n TRACK 01 MODE1/2048\n INDEX 01 00:00:00\n",
            "TRACK 01 AUDIO\n INDEX 01 00:00:00\n",
        ] {
            fs::write(dir.join("bad.cue"), text).unwrap();
            assert!(DiscImage::open(&dir.join("bad.cue")).is_err());
        }
        fs::write(dir.join("valid.bin"), [0; 2352]).unwrap();
        for indexes in ["INDEX 01 00:01:00", "INDEX 00 00:00:01\nINDEX 01 00:00:00", "INDEX 01 999999999:00:00"] {
            fs::write(dir.join("bad.cue"), format!("FILE \"valid.bin\" BINARY\nTRACK 01 AUDIO\n{indexes}\n")).unwrap();
            assert!(DiscImage::open(&dir.join("bad.cue")).is_err());
        }
        let error = DiscImage::open(&dir.join("disc.CHD")).err().unwrap();
        assert!(error.contains("CHD") && error.contains("not supported"));
    }

    #[test]
    fn maximum_track_count_and_toc() {
        let dir = case("99");
        fs::write(dir.join("tracks.bin"), vec![0; 99 * SECTOR_SIZE]).unwrap();
        let mut cue = "FILE \"tracks.bin\" BINARY\n".to_string();
        for i in 0..99 {
            cue.push_str(&format!(" TRACK {:02} AUDIO\n INDEX 01 00:{:02}:{:02}\n", i + 1, i / 75, i % 75));
        }
        fs::write(dir.join("test.cue"), &cue).unwrap();
        let image = DiscImage::open(&dir.join("test.cue")).unwrap();
        let toc = image.toc();
        assert_eq!(toc.len(), 1600);
        assert_eq!(&toc[..4], &0x9963u32.to_le_bytes());
        cue.push_str("TRACK 100 AUDIO\nINDEX 01 00:01:23\n");
        fs::write(dir.join("test.cue"), cue).unwrap();
        assert!(DiscImage::open(&dir.join("test.cue")).is_err());
    }
}

#[cfg(test)]
mod allocations {
    use std::{alloc::{GlobalAlloc, Layout, System}, cell::Cell};
    thread_local! {
        static COUNT: Cell<Option<usize>> = const { Cell::new(None) };
    }
    struct Counting;
    #[global_allocator]
    static ALLOCATOR: Counting = Counting;
    fn record() {
        COUNT.with(|count| {
            if let Some(n) = count.get() { count.set(Some(n + 1)); }
        });
    }
    unsafe impl GlobalAlloc for Counting {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            record();
            System.alloc(layout)
        }
        unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
            System.dealloc(ptr, layout)
        }
        unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
            record();
            System.realloc(ptr, layout, size)
        }
    }
    #[test]
    fn reads_do_not_allocate_after_opening_the_file() {
        use super::disc::{DiscImage, SECTOR_SIZE};
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("fixtures/single/test.cue");
        let mut image = DiscImage::open(&path).unwrap();
        let mut sector = [0; SECTOR_SIZE];
        image.read_sector(150, &mut sector).unwrap();
        COUNT.with(|n| n.set(Some(0)));
        let result = image.read_at(13, 1502 * SECTOR_SIZE, &mut sector, |_, _| Ok(()));
        let allocations = COUNT.with(|n| n.replace(None).unwrap());
        result.unwrap();
        assert_eq!(allocations, 0);
    }
}

#[cfg(test)]
mod next_disc {
    use super::disc::{cue_file_name, next_disc_name};
    use std::{collections::HashSet, path::Path};

    fn choose(current: &str, names: &[&str], references: &[&str]) -> Option<String> {
        let references: HashSet<_> = references.iter().map(|s| s.to_lowercase()).collect();
        next_disc_name(Path::new(current), names.iter().map(|s| Ok(s.to_string())), &references).unwrap()
    }

    #[test]
    fn alphabetical_order_and_wrap() {
        let names = ["Disc 3.iso", "Disc 1.cue", "Disc 2.iso", "Disc 10.iso"];
        assert_eq!(choose("/games/Disc 1.cue", &names, &[]).as_deref(), Some("Disc 10.iso"));
        assert_eq!(choose("Disc 2.iso", &names, &[]).as_deref(), Some("Disc 3.iso"));
        assert_eq!(choose("Disc 3.iso", &names, &[]).as_deref(), Some("Disc 1.cue"));
    }

    #[test]
    fn single_disc_and_empty_folder() {
        assert_eq!(choose("disc.cue", &["disc.cue", "track.bin"], &["track.bin"]), None);
        assert_eq!(choose("disc.iso", &["disc.iso"], &[]), None);
        assert_eq!(choose("disc.iso", &[], &[]), None);
    }

    #[test]
    fn cue_tracks_excluded_and_standalone_bin_included() {
        let names = ["a.cue", "b.bin", "c.bin", "d.cue"];
        assert_eq!(choose("a.cue", &names, &["b.bin"]).as_deref(), Some("c.bin"));
        assert_eq!(choose("c.bin", &names, &["b.bin"]).as_deref(), Some("d.cue"));
    }

    #[test]
    fn case_insensitive_names_extensions_and_references() {
        let names = ["Alpha.CUE", "beta.ISO", "TRACK.BIN", "Zeta.BiN"];
        assert_eq!(choose("ALPHA.cue", &names, &["track.bin"]).as_deref(), Some("beta.ISO"));
        assert_eq!(choose("BETA.iso", &names, &["track.bin"]).as_deref(), Some("Zeta.BiN"));
        assert_eq!(choose("zeta.bin", &names, &["track.bin"]).as_deref(), Some("Alpha.CUE"));
    }

    #[test]
    fn unrelated_files_ignored() {
        assert_eq!(choose("a.iso", &["a.iso", "b.txt", "c.chd", "d", "e.iso.bak"], &[]), None);
    }

    #[test]
    fn quoted_cue_file_lines_only() {
        assert_eq!(cue_file_name("  FiLe \"Track 01.BIN\" BINARY"), Some("Track 01.BIN"));
        for line in ["REM FILE \"no.bin\"", "FILE no.bin BINARY", "FILE \"broken", "TRACK 01 AUDIO", ""] {
            assert_eq!(cue_file_name(line), None);
        }
    }

    #[test]
    fn listing_errors_abort_selection() {
        let names = [Ok("b.iso".to_string()), Err(std::io::ErrorKind::PermissionDenied.into())];
        assert!(next_disc_name(Path::new("a.iso"), names, &HashSet::new()).is_err());
    }
}

/// Real images, outside the repository: `DISC_REAL_CUES` lists cue paths separated by `|`.
/// Run with `-- --ignored --nocapture`; prints each table of contents and reads the disc's ends.
#[cfg(test)]
mod real_images {
    use super::disc::{DiscImage, SECTOR_SIZE};
    use std::path::Path;

    #[test]
    #[ignore]
    fn open_and_read() {
        let list = std::env::var("DISC_REAL_CUES").expect("DISC_REAL_CUES");
        for path in list.split('|').filter(|p| !p.is_empty()) {
            let mut image = DiscImage::open(Path::new(path)).unwrap_or_else(|e| panic!("{path}: {e}"));
            let toc = image.toc();
            let word = |i: usize| u32::from_le_bytes(toc[4 * i..4 * i + 4].try_into().unwrap());
            let tracks = (word(0) & 0xff) as usize;
            let audio = (0..tracks).filter(|n| word(7 + 4 * n) >> 31 != 0).count();
            println!("{path}\n  tracks={tracks} audio={audio} lead-out LBA={} ({:06x})", word(1), word(2));
            println!("  track 2: first={} index1={} msf={:06x}", word(8), word(10), word(11) & 0xff_ffff);
            let mut sector = [0u8; SECTOR_SIZE];
            image.read_sector(150, &mut sector).unwrap();
            assert_eq!(&sector[..12], &[0, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 0], "{path}: sync");
            println!("  sector 0 header: {}", String::from_utf8_lossy(&sector[16..32]));
            // every track: its index 1 sector and its last sector must be readable
            for n in 0..tracks {
                image.read_sector(word(6 + 4 * n), &mut sector).unwrap_or_else(|e| panic!("{path} track {}: {e}", n + 1));
                image.read_sector(word(5 + 4 * n), &mut sector).unwrap_or_else(|e| panic!("{path} track {}: {e}", n + 1));
            }
        }
    }
}
