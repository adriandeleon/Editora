(ns shelf.core-test
  "clojure.test suite: `clojure -M:test` runs it (the Clojure CLI toolbar button lists the :test alias).
  Every test is green; a breakpoint is marked in the first."
  (:require [clojure.test :refer [deftest is testing]]
            [shelf.core :as shelf]
            [shelf.pricing :as pricing]
            [shelf.restock :as restock]))

(deftest checkout-stops-at-the-limit
  (let [{:keys [lines total]} (shelf/checkout shelf/items 3)] ; break here: Step Into checkout from a test
    (testing "one line per item, plus the summary line"
      (is (= 4 (count lines))))
    (testing "Pencil + Notebook + Stapler at list price: 4.80 + 8.25 + 8.90"
      (is (< 21.94 total 21.96)))))

(deftest cheap-items-are-listed-sorted
  (let [{:keys [lines]} (shelf/checkout shelf/items 0)]
    (is (= ["cheap: [\"Eraser\" \"Pencil\"]  by prefix: {}"] lines))))

(deftest discounts-by-kind
  (is (= {:kind :percent :pct 15} (pricing/discount-for {:sku "C-2" :quantity 25})))
  (is (= :stacked (:kind (pricing/discount-for {:sku "B-2" :quantity 4}))))
  (is (= {:kind :flat :amount 0.05} (pricing/discount-for {:sku "C-3" :quantity 2})))
  (is (= {:kind :none} (pricing/discount-for {:sku "A-1" :quantity 12}))))

(deftest stacked-discount-applies-both-parts
  (let [discount (pricing/discount-for {:sku "B-2" :quantity 4})]
    (is (< 0.879 (pricing/apply-discount discount 1.20) 0.881)))
  (is (= 0.0 (pricing/apply-discount {:kind :flat :amount 1.0} 0.50))))

(deftest parse-quantity-falls-back
  (is (= 42 (shelf/parse-quantity "42")))
  (is (= 7 (shelf/parse-quantity "seven"))))

(deftest restock-adds-thirty-per-item
  (let [stock (restock/restock shelf/items 3)]
    (is (= 8 (count stock)))
    (is (= ["A-1" "A-2" "B-1" "B-2" "C-1" "C-2" "C-3" "D-1"] (keys stock)))
    (is (= 42 (get stock "A-1")))
    (is (= 55 (get stock "C-2")))))
