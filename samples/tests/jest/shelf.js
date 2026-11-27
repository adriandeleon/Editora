// The shelf logic that shelf.test.js checks under Jest: an item's subtotal, a percentage off, a quantity
// read from text. CommonJS, which Jest runs without any setup.

/** The price of `quantity` items at `price` each, in cents so the sums stay exact. */
function subtotalCents(quantity, priceCents) {
  return quantity * priceCents;
}

/** `price` with `percent` off, rounded to a whole cent. */
function percentOff(priceCents, percent) {
  return Math.round((priceCents * (100 - percent)) / 100);
}

/** Turns "12" into 12; anything else throws a RangeError. */
function parseQuantity(text) {
  const quantity = Number(text);
  if (!Number.isInteger(quantity) || quantity < 0) {
    throw new RangeError(`not a quantity: ${text}`);
  }
  return quantity;
}

module.exports = { subtotalCents, percentOff, parseQuantity };
