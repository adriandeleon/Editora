#include <mutex>
#include <thread>

#include "shelf.hpp"

// Runs `workers` threads that each count up their share: the Threads view lists them.
int restock(int workers) {
    int restocked = 0;
    std::mutex lock;
    std::vector<std::thread> threads;
    for (int n = 0; n < workers; n++) {
        threads.emplace_back([&] {
            for (int i = 0; i < 160; i++) {
                std::lock_guard<std::mutex> guard(lock);
                restocked++; // break here: a hit-count breakpoint (480 hits)
            }
        });
    }
    for (std::thread &t : threads) {
        t.join();
    }
    return restocked;
}
