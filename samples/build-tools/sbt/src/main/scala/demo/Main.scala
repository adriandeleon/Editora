// The sbt sample's program: `sbt run` (or Run on this file, `sbt "runMain demo.hello"`) prints one line.
package demo

@main def hello(): Unit =
  println(greeting("the sbt sample project"))
