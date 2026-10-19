/**
 * A small Scala sample for syntax highlighting and folding.
 *
 * Covers Scaladoc, Scala 3 optional braces with `end` markers, an enum, a
 * sealed trait with case classes, pattern matching, string interpolation, a
 * character literal, a Scala 2 brace-style object and an extension method.
 */
package demo

import scala.collection.mutable

val MaxItems = 0x40 // hex literal

sealed trait Item:
  def name: String

case class Book(name: String, pages: Int) extends Item
case class Tool(name: String, weightKg: Double) extends Item

enum Size:
  case Small, Medium, Large

class ShelfFull(message: String) extends Exception(message)

class Shelf:
  private val items = mutable.ListBuffer.empty[Item]

  def size: Int = items.size

  def add(item: Item): Unit =
    if items.size >= MaxItems then
      throw ShelfFull(s"no room for ${item.name}")
    items += item
  end add

  def describe(item: Item): String = item match
    case Book(n, p) if p > 300 => s"$n (a long read)"
    case Book(n, _)            => n
    case Tool(n, kg)           => f"$n%s, $kg%.1f kg"
end Shelf

extension (s: Size)
  def label: String = s.toString.toLowerCase

/* A block comment, then Scala 2 style: braces and a char literal. */
object Legacy {
  val separator: Char = '\t'
  def tidy(name: String): String = {
    name.trim.replace(' ', '-')
  }
}

@main def run(): Unit =
  val shelf = Shelf()
  shelf.add(Book("Dune", 412))
  shelf.add(Tool("Hammer", 0.6))
  for item <- List(Book("Dune", 412), Tool("Hammer", 0.6)) do
    println(shelf.describe(item) + Legacy.separator + Size.Small.label)
  println(s"${shelf.size} items, \"done\"\n")
