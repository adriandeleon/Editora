package main

// Discount is a percentage off, possibly stacked on another discount.
type Discount struct {
	Percent float64
	Then    *Discount
}

// discountFor picks an item's discount: a stacked one for a big quantity.
func discountFor(item Item) *Discount {
	switch {
	case item.Quantity >= 10:
		return &Discount{Percent: 10, Then: &Discount{Percent: 5}} // break here: hit once per big item
	case item.Price > 5:
		return &Discount{Percent: 15}
	default:
		return nil
	}
}

// applyDiscount recurses through a stacked discount: the call stack shows two applyDiscount frames.
func applyDiscount(d *Discount, price float64) float64 {
	if d == nil {
		return price
	}
	price = price * (100 - d.Percent) / 100 // break here: Step Out, or look at the frames below
	return applyDiscount(d.Then, price)
}
