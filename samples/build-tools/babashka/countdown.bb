#!/usr/bin/env bb
;; A small Babashka script: Run (the gutter ▶) runs it as `bb countdown.bb`, and the `countdown` task in bb.edn
;; runs it with 5. Babashka is a native program, so Debug says it can't debug it and runs nothing.
(require '[clojure.string :as str])

(defn countdown
  "n, n-1, …, 1."
  [n]
  (range n 0 -1))

(let [n (or (some-> (first *command-line-args*) parse-long) 3)]
  (println (str/join " " (countdown n)) "liftoff"))
