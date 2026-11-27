// Green Deno.test tests, for Debug Test at Caret: Deno filters by top-level test name, so each is a
// Deno.test of its own. `deno task test` (deno test) runs them all; @std/assert is fetched on the first run.
import { assertAlmostEquals, assertEquals, assertThrows } from "@std/assert";
import { apply, discountFor } from "./pricing.ts";
import { checkout, ITEMS, parseLimit, restock } from "./main.ts";

Deno.test("checkout totals the first two items", () => {
  const { total, byPrefix } = checkout(ITEMS, 2); // break here: Step Into checkout from a test
  assertAlmostEquals(total, 12.354);
  assertEquals(byPrefix, { A: 15 });
});

Deno.test("a stacked discount applies both parts", () => {
  assertEquals(apply(discountFor(12, 100), 100), 85.5);
});

Deno.test({
  name: "parseLimit rejects a word",
  fn() {
    assertEquals(parseLimit("3"), 3);
    assertThrows(() => parseLimit("word"), TypeError);
  },
});

Deno.test("restock counts every task's share", async () => {
  assertEquals(await restock(3, 160), 480);
});
