use std::collections::BTreeMap;

use crate::pricing;

/// What is on the shelf; expand one in Variables to see its fields.
#[derive(Debug, Clone)]
pub struct Item {
    pub sku: &'static str,
    pub name: String,
    pub quantity: u32,
    pub price: f64,
}

pub fn items() -> Vec<Item> {
    [
        ("A-1", "Pencil", 12, 0.40),
        ("A-2", "Notebook", 3, 2.75),
        ("B-1", "Stapler", 1, 8.90),
        ("B-2", "Tape", 4, 1.20),
        ("C-1", "Marker", 10, 1.05),
        ("C-2", "Eraser", 25, 0.30),
        ("C-3", "Ruler", 2, 1.50),
        ("D-1", "Glue", 6, 2.10),
    ]
    .into_iter()
    .map(|(sku, name, quantity, price)| Item {
        sku,
        name: name.to_string(),
        quantity,
        price,
    })
    .collect()
}

/// Totals up to `limit` items: `total`, `lines` and `by_prefix` change on every pass.
pub fn checkout(shelf: &[Item], limit: usize) -> (Vec<String>, f64) {
    let mut lines = Vec::new();
    let mut by_prefix: BTreeMap<String, u32> = BTreeMap::new();
    let mut total = 0.0;
    for item in shelf.iter().take(limit) {
        let discount = pricing::discount_for(item); // break here: Step Into, then look at `discount` per item
        let price = pricing::apply(discount.as_ref(), item.price);
        let subtotal = price * f64::from(item.quantity);
        lines.push(format!(
            "{:<4} {:<9} {:>3} x {:5.2} = {:7.2}",
            item.sku, item.name, item.quantity, price, subtotal
        ));
        *by_prefix.entry(item.sku[..1].to_string()).or_default() += item.quantity;
        total += subtotal; // break here: a watch on `total`, or a logpoint printing {item.name}
    }
    let mut cheap: Vec<&str> = shelf
        .iter()
        .filter(|item| item.price < 1.0) // break here: a conditional breakpoint, item.sku == "C-2"
        .map(|item| item.name.as_str())
        .collect();
    cheap.sort();
    lines.push(format!("cheap: {cheap:?}  by prefix: {by_prefix:?}"));
    (lines, total)
}

/// "12" is 12; a word is an error, returned rather than panicking.
pub fn parse_quantity(text: &str) -> Result<u32, String> {
    text.parse::<u32>()
        .map_err(|err| format!("not a quantity: {text:?}: {err}")) // break here: `err` in Variables
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checkout_totals() {
        let (lines, total) = checkout(&items(), 2);
        assert_eq!(lines.len(), 3); // break here: `lines` and `total` in Variables
        assert!((12.0..13.0).contains(&total), "total = {total}");
    }

    #[test]
    fn parse_quantity_reads_numbers_only() {
        assert_eq!(parse_quantity("12"), Ok(12));
        assert!(parse_quantity("many").is_err());
    }
}
