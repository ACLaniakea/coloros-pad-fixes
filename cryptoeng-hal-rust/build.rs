use std::path::PathBuf;

fn main() {
    rsbinder_aidl::Builder::new()
        .source(PathBuf::from("aidl/ICryptoeng.aidl"))
        .output("icryptoeng.rs")
        .version(1)
        .hash("765136eb397eb5b85ee1e089d8fec72c15e266b3")
        .generate()
        .expect("aidl generation failed");
}
