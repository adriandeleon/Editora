;; A small Clojure sample for syntax highlighting and folding.
;;
;; Covers the ns form, docstrings, defn with multiple arities, a protocol
;; and a record, maps/vectors/sets, keywords, character literals, a regex
;; literal, quoting, threading macros, and a multi-line string.
(ns demo.shelf
  (:require [clojure.string :as str]))

(def max-items 0x40) ; hex literal

(defprotocol Item
  (item-name [this] "The item's display name."))

(defrecord Book [title pages]
  Item
  (item-name [_] title))

(defrecord Tool [label weight-kg]
  Item
  (item-name [_] label))

(defonce shelf (atom []))

(defn add!
  "Adds `item` to the shelf, refusing once it is full."
  [item]
  (when (>= (count @shelf) max-items)
    (throw (ex-info "no room" {:item (item-name item)})))
  (swap! shelf conj item))

(defn- describe
  ([item] (describe item \space))
  ([item sep]
   (cond
     (and (instance? Book item) (> (:pages item) 300))
     (str (item-name item) sep "(a long read)")
     :else (item-name item))))

(def ^:private tidy-re #"\s+")

(defn tidy [s]
  (-> s str/trim (str/replace tidy-re "-")))

(def banner
  "Shelf:
  items with \"quotes\" and a ; that is not a comment")

(defmacro with-shelf [& body]
  `(let [~'items @shelf] ~@body))

(defn -main [& _args]
  (add! (->Book "Dune" 412))
  (add! (map->Tool {:label "Hammer" :weight-kg 0.6}))
  (doseq [item @shelf]
    (println (describe item) #{:a :b} '(quoted list) \( \"))
  (with-shelf (println (count items) "items" (tidy "  a  b "))))
