// `mill app.test` (app.test in the popup's loaded tasks, or __.test) runs these munit tests: one passes, one
// fails on purpose and one is skipped on purpose, so every status has an example. Mill's test output stays in
// the Output console (the Test Results window does not read it yet).
package demo

class MainTest extends munit.FunSuite:
  test("greets by name") {
    assertEquals(greeting("Ada"), "Hello from Ada.")
  }

  test("fails on purpose") {
    assertEquals(greeting("Ada"), "Goodbye from Ada.") // this failure is the sample's red test
  }

  test("skipped on purpose".ignore) {
    fail("never runs")
  }
