package com.example.shelf;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Restocks items on named worker threads ({@code restock-1}, {@code restock-2}, …). A breakpoint
 * inside the worker loop is hit on each thread in turn: check the Threads view, suspend-all vs
 * suspend-one, and a hit-count breakpoint (the line runs {@code ROUNDS × items} times in all).
 */
final class Restocker {

    static final int ROUNDS = 20;

    private Restocker() {}

    static Map<String, Integer> restock(List<Item> items, int workers) throws InterruptedException {
        var stock = new ConcurrentHashMap<String, Integer>();
        var factory = Thread.ofPlatform().name("restock-", 1).factory();
        try (var pool = Executors.newThreadPerTaskExecutor(factory)) {
            for (int w = 0; w < workers; w++) {
                int worker = w;
                pool.submit(() -> {
                    for (int round = 1; round <= ROUNDS; round++) {
                        for (int i = worker; i < items.size(); i += workers) {
                            Item item = items.get(i);
                            stock.merge(item.sku(), 1, Integer::sum); // break here: hit count 50, or condition round == ROUNDS
                        }
                        Thread.sleep(2);
                    }
                    return worker;
                });
            }
        } // close() waits for every worker
        return new TreeMap<>(stock);
    }
}
