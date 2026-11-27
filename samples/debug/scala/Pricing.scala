// Discounts, and the price after one: a stacked discount recurses.
package shelf

enum Discount:
  case Percent(pct: Int)
  case Stacked(first: Discount, rest: Discount)
  case NoDiscount

object Pricing:
  // An item's discount: a stacked one for a big quantity.
  def discountFor(item: Item): Discount =
    if item.quantity >= 10 then
      Discount.Stacked(Discount.Percent(10), Discount.Percent(5)) // break here: hit once per big item
    else if item.price > 5 then Discount.Percent(15)
    else Discount.NoDiscount

  // Recurses through a stacked discount: the call stack shows nested Pricing.apply frames.
  def apply(discount: Discount, price: Double): Double = discount match
    case Discount.Percent(pct) =>
      price * (100 - pct) / 100.0 // break here: Step Out, or look at the apply frames below
    case Discount.Stacked(first, rest) => apply(rest, apply(first, price))
    case Discount.NoDiscount           => price
