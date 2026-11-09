// The shelf, in Rust: open this file and press F5 (Debug). Cargo builds it in the Debug console, then CodeLLDB
// runs it. Every suggested spot is a "break here" comment. `cargo run` runs it outside Editora, `cargo test` runs
// the tests (put the caret in one and use Debug Test at Caret to debug it alone).
mod pricing;
mod restock;
mod shelf;

fn main() {
    let limit: usize = 6; // break here: a first plain breakpoint; Step Over, then Step Into checkout
    let argument = std::env::args().nth(1).unwrap_or_default();
    if argument == "crash" {
        let empty: Vec<u32> = Vec::new();
        println!("{}", empty[0]); // a panic: the debugger stops here (Rust: on panic)
    }
    let (lines, total) = shelf::checkout(&shelf::items(), limit);
    for line in &lines {
        println!("{line}");
    }
    println!("total {total:.2}");
    println!("restocked {}", restock::restock(3));
}
