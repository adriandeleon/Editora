"""Restocking on three named threads: the Threads view lists restock-1, restock-2 and restock-3."""

import threading


def run(workers: int) -> int:
    """Runs workers threads that each count up their share of 160, and returns the sum."""
    restocked = 0
    gate = threading.Lock()
    start = threading.Barrier(workers)  # every thread waits here until all have started, so all show in Threads

    def work() -> None:
        nonlocal restocked
        start.wait()
        for _ in range(160):
            with gate:
                restocked += 1  # break here: a hit-count breakpoint (this line runs 480 times; try 100 or % 50)

    threads = [threading.Thread(target=work, name=f"restock-{w}") for w in range(1, workers + 1)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return restocked
