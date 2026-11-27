// Discounts as a tagged union: apply recurses through a stacked one, so the call stack shows two apply frames.

export type Discount =
  | { kind: "none" }
  | { kind: "percent"; percent: number }
  | { kind: "stacked"; first: Discount; then: Discount };

/** An item's discount, picked by its quantity and price. */
export function discountFor(quantity: number, price: number): Discount {
  if (quantity >= 10) {
    const first: Discount = { kind: "percent", percent: 10 };
    return { kind: "stacked", first, then: { kind: "percent", percent: 5 } }; // break here: hit once per big item
  }
  return price > 5 ? { kind: "percent", percent: 15 } : { kind: "none" };
}

/** The price after `discount`; a stacked one applies its first part, then recurses into the rest. */
export function apply(discount: Discount, price: number): number {
  switch (discount.kind) {
    case "none":
      return price;
    case "percent":
      return (price * (100 - discount.percent)) / 100; // break here: Step Out, or look at the frames below
    case "stacked":
      return apply(discount.then, apply(discount.first, price));
  }
}
