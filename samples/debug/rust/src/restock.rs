use std::sync::{Arc, Mutex};
use std::thread;

/// Runs `workers` named threads that each count up their share: the Threads view lists them.
pub fn restock(workers: usize) -> u32 {
    let restocked = Arc::new(Mutex::new(0u32));
    let handles: Vec<_> = (1..=workers)
        .map(|n| {
            let restocked = Arc::clone(&restocked);
            thread::Builder::new()
                .name(format!("restock-{n}"))
                .spawn(move || {
                    for _ in 0..160 {
                        *restocked.lock().unwrap() += 1; // break here: a hit-count breakpoint (480 hits)
                    }
                })
                .unwrap()
        })
        .collect();
    for handle in handles {
        handle.join().unwrap();
    }
    let total = *restocked.lock().unwrap();
    total
}

#[cfg(test)]
mod tests {
    #[test]
    fn three_workers_restock_480() {
        assert_eq!(super::restock(3), 480);
    }
}
