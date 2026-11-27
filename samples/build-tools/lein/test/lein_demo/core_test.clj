(ns lein-demo.core-test
  "`lein test` from the Leiningen popup runs these: one test passes and one fails on purpose (clojure.test has
  no skip, so there is no skipped one). The output stays in the Output console (the Test Results window does
  not read it). \"Go: Related File\" jumps between this file and core.clj."
  (:require [clojure.test :refer [deftest is are]]
            [lein-demo.core :refer [greeting]]))

(deftest greets-by-name
  (is (= "Hello from Ada." (greeting "Ada"))))

;; A table: one assertion per row.
(deftest greets-everyone
  (are [who] (.contains (greeting who) who)
    "Ada"
    "Grace"))

(deftest fails-on-purpose
  (is (= "Goodbye from Ada." (greeting "Ada")) "this failure is the sample's red test"))
