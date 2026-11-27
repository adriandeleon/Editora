;; Sample Leiningen project for exercising Editora's Leiningen support. A project.clj is Clojure code, so the
;; actions popup shows the common lein tasks (run/test/check/compile/jar/uberjar/install/deps/clean) rather than
;; reading this file; the `greet` alias below runs only from Run custom…. Standalone: the repo's own build never
;; reads it. Needs `lein` on PATH; the first run fetches Clojure.
(defproject lein-demo "1.0.0"
  :description "A one-namespace Leiningen sample."
  :dependencies [[org.clojure/clojure "1.12.6"]]
  :main lein-demo.core
  ;; `lein greet` (type it into Run custom…): -main with an argument.
  :aliases {"greet" ["run" "Grace"]}
  :profiles {:uberjar {:aot :all}})
