(ns shelf.pricing
  "Discounts as plain maps (`:kind` :none, :percent, :flat or :stacked) and the function that applies them.")

(defn discount-for
  "The discount an item gets. Pause on the `cond` and compare `sku` and `quantity` across the eight calls
  (the spot for a conditional breakpoint on `(= sku \"B-2\")`, once this debugger evaluates conditions)."
  [item]
  (let [sku (:sku item)
        quantity (:quantity item)]
    (cond ; break here: hit once per item
      (>= quantity 20) {:kind :percent :pct 15}
      (= sku "B-2") {:kind :stacked
                     :first {:kind :percent :pct 10}
                     :then {:kind :flat :amount 0.20}}
      (.startsWith ^String sku "C") {:kind :flat :amount 0.05}
      :else {:kind :none})))

(defn apply-discount
  "The price after `discount`. A `:stacked` one applies its two parts in turn by calling itself, so the
  call stack shows nested `shelf.pricing/apply-discount` frames, each with its own `discount` and `price`."
  [discount price]
  (case (:kind discount)
    :none price
    :percent (* price (- 1 (/ (:pct discount) 100.0)))
    :flat (max 0.0 (- price (:amount discount)))
    :stacked (apply-discount (:then discount) ; break here: two frames deep for "Tape"
                             (apply-discount (:first discount) price))))
