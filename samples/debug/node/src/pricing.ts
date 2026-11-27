// Discounts, possibly stacked on another: applyDiscount recurses, so the call stack shows nested frames.
import type { Item } from "./shelf.ts";

/** A percentage off, possibly stacked on another discount. */
export interface Discount {
  percent: number;
  stacked?: Discount;
}

/** An item's discount: a stacked one for a big quantity, none for most. */
export function discountFor(item: Item): Discount | undefined {
  if (item.quantity >= 10) {
    return { percent: 10, stacked: { percent: 5 } }; // break here: hit once per big item
  }
  return item.price > 5 ? { percent: 15 } : undefined;
}

/** Recurses through a stacked discount: the call stack shows two applyDiscount frames. */
export function applyDiscount(discount: Discount | undefined, price: number): number {
  if (discount === undefined) {
    return price;
  }
  const discounted = (price * (100 - discount.percent)) / 100; // break here: Step Out, or look at the frames below
  return applyDiscount(discount.stacked, discounted);
}
