// `sbt test` from the sbt popup runs these munit tests: one passes, one fails on purpose and one is skipped on
// purpose, plus a table of two, so every status has an example. sbt's test output stays in the Output console
// (the Test Results window does not read it yet). "Go: Related File" jumps between this file and Greeting.scala.
package demo

class GreetingTest extends munit.FunSuite:
  test("greets by name") {
    assertEquals(greeting("Ada"), "Hello from Ada.")
  }

  // A table: one test per name.
  for who <- List("Ada", "Grace") do
    test(s"greets $who") {
      assert(greeting(who).contains(who))
    }

  test("fails on purpose") {
    assertEquals(greeting("Ada"), "Goodbye from Ada.") // this failure is the sample's red test
  }

  test("skipped on purpose".ignore) {
    fail("never runs")
  }
