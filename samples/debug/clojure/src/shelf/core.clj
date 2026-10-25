(ns shelf.core
  "The shelf: a vector of items, a checkout loop, and a parse with a caught exception."
  (:require [shelf.pricing :as pricing]))

(def items
  "What is on the shelf. Each item is a map; expand one in Variables to see its keys and values."
  [{:sku "A-1" :name "Pencil" :quantity 12 :price 0.40}
   {:sku "A-2" :name "Notebook" :quantity 3 :price 2.75}
   {:sku "B-1" :name "Stapler" :quantity 1 :price 8.90}
   {:sku "B-2" :name "Tape" :quantity 4 :price 1.20}
   {:sku "C-1" :name "Marker" :quantity 10 :price 1.05}
   {:sku "C-2" :name "Eraser" :quantity 25 :price 0.30}
   {:sku "C-3" :name "Ruler" :quantity 2 :price 1.50}
   {:sku "D-1" :name "Glue" :quantity 6 :price 2.10}])

(defn checkout
  "Totals up to `limit` items: a `loop` whose bindings (`total`, `lines`, `by-prefix`) change on every
  `recur`. A local with a dash shows under its JVM name (`by_prefix`)."
  [items limit]
  (loop [todo (take limit items)
         lines []
         by-prefix (sorted-map)
         total 0.0]
    (if-let [item (first todo)]
      (let [discount (pricing/discount-for item) ; break here: Step Into, then look at `discount` per item
            price (pricing/apply-discount discount (:price item))
            subtotal (* price (:quantity item))
            line (format "%-4s %-9s %3d x %5.2f = %7.2f"
                         (:sku item) (:name item) (:quantity item) price subtotal)]
        (recur (rest todo)
               (conj lines line)
               (update by-prefix (subs (:sku item) 0 1) (fnil + 0) (:quantity item))
               (+ total subtotal))) ; break here: `total` grows on every pass (a watch on it)
      (let [cheap (->> items
                       (filter (fn [item] (< (:price item) 1.0))) ; break here: a breakpoint inside an anonymous fn
                       (map :name)
                       sort
                       vec)]
        {:lines (conj lines (str "cheap: " cheap "  by prefix: " by-prefix))
         :total total}))))

(defn parse-quantity
  "Parses a quantity, falling back to 7. The caught exception is the \"Caught Exceptions\" filter's test."
  [text]
  (try
    (Long/parseLong text)
    (catch NumberFormatException err
      (println "not a number:" (ex-message err)) ; break here: `err` is in Variables
      7)))
