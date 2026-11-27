// The shelf, in TypeScript on Deno: deno.json makes this folder a Deno project, so Run and Debug use
// `deno run` (Debug adds --inspect-wait and js-debug attaches). Open this file and press F5; every suggested
// spot is a "break here" comment. `deno task start` runs it outside Editora: a number argument sets how
// many items to check out, a word is a caught error, and `crash` reads a missing file, uncaught.
import { apply, discountFor } from "./pricing.ts";

export type Item = { sku: string; name: string; quantity: number; price: number };

export const ITEMS: readonly Item[] = [
  { sku: "A-1", name: "Pencil", quantity: 12, price: 0.4 },
  { sku: "A-2", name: "Notebook", quantity: 3, price: 2.75 },
  { sku: "B-1", name: "Stapler", quantity: 1, price: 8.9 },
  { sku: "B-2", name: "Tape", quantity: 4, price: 1.2 },
  { sku: "C-1", name: "Marker", quantity: 10, price: 1.05 },
  { sku: "C-2", name: "Eraser", quantity: 25, price: 0.3 },
];

/** Totals up to limit items; `byPrefix` (a plain object) grows by one key per new prefix. */
export function checkout(shelf: readonly Item[], limit: number): { total: number; byPrefix: Record<string, number> } {
  const byPrefix: Record<string, number> = {};
  let total = 0;
  for (const item of shelf.slice(0, limit)) {
    const price = apply(discountFor(item.quantity, item.price), item.price); // break here: Step Into discountFor, then apply
    const prefix = item.sku.charAt(0);
    byPrefix[prefix] = (byPrefix[prefix] ?? 0) + item.quantity;
    total += price * item.quantity; // break here: a watch on `total`, a logpoint printing {item.sku}: {total},
    // or the condition item.sku === "C-2"
  }
  return { total, byPrefix };
}

/** Turns "12" into 12; a word throws a TypeError, which the caller below catches. */
export function parseLimit(text: string): number {
  const limit = Number.parseInt(text, 10);
  if (Number.isNaN(limit)) {
    throw new TypeError(`not a number of items: ${text}`); // break here: the Caught Exceptions filter stops on this throw
  }
  return limit;
}

/** Three async tasks restocking in turns: each await lets another run, and the call stack shows the await. */
export async function restock(tasks: number, rounds: number): Promise<number> {
  let restocked = 0;
  const one = async (task: number) => {
    for (let round = 0; round < rounds; round++) {
      restocked += 1; // break here: a hit-count breakpoint (this line runs 480 times, 160 per task); `task` says whose turn it is
      if (round % 40 === 0) await new Promise((wake) => setTimeout(wake, task));
    }
  };
  await Promise.all(Array.from({ length: tasks }, (_, i) => one(i + 1)));
  return restocked;
}

if (import.meta.main) {
  let limit = ITEMS.length; // break here: a first plain breakpoint; Step Over, then Step Into checkout
  const [arg] = Deno.args;
  if (arg === "crash") {
    Deno.readTextFileSync("missing.json"); // Deno.errors.NotFound, uncaught: only the Caught Exceptions filter stops on it
  } else if (arg !== undefined) {
    try {
      limit = parseLimit(arg);
    } catch (err) {
      console.log((err as Error).message); // break here: `err` in Variables
    }
  }
  const { total, byPrefix } = checkout(ITEMS, limit);
  console.log(`total ${total.toFixed(2)}`, byPrefix);
  console.log(`restocked ${await restock(3, 160)}`);
}
