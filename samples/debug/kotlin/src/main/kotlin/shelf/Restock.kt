// Three named worker threads (restock-1…3) for the Threads view: plain threads, no coroutines dependency.
package shelf

import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Runs [workers] threads that each count up their share, and returns how many they restocked in all. */
fun restock(workers: Int): Int {
    val restocked = AtomicInteger()
    val threads = (1..workers).map { w ->
        thread(name = "restock-$w") {
            repeat(160) { round ->
                restocked.incrementAndGet() // break here: each thread in Threads; this line runs 480 times
                if (round % 40 == 39) {
                    Thread.sleep(2) // so the three workers overlap
                }
            }
        }
    }
    threads.forEach { it.join() }
    return restocked.get()
}
