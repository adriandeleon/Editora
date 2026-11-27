// Green node:test tests, for Debug Test at Caret: put the caret in one and debug that test alone.
// `npm test` (node --test) runs them all.
import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { applyDiscount, discountFor } from "./pricing.ts";
import { restock } from "./restock.ts";
import { ITEMS, checkout, parseQuantity } from "./shelf.ts";

describe("checkout", () => {
  test("totals the first two items", () => {
    const receipt = checkout(ITEMS, 2); // break here: Step Into checkout from a test
    assert.equal(receipt.total.toFixed(2), "12.35");
    assert.equal(receipt.lines.length, 3);
  });

  test("counts quantities by sku prefix", () => {
    const receipt = checkout(ITEMS, ITEMS.length);
    assert.deepEqual([...receipt.byPrefix], [["A", 15], ["B", 5], ["C", 37], ["D", 6]]);
  });
});

describe("pricing", () => {
  test("stacks the discounts of a big item", () => {
    const pencil = ITEMS[0]!;
    assert.equal(applyDiscount(discountFor(pencil), 100), 85.5);
  });

  test("leaves a cheap small item alone", () => {
    assert.equal(discountFor({ sku: "Z-1", name: "Clip", quantity: 1, price: 0.1 }), undefined);
  });
});

test("parseQuantity rejects a word", () => {
  assert.equal(parseQuantity("12"), 12);
  assert.throws(() => parseQuantity("word"), RangeError);
});

test("restock counts every worker's share", async () => {
  assert.equal(await restock(3, 160), 480);
});
