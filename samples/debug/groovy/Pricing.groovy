// Discounts as maps, used by shelf.groovy: a stacked one makes Pricing.apply recurse (nested frames).
class Pricing {

    /** An item's discount: a stacked one for a big quantity, null for most. */
    static Map discountFor(Item item) {
        if (item.quantity >= 10) {
            return [percent: 10, then: [percent: 5]] // break here: hit once per big item
        }
        item.price > 5 ? [percent: 15] : null
    }

    /** Recurses through a stacked discount: the call stack shows two `Pricing.apply` frames. */
    static BigDecimal apply(Map discount, BigDecimal price) {
        if (discount == null) {
            return price
        }
        def cut = price * (100 - discount.percent) / 100 // break here: Step Out, or look at the frames below
        apply(discount.then as Map, cut)
    }
}
