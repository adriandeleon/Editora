// Vitest tests for Test Results and the test gutter ▶ (after `npm install`): one passes, one FAILS ON PURPOSE,
// one is SKIPPED ON PURPOSE and a test.each table runs once per row, so every status has an example. Run Test
// at Caret runs the test or describe block the caret is in. "Go: Related File" jumps to shelf.js.
import { describe, expect, test } from "vitest";
import { parseQuantity, percentOff, subtotalCents } from "./shelf.js";

describe("shelf", () => {
  test("totals a line", () => {
    expect(subtotalCents(12, 40)).toBe(480);
  });

  test("fails on purpose", () => {
    expect(percentOff(1000, 10)).toBe(950); // the sample's red test: 10% off 1000 is 900
  });

  test.skip("skipped on purpose", () => {
    expect(parseQuantity("12")).toBe(12);
  });

  test.each([
    { price: 1000, percent: 0, expected: 1000 },
    { price: 1000, percent: 15, expected: 850 },
    { price: 275, percent: 10, expected: 248 },
  ])("$price cents with $percent% off is $expected", ({ price, percent, expected }) => {
    expect(percentOff(price, percent)).toBe(expected);
  });
});

test("rejects a word as a quantity", () => {
  expect(() => parseQuantity("word")).toThrow(RangeError);
});
