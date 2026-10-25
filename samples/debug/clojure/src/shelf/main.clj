(ns shelf.main
  "Entry point of the debugging demo. Run it with no arguments, or with one argument: a number
  (used as a quantity), any other word (a caught NumberFormatException), or `crash` (ends with
  an uncaught exception, exit code 1).

  Lines marked `break here` are the suggested breakpoints; each one exercises a different
  debugger feature. Editora starts the JVM with locals clearing disabled, so every `let` and
  `loop` binding stays visible in Variables until its function returns."
  (:require [shelf.core :as shelf]
            [shelf.restock :as restock]))

(defn greeting
  "A function to step into from `-main` (Step Into, then Step Out: the frame reads `shelf.main/greeting`)."
  []
  (str "Shelf demo" " (" (count shelf/items) " items)"))

(defn -main [& args]
  (let [limit 12]
    (println (greeting)) ; break here: a first stop; Variables shows `args` (nil or a seq) and `limit`
    (let [{:keys [lines total]} (shelf/checkout shelf/items limit)] ; break here: Step Into enters checkout
      (run! println lines)
      (println (format "total %.2f" total)))
    (println "restocked" (restock/restock shelf/items 3)) ; worker threads live in here
    (let [quantity (shelf/parse-quantity (or (first args) "seven"))] ; "seven" is not a number
      (println "quantity" quantity))
    (when (some #{"crash"} args)
      (throw (ex-info "crash requested" {:args (vec args)}))) ; uncaught: the Uncaught Exceptions stop lands here
    (shutdown-agents)))
