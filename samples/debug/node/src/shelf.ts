// The shelf's items and the checkout loop: a watch, a logpoint, a lambda breakpoint and a caught error.
import { applyDiscount, discountFor } from "./pricing.ts";

/** What is on the shelf; expand one in Variables to see its fields. */
export interface Item {
  sku: string;
  name: string;
  quantity: number;
  price: number;
}

export const ITEMS: readonly Item[] = [
  { sku: "A-1", name: "Pencil", quantity: 12, price: 0.4 },
  { sku: "A-2", name: "Notebook", quantity: 3, price: 2.75 },
  { sku: "B-1", name: "Stapler", quantity: 1, price: 8.9 },
  { sku: "B-2", name: "Tape", quantity: 4, price: 1.2 },
  { sku: "C-1", name: "Marker", quantity: 10, price: 1.05 },
  { sku: "C-2", name: "Eraser", quantity: 25, price: 0.3 },
  { sku: "C-3", name: "Ruler", quantity: 2, price: 1.5 },
  { sku: "D-1", name: "Glue", quantity: 6, price: 2.1 },
];

export interface Receipt {
  lines: string[];
  byPrefix: Map<string, number>;
  total: number;
}

/** Totals up to limit items: total, lines and byPrefix change on every pass. */
export function checkout(shelf: readonly Item[], limit: number): Receipt {
  const lines: string[] = [];
  const byPrefix = new Map<string, number>();
  let total = 0;
  for (const item of shelf.slice(0, limit)) {
    const discount = discountFor(item); // break here: Step Into, then look at `discount` per item
    const price = applyDiscount(discount, item.price);
    const subtotal = price * item.quantity;
    const count = String(item.quantity).padStart(3);
    lines.push(`${item.sku} ${item.name.padEnd(9)} ${count} x ${price.toFixed(2)} = ${subtotal.toFixed(2)}`);
    const prefix = item.sku.charAt(0);
    byPrefix.set(prefix, (byPrefix.get(prefix) ?? 0) + item.quantity);
    total += subtotal; // break here: a watch on `total`, or a logpoint printing {item.name}: {total}
  }
  const cheap = shelf.filter((item) => item.price < 1).map((item) => item.name); // break here: a lambda breakpoint,
  // in the filter's arrow function, with the condition item.sku === "C-2"
  lines.push(`cheap: ${cheap.sort().join(", ")}  by prefix: ${[...byPrefix].map(([p, q]) => `${p}=${q}`).join(" ")}`);
  return { lines, byPrefix, total };
}

/** Turns "12" into 12; a word throws a RangeError, which main.ts catches. */
export function parseQuantity(text: string): number {
  const quantity = Number(text);
  if (!Number.isInteger(quantity) || quantity < 0) {
    throw new RangeError(`not a quantity: ${text}`); // break here: the Caught Exceptions filter stops on this throw
  }
  return quantity;
}
