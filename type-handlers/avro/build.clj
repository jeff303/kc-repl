(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]))

(def lib 'net.clojars.jeff_evans/kc-repl-type-handler-avro)
(def version "1.0.0")

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn print-classpath []
  (let [classpath (System/getProperty "java.class.path")]
    (println "Current CLASSPATH:")
    (println classpath)))


(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (-> opts
      (assoc :lib lib :version version :transitive true)
      (bb/run-tests)
      (bb/clean)
      (bb/uber)))

(defn uberjar "Just build the uberjar" [opts]
      (-> opts
          (assoc :lib lib :version version)
          (bb/clean)
          (bb/uber)))