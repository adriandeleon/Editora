// `npm test` runs these with Node's built-in runner, which prints TAP for the Test Results tool window: one
// test passes, one fails on purpose, and one is skipped, so every status has an example. "Go to
// Related File" jumps between this file and index.js.
const test = require("node:test");
const assert = require("node:assert/strict");
const { greeting } = require("./index.js");

test("greets by name", () => {
  assert.equal(greeting("Ada"), "Hello from Ada.");
});

test("fails on purpose", () => {
  assert.equal(greeting("Ada"), "Goodbye from Ada.", "this failure is the sample's red test");
});

test("skipped on purpose", { skip: "skipped on purpose" }, () => {});
