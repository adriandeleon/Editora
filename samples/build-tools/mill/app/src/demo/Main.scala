// The Mill sample's program: `mill app.run` (or Run on this file) prints one line.
package demo

def greeting(who: String): String = s"Hello from $who."

@main def hello(): Unit =
  println(greeting("the Mill sample project"))
