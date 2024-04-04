(ns us.jeffevans.kc-repl.type-handlers.avro-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [clojure.tools.logging :as log]
    [us.jeffevans.kc-repl :as kcr]
    [us.jeffevans.kc-repl.test-common :as tc]
    [us.jeffevans.kc-repl.type-handlers.avro :as avro])
  (:import (io.confluent.kafka.schemaregistry.avro AvroSchema)
           (io.confluent.kafka.schemaregistry.client CachedSchemaRegistryClient)
           (io.confluent.kafka.serializers KafkaAvroSerializer)
           (org.apache.avro Schema$Parser)
           (org.apache.avro.generic GenericData$Record)
           (org.apache.kafka.clients.admin NewTopic)
           (org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord)))
(def ^:private ^:const avro-data-topic "sensor-readings")

(def ^:dynamic *start-offset* 0)

(def test-data [{::deviceId 1, ::reading 10.0, ::timestamp 1676231420000,
                 ::deviceMetadata {"category" "scientific", "mfrName" "acme"}}
                {::deviceId 13, ::reading 17.9, ::timestamp 1676231438000,
                 ::deviceMetadata {"category" "consumer", "mfrName" "Aerotyne International"}}])

(defn test-avro-data-fixture [f]
  (with-open [schema-reader (io/input-stream (io/resource "sensor-reading.avsc"))]
    (let [sr-client (CachedSchemaRegistryClient. tc/*schema-registry-url* 10)
          topic-nm       avro-data-topic
          parser         (Schema$Parser.)
          schema         (.parse parser schema-reader)
          producer-props (assoc tc/*kafka-common-props*
                           ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG (.getName KafkaAvroSerializer)
                           ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG (.getName KafkaAvroSerializer)
                           "schema.registry.url" tc/*schema-registry-url*)
          producer       (KafkaProducer. producer-props)]
      (.createTopics tc/*kafka-admin* [(NewTopic. "sensor-readings" 1 (short 1))])
      (.register sr-client (str topic-nm "-value") (AvroSchema. schema) true)
      (let [start-offset (reduce (fn [min-offset {did ::deviceId, r ::reading, ts ::timestamp,
                                                  dmd ::deviceMetadata}]
                                   (let [dMdSch     (-> schema
                                                        (.getField "deviceMetadata")
                                                        .schema)
                                         deviceMd   (doto (GenericData$Record. dMdSch)
                                                      (.put "mfrName" (get dmd "mfrName"))
                                                      (.put "category" (get dmd "category")))
                                         record     (doto (GenericData$Record. schema)
                                                      (.put "deviceId" did)
                                                      (.put "reading" r)
                                                      (.put "timestamp" ts)
                                                      (.put "deviceMetadata" deviceMd))
                                         record-md @(.send producer (ProducerRecord. topic-nm
                                                                                     nil
                                                                                     nil
                                                                                     nil
                                                                                     record
                                                                                     []))
                                         new-offset (.offset record-md)]
                                     (log/debugf "inserted Avro test record at offset %d" new-offset)
                                     (min min-offset new-offset)))
                                 Long/MAX_VALUE
                                 test-data)]
        (binding [*start-offset* start-offset]
          (f))))))

(use-fixtures :once tc/kafka-with-schema-registry-docker-compose-manual-fixture
              test-avro-data-fixture)

;; need one of these per test since they might run in parallel
(use-fixtures :each tc/kcr-client-fixture)

(deftest avro-handling-testc
  ;; require to force the type handler to be registered
  (require '[us.jeffevans.kc-repl.type-handlers.avro])
  (testing "list-topics works as expected"
    (is (contains? (into #{} (kcr/list-topics tc/*kcr-client*)) avro-data-topic)))
  (testing "reading Avro data"
    (let [records (kcr/read-from tc/*kcr-client* avro-data-topic 0 *start-offset* (count test-data) "avro")]
      (is (= (map (partial reduce-kv (fn [acc k v]
                                       (assoc acc (name k) v)) {}) test-data) records)))))
