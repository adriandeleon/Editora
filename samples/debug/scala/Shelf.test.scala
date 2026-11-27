// munit tests for the shelf, all green: `scala-cli test .` (or `test` in the Scala CLI popup) runs them, and
// their results print in the Output console. munit comes from the `//> using test.dep` in project.scala.
package shelf

class ShelfSuite extends munit.FunSuite:
  test("checkout stops at the limit") {
    val (lines, total) = Shelf.checkout(Shelf.items, 3)
    assertEquals(lines.size, 4) // one line per item, plus the summary line
    assertEqualsDouble(total, 4.104 + 8.25 + 7.565, 0.001) // Pencil (stacked) + Notebook + Stapler (15%)
  }

  test("cheap items are listed sorted") {
    val (lines, _) = Shelf.checkout(Shelf.items, 0)
    assertEquals(lines, List("cheap: Eraser, Pencil  by prefix: "))
  }

  test("a stacked discount applies both parts") {
    val stacked = Pricing.discountFor(Shelf.items.head)
    assertEqualsDouble(Pricing.apply(stacked, 100.0), 85.5, 1e-9)
  }

  test("a word is not a quantity") {
    assertEquals(Shelf.parseQuantity("12"), Right(12))
    assert(Shelf.parseQuantity("dozen").isLeft)
  }

  test("three workers restock 480") {
    assertEquals(Restock.run(3), 480)
  }
