(ns shelf.restock
  "Restocking on named worker threads: the Threads view, and what one paused thread does to the others.")

(defn- restock-one
  "Adds stock over `rounds` rounds. With 8 items and 20 rounds the marked line runs 160 times, spread over
  the workers (the spot for a hit-count breakpoint, once this debugger counts hits)."
  [item rounds]
  (loop [round 0
         stock (long (:quantity item))]
    (if (< round rounds)
      (recur (inc round) (+ stock (if (even? round) 2 1))) ; break here: Threads shows which `restock-N` stopped
      stock)))

(defn restock
  "Restocks every item on `workers` threads named `restock-1`, `restock-2`, …; returns sku -> stock, sorted."
  [items workers]
  (let [results (atom {})
        per-thread (long (Math/ceil (/ (count items) (double workers))))
        groups (partition-all per-thread items)
        threads (doall
                 (map-indexed
                  (fn [i group]
                    (Thread. ^Runnable (fn []
                                         (doseq [item group] ; break here: one worker pauses, the others run on
                                           (swap! results assoc (:sku item) (restock-one item 20))))
                             (str "restock-" (inc i))))
                  groups))]
    (run! #(.start ^Thread %) threads)
    (run! #(.join ^Thread %) threads)
    (into (sorted-map) @results)))
