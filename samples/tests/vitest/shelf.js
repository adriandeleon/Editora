// The shelf logic that shelf.test.js checks under Vitest: an item's subtotal, a percentage off, a quantity
// read from text. An ES module, as Vitest expects.

/** The price of `quantity` items at `priceCents` each, in cents so the sums stay exact. */
export function subtotalCents(quantity, priceCents) {
  return quantity * priceCents;
}

/** `priceCents` with `percent` off, rounded to a whole cent. */
export function percentOff(priceCents, percent) {
  return Math.round((priceCents * (100 - percent)) / 100);
}

/** Turns "12" into 12; anything else throws a RangeError. */
export function parseQuantity(text) {
  const quantity = Number(text);
  if (!Number.isInteger(quantity) || quantity < 0) {
    throw new RangeError(`not a quantity: ${text}`);
  }
  return quantity;
}
