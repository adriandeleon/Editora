use crate::shelf::Item;

/// A percentage off, possibly stacked on another discount.
#[derive(Debug)]
pub struct Discount {
    pub percent: f64,
    pub then: Option<Box<Discount>>,
}

/// An item's discount: a stacked one for a big quantity.
pub fn discount_for(item: &Item) -> Option<Discount> {
    match item {
        Item { quantity: 10.., .. } => Some(Discount {
            percent: 10.0, // break here: hit once per big item
            then: Some(Box::new(Discount {
                percent: 5.0,
                then: None,
            })),
        }),
        Item { price, .. } if *price > 5.0 => Some(Discount {
            percent: 15.0,
            then: None,
        }),
        _ => None,
    }
}

/// Recurses through a stacked discount: the call stack shows two `apply` frames.
pub fn apply(discount: Option<&Discount>, price: f64) -> f64 {
    match discount {
        None => price,
        Some(d) => {
            let price = price * (100.0 - d.percent) / 100.0; // break here: Step Out, or look at the frames below
            apply(d.then.as_deref(), price)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_stacked_discount_applies_both() {
        let d = Discount {
            percent: 10.0,
            then: Some(Box::new(Discount {
                percent: 50.0,
                then: None,
            })),
        };
        assert!((apply(Some(&d), 100.0) - 45.0).abs() < 1e-9);
    }
}
