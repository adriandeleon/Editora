package main

import "sync"

// restock runs workers goroutines that each count up their share: the Threads view lists them.
func restock(workers int) int {
	var wg sync.WaitGroup
	var mu sync.Mutex
	restocked := 0
	for w := range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 160 {
				mu.Lock()
				restocked++ // break here: a hit-count breakpoint (this line runs 480 times), and each goroutine in Threads
				mu.Unlock()
			}
			_ = w
		}()
	}
	wg.Wait()
	return restocked
}
