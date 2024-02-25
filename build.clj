(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]))

(def lib 'net.clojars.jeff_evans/kc-repl)
(def version "1.1.0")
(def main 'us.jeffevans.kc-repl.java-main)

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      (bb/run-tests)
      (bb/clean)
      (bb/uber)))

(defn uberjar "Just build the uberjar" [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/clean)
      (bb/uber)))