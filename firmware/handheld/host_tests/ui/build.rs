use std::path::PathBuf;

fn main() {
    // Same compiler configuration as the firmware's build.rs (style included).
    let firmware = PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("../..");
    println!("cargo:rerun-if-changed=../../res/style/gamebub");
    slint_build::compile_with_config(
        "../../res/ui/main.slint",
        slint_build::CompilerConfiguration::new()
            .embed_resources(slint_build::EmbedResourcesKind::EmbedForSoftwareRenderer)
            .with_style("gamebub".into())
            .with_include_paths(vec![firmware.join("res/style"), firmware.join("res/style/gamebub")]),
    )
    .unwrap();
}
