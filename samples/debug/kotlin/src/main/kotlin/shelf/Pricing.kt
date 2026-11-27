// Discounts: a stacked discount makes Pricing.apply recurse, so the call stack shows nested frames.
package shelf

/** A percentage off, possibly stacked on another discount. */
data class Discount(val percent: Int, val then: Discount? = null)

object Pricing {
    /** An item's discount: a stacked one for a big quantity, none for most. */
    fun discountFor(item: Item): Discount? = when {
        item.quantity >= 10 -> Discount(10, Discount(5)) // break here: hit once per big item
        item.price > 5 -> Discount(15)
        else -> null
    }

    /** Recurses through a stacked discount: the call stack shows two `Pricing.apply` frames. */
    fun apply(discount: Discount?, price: Double): Double {
        if (discount == null) {
            return price
        }
        val cut = price * (100 - discount.percent) / 100.0 // break here: Step Out, or look at the frames below
        return apply(discount.then, cut)
    }
}
