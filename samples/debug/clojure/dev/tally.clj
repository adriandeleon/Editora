;; A script, not a namespace with a -main: Run (gutter ▶) loads it with `clojure -M dev/tally.clj` from
;; the project folder, so `src` is on the classpath and `shelf.core` can be required. Its top-level
;; forms run in order; Debug stops at a breakpoint on any of them, and the frames of a script loaded
;; by path are named by the path itself.
(require '[shelf.core :as shelf])

(def wanted ["A-1" "C-2" "Z-9"])

(defn find-item
  "The item with `sku`, or nil."
  [sku]
  (some #(when (= (:sku %) sku) %) shelf/items)) ; break here: `sku` per call; `%` is the item being tested

(doseq [sku wanted]
  (let [item (find-item sku)] ; break here: `item` is nil on the third pass ("Z-9")
    (println sku (if item (:name item) "missing"))))

(println "cheapest:" (:name (apply min-key :price shelf/items)))
