/// The line `main` prints; the tests below check it.
fn greeting(who: &str) -> String {
    format!("Hello from {who}.")
}

fn main() {
    println!("{}", greeting("the Cargo sample project"));
}

// `cargo test` from the Cargo popup fills the Test Results tool window: one test passes, one fails on
// purpose, and one is ignored, so every status has an example.
#[cfg(test)]
mod tests {
    use super::greeting;

    #[test]
    fn greets_by_name() {
        assert_eq!(greeting("Ada"), "Hello from Ada.");
    }

    #[test]
    fn fails_on_purpose() {
        assert_eq!(greeting("Ada"), "Goodbye from Ada.", "this failure is the sample's red test");
    }

    #[test]
    #[ignore = "skipped on purpose"]
    fn skipped_on_purpose() {}
}
