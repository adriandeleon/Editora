(ns lein-demo.core
  "The Leiningen sample's program: `lein run` (or Run on this file, `lein run -m lein-demo.core`) prints one
  line; core_test.clj checks `greeting`."
  (:gen-class))

(defn greeting
  "The line -main prints."
  [who]
  (str "Hello from " who "."))

(defn -main
  [& args]
  (println (greeting (or (first args) "the Leiningen sample project"))))
