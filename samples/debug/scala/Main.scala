// The entry point of the shelf: open this file and press F5 (Debug). Every suggested spot is a "break here"
// comment. The program argument is a quantity (how many items to check out), a word (a caught error), or
// `crash` (an exception nobody catches, which stops the debugger where it is thrown).
package shelf

def greeting(who: String): String =
  val line = s"Checking out the $who shelf." // break here: Step Into lands here, Step Out goes back
  line

@main def shelfDemo(args: String*): Unit =
  var limit = 6 // break here: a first plain breakpoint; Step Over, then Step Into greeting and checkout
  println(greeting("stationery"))
  args.headOption match
    case Some("crash") =>
      val missing = Map.empty[String, Int]
      println(missing("boom")) // a NoSuchElementException nobody catches
    case Some(text) =>
      Shelf.parseQuantity(text) match
        case Right(n)      => limit = n
        case Left(message) => println(message)
    case None => ()
  val (lines, total) = Shelf.checkout(Shelf.items, limit)
  lines.foreach(println)
  println(f"total $total%.2f")
  println(s"restocked ${Restock.run(3)}")
