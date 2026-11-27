// Three named worker threads that each count up their share: the Threads view lists them.
package shelf

import java.util.concurrent.atomic.AtomicInteger

object Restock:
  def run(workers: Int): Int =
    val restocked = AtomicInteger()
    val threads = (1 to workers).map { w =>
      val work: Runnable = () =>
        for _ <- 1 to 160 do
          restocked.incrementAndGet() // break here: each restock-N thread in Threads (this line runs 480 times)
      Thread(work, s"restock-$w")
    }
    threads.foreach(_.start())
    threads.foreach(_.join())
    restocked.get
