const MAX_CORE_DIAG_LOG: usize = 24;
const FRAMEWORK_ADDR_START: u32 = 0xF000_0000;

pub struct CoreDiagLog {
    pub name: String,
    pub address: u32,
}

/// Validate the optional `core.json` diagnostic list without making malformed entries fatal.
pub fn validate_diag_log(value: &serde_json::Value) -> Result<Vec<CoreDiagLog>, &'static str> {
    let entries = value.as_array().ok_or("expected a list")?;
    if entries.len() > MAX_CORE_DIAG_LOG {
        return Err("too many entries (max 24)");
    }

    entries
        .iter()
        .map(|entry| {
            let object = entry.as_object().ok_or("expected an object")?;
            let name = object
                .get("name")
                .and_then(serde_json::Value::as_str)
                .ok_or("name must be a string")?;
            if name.len() > 12 || !name.is_ascii() {
                return Err("name must contain at most 12 ASCII characters");
            }

            let address = object
                .get("address")
                .and_then(serde_json::Value::as_str)
                .and_then(|address| address.strip_prefix("0x"))
                .and_then(|address| u32::from_str_radix(address, 16).ok())
                .ok_or("address must be a hexadecimal string")?;
            if address % 4 != 0 || address >= FRAMEWORK_ADDR_START {
                return Err("invalid address");
            }

            Ok(CoreDiagLog { name: name.to_owned(), address })
        })
        .collect()
}
