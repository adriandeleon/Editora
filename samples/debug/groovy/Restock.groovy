// Three named worker threads (restock-1…3) for the Threads view, each running a closure.
import java.util.concurrent.atomic.AtomicInteger

class Restock {

    /** Runs workers threads that each count up their share, and returns how many they restocked in all. */
    static int run(int workers) {
        def restocked = new AtomicInteger()
        def threads = (1..workers).collect { w ->
            Thread.start("restock-$w") {
                160.times { round ->
                    restocked.incrementAndGet() // break here: each thread in Threads (frame `Restock.run { closure }`); runs 480 times
                    if (round % 40 == 39) {
                        Thread.sleep(2) // so the three workers overlap
                    }
                }
            }
        }
        threads*.join()
        restocked.get()
    }
}
