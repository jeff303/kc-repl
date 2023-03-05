(ns us.jeffevans.kc-repl.type-handlers.avro-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [us.jeffevans.kc-repl :as kcr]
    [us.jeffevans.kc-repl.test-common :as tc]
    [us.jeffevans.kc-repl.test-containers :as containers])
  (:import (io.confluent.kafka.schemaregistry.client CachedSchemaRegistryClient)
           (io.confluent.kafka.serializers AbstractKafkaSchemaSerDe AbstractKafkaSchemaSerDeConfig KafkaAvroSerializer)
           (java.util Properties)
           (kafka.server KafkaConfig)
           (org.apache.avro Schema$Parser)
           (org.apache.avro.generic GenericData GenericData$Record)
           (org.apache.kafka.clients.admin NewTopic)
           (org.apache.kafka.clients.consumer ConsumerConfig ConsumerRecord)
           (org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord)
           (org.testcontainers.containers Network)))


(def ^:private ^:const avro-data-topic "avro-data")

(defn test-avro-data-fixture [f]
  (with-open [schema-reader (io/input-stream (io/resource "sensor-reading.avsc"))]
    (let [sr-client (CachedSchemaRegistryClient. (containers/zk-internal-url) 10)
          topic-nm       "sensor-readings"
          schema         (doto (Schema$Parser.)
                           (.parse schema-reader))
          producer-props (assoc tc/*kafka-common-props*
                           ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG (.getName KafkaAvroSerializer))
          producer       (KafkaProducer. producer-props)]
      (.createTopics tc/*kafka-admin* [(NewTopic. "sensor-readings" 2 (short 1))])
      (.register sr-client (str topic-nm "-value") schema true)
      (doseq [{did ::deviceId, r ::reading, ts ::timestamp} [{::deviceId 1, ::reading 10.0, ::timestamp 1676231420000}]]
        (let [record (doto (GenericData$Record. schema)
                       (.put "deviceId" did)
                       (.put "reading" r)
                       (.put "timestamp" ts))]
          (.send producer (ProducerRecord. topic-nm
                                           (int 0)
                                           ts
                                           nil
                                           record
                                           []))))
      (f))))

(defonce testing-network (Network/newNetwork))

(use-fixtures :once (partial tc/kafka-with-schema-registry-fixture testing-network)
                    test-avro-data-fixture)

;; need one of these per test since they might run in parallel
(use-fixtures :each tc/kcr-client-fixture)

(deftest sr-test
  (is (= 1 1)))
