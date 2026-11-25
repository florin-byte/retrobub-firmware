//! The console's date and time for a core that has a clock (`rtc` in core.json).
//!
//! The layout is the one MiSTer cores get from its framework: BCD seconds, minutes, hours, day of the month, month
//! and two-digit year in bits 7:0, 15:8, 23:16, 31:24, 39:32 and 47:40, and a bit 64 that changes when the value is
//! new. The core has them as three 32-bit registers: bits 31:0 at `address`, 63:32 at `address + 4`, bit 64 at
//! `address + 8`.

const FRAMEWORK_ADDR_START: u32 = 0xF000_0000;

/// Whether `address` can hold the three registers.
pub fn valid_address(address: u32) -> bool {
    address % 4 == 0 && address < FRAMEWORK_ADDR_START - 8
}

fn bcd(value: u32) -> u32 {
    ((value / 10) % 10) << 4 | (value % 10)
}

/// The words for `address` and `address + 4`.
pub fn pack(year: u32, month: u32, day: u32, hour: u32, minute: u32, second: u32) -> [u32; 2] {
    [
        bcd(day) << 24 | bcd(hour) << 16 | bcd(minute) << 8 | bcd(second),
        bcd(year % 100) << 8 | bcd(month),
    ]
}
