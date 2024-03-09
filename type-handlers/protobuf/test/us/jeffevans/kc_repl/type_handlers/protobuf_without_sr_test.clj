(ns us.jeffevans.kc-repl.type-handlers.protobuf-without-sr-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [us.jeffevans.kc-repl :as kcr]
            [us.jeffevans.kc-repl.test-common :as tc]
            [us.jeffevans.kc-repl.type-handlers.protobuf :as pb]
            [us.jeffevans.kc-repl.type-handlers.protobuf-test :as pbt])
  (:import (us.jeffevans.kc_repl.testdata SensorReadingOuterClass$SensorReading)))

(def ^:const protobuf-no-sr-data-topic "sensor-readings-protobuf-no-schema")
(use-fixtures :once tc/kafka-with-schema-registry-docker-compose-manual-fixture
              (partial pbt/test-protobuf-data-fixture false protobuf-no-sr-data-topic))

;; need one of these per test since they might run in parallel
(use-fixtures :each tc/kcr-client-fixture)


(deftest protobuf-with-proto-file-options-handling-test
  ;; require to force the type handler to be registered
  (testing "reading Protobuf data without Schema Registry"
    (let [c SensorReadingOuterClass$SensorReading]
      (log/infof "found class %s" c))
    (kcr/set-type-handler-config!
      tc/*kcr-client*
      "protobuf"
      pb/topic-name-to-message-class-config
      [protobuf-no-sr-data-topic SensorReadingOuterClass$SensorReading])
    (let [records (kcr/read-from tc/*kcr-client* protobuf-no-sr-data-topic 0 pbt/*start-offset* (count pbt/test-data) "protobuf")]
      (is (= (map (partial reduce-kv
                           (fn [acc k* v*]
                             (let [k (name k*)
                                   v (case k "timestamp"
                                             {"seconds" v*}
                                             v*)]
                               (assoc acc k v)))
                           {})
                  pbt/test-data) records)))))
