// The shelf, in TypeScript on Node.js: open this file and press F5 (Debug). Node.js 22.18 or newer runs the .ts
// files as they are, under js-debug, which stops at your breakpoints. Every suggested spot is a "break here"
// comment. `npm start` (node src/main.ts) runs it outside Editora: a number argument sets how many items to
// check out, a word is a caught error, and `crash` throws a SyntaxError nobody catches.
import { restock } from "./restock.ts";
import { ITEMS, checkout, parseQuantity } from "./shelf.ts";

let limit = 6; // break here: a first plain breakpoint; Step Over, then Step Into checkout
const arg = process.argv[2];
if (arg === "crash") {
  // In a timer callback, outside the module's own code, so nothing catches it: the Uncaught Exceptions filter
  // stops on it (an error thrown by the module's top level would reject the module's promise instead).
  setTimeout(() => JSON.parse("{ not json"));
} else if (arg !== undefined) {
  try {
    limit = parseQuantity(arg);
  } catch (err) {
    console.log((err as Error).message); // break here: `err` in Variables
  }
}
const receipt = checkout(ITEMS, limit);
for (const line of receipt.lines) {
  console.log(line);
}
console.log(`total ${receipt.total.toFixed(2)}`);
console.log(`restocked ${await restock(3, 160)}`);
