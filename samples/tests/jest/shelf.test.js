// Jest tests for Test Results and the test gutter ▶ (after `npm install`): one passes, one FAILS ON PURPOSE,
// one is SKIPPED ON PURPOSE and a test.each table runs once per row, so every status has an example. Run Test
// at Caret runs the test or describe block the caret is in. "Go: Related File" jumps to shelf.js.
const { parseQuantity, percentOff, subtotalCents } = require("./shelf.js");

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
    [1000, 0, 1000],
    [1000, 15, 850],
    [275, 10, 248],
  ])("%i cents with %i%% off is %i", (price, percent, expected) => {
    expect(percentOff(price, percent)).toBe(expected);
  });
});

test("rejects a word as a quantity", () => {
  expect(() => parseQuantity("word")).toThrow(RangeError);
});
