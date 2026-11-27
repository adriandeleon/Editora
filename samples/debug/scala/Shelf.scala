// What is on the shelf, and the checkout loop.
package shelf

import scala.collection.mutable

// One kind of item; expand one in Variables to see its fields.
final case class Item(sku: String, name: String, quantity: Int, price: Double)

object Shelf:
  val items: List[Item] = List(
    Item("A-1", "Pencil", 12, 0.40),
    Item("A-2", "Notebook", 3, 2.75),
    Item("B-1", "Stapler", 1, 8.90),
    Item("B-2", "Tape", 4, 1.20),
    Item("C-1", "Marker", 10, 1.05),
    Item("C-2", "Eraser", 25, 0.30),
    Item("C-3", "Ruler", 2, 1.50),
    Item("D-1", "Glue", 6, 2.10)
  )

  // Totals up to limit items: total, lines and byPrefix change on every pass.
  def checkout(shelf: List[Item], limit: Int): (List[String], Double) =
    val lines = mutable.ListBuffer.empty[String]
    val byPrefix = mutable.TreeMap.empty[Char, Int]
    var total = 0.0
    val it = shelf.take(limit).iterator
    while it.hasNext do
      val item = it.next()
      val discount = Pricing.discountFor(item) // break here: Step Into, then look at `discount` per item
      val price = Pricing.apply(discount, item.price)
      val subtotal = price * item.quantity
      lines += f"${item.sku}%-4s ${item.name}%-9s ${item.quantity}%3d x $price%5.2f = $subtotal%7.2f"
      byPrefix(item.sku.head) = byPrefix.getOrElse(item.sku.head, 0) + item.quantity
      total += subtotal // break here: a watch on `total`; `byPrefix` grows on every pass
    val cheap = shelf.filter(item => item.price < 1.0).map(_.name).sorted // break here: inside the lambda (Shelf { lambda })
    lines += s"cheap: ${cheap.mkString(", ")}  by prefix: ${byPrefix.mkString(", ")}"
    (lines.toList, total)

  // Turns "12" into 12; a word is a NumberFormatException, caught here and returned as a message.
  def parseQuantity(text: String): Either[String, Int] =
    try Right(text.toInt)
    catch
      case err: NumberFormatException =>
        Left(s"not a quantity: $text (${err.getMessage})") // break here: `err` in Variables, or the Caught Exceptions filter
