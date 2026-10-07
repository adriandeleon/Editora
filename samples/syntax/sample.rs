//! A small Rust sample for syntax highlighting and folding.
//!
//! Covers doc comments, an enum with data, a trait, generics with a lifetime,
//! pattern matching, `Result` with `?`, a closure, a macro, and attributes.

use std::collections::HashMap;
use std::fmt;

const MAX_ITEMS: usize = 0x40; // hex literal

/// Anything the shelf can hold.
#[derive(Debug, Clone, PartialEq)]
enum Item {
    Book { title: String, pages: u32 },
    Tool(String, f64),
}

#[derive(Debug)]
struct ShelfFull;

impl fmt::Display for ShelfFull {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "the shelf holds at most {MAX_ITEMS} items")
    }
}

trait Describe {
    fn describe(&self) -> String;
}

impl Describe for Item {
    fn describe(&self) -> String {
        match self {
            Item::Book { title, pages } if *pages > 500 => format!("a long book: \"{title}\""),
            Item::Book { title, .. } => format!("a book: {title}"),
            Item::Tool(name, kg) => format!("a tool ({kg:.1} kg): {name}"),
        }
    }
}

#[derive(Default)]
struct Shelf {
    items: Vec<Item>,
}

impl Shelf {
    fn add(&mut self, item: Item) -> Result<(), ShelfFull> {
        if self.items.len() >= MAX_ITEMS {
            return Err(ShelfFull);
        }
        self.items.push(item);
        Ok(())
    }

    /// The first item whose description contains `needle`.
    fn find<'a>(&'a self, needle: &str) -> Option<&'a Item> {
        self.items.iter().find(|item| item.describe().contains(needle))
    }
}

fn main() -> Result<(), ShelfFull> {
    let mut shelf = Shelf::default();
    shelf.add(Item::Book { title: "Dune".into(), pages: 612 })?;
    shelf.add(Item::Tool(String::from("hammer"), 0.6))?;

    /* A block comment: count the items by kind. */
    let mut counts: HashMap<&str, usize> = HashMap::new();
    for item in &shelf.items {
        let kind = if matches!(item, Item::Book { .. }) { "book" } else { "tool" };
        *counts.entry(kind).or_insert(0) += 1;
    }
    println!("{counts:?} {:?}", shelf.find("hammer").map(Describe::describe));
    Ok(())
}
