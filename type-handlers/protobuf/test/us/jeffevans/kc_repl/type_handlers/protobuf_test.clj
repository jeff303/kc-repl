(ns us.jeffevans.kc-repl.type-handlers.protobuf-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [us.jeffevans.kc-repl :as kcr]
            [us.jeffevans.kc-repl.test-common :as tc]
            [us.jeffevans.kc-repl.type-handlers.protobuf :as pb])
  (:import (org.apache.kafka.common.serialization ByteArraySerializer)
           (us.jeffevans.kc_repl.testdata SensorReadingOuterClass$SensorReading)
           (com.google.protobuf AbstractMessage MessageOrBuilder Timestamp)
           (io.confluent.kafka.schemaregistry.client CachedSchemaRegistryClient)
           (io.confluent.kafka.serializers.protobuf KafkaProtobufSerializer)
           (io.confluent.kafka.schemaregistry.protobuf ProtobufSchema)
           (io.confluent.kafka.serializers.protobuf KafkaProtobufSerializer)
           (org.apache.kafka.clients.admin NewTopic)
           (org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord RecordMetadata)))

(def ^:private dummy pb/dummy)

(def ^:const protobuf-data-topic "sensor-readings-protobuf")

;; unlike in Avro test, this timestamp will be in seconds for simplicity
(def test-data [{::deviceId 1, ::reading 10.0, ::timestamp 1676231420}
                {::deviceId 13, ::reading 17.9, ::timestamp 1676231438}])

(def ^:dynamic *start-offset* 0)

(defn- test-data->protobuf [{:keys [::deviceId ::reading ::timestamp]}]
  (-> (SensorReadingOuterClass$SensorReading/newBuilder)
      (.setDeviceId deviceId)
      (.setReading reading)
      (.setTimestamp (-> (Timestamp/newBuilder)
                         (.setSeconds timestamp)
                         (.build)))
      (.build)))

(defn- msg->byte-arr [^AbstractMessage msg]
  (.toByteArray msg))
(defn test-protobuf-data-fixture [use-sr? topic-nm f]
  (let [sr-client (CachedSchemaRegistryClient. tc/*schema-registry-url* 10)
        pb-records     (map test-data->protobuf test-data)
        schema         (ProtobufSchema. (-> (first pb-records)
                                            (.getDescriptorForType)))
        ser-cls        (if use-sr? KafkaProtobufSerializer ByteArraySerializer)
        record-val-fn  (if use-sr? identity msg->byte-arr)
        producer-props (cond-> (assoc tc/*kafka-common-props*
                                 ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG (.getName ser-cls)
                                 ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG (.getName ser-cls))
                               use-sr?
                               (assoc tc/schema-registry-url-prop tc/*schema-registry-url*))
        producer       (KafkaProducer. producer-props)]
    (let [existing-topics (-> (.listTopics tc/*kafka-admin*)
                              (.names)
                              (deref))]
      (when-not (contains? (into #{} existing-topics) topic-nm)
        (printf "topic %s does not exist; creating and populating it" topic-nm)
        (.createTopics tc/*kafka-admin* [(NewTopic. topic-nm 1 (short 1))])
        (when use-sr?
          (.register sr-client (str topic-nm "-value") schema true))))
    (let [start-offset (reduce (fn [min-offset test-record]
                                 (let [prod-rec (ProducerRecord. topic-nm
                                                                 (int 0)
                                                                 nil
                                                                 nil
                                                                 (record-val-fn test-record)
                                                                 [])
                                       ^RecordMetadata rec @(.send producer prod-rec)
                                       new-offset (.offset rec)]
                                   (log/debugf "inserted Protobuf test record at offset %d" new-offset)
                                   (min min-offset new-offset)))
                               Long/MAX_VALUE
                               pb-records)]
      (binding [*start-offset* start-offset
                tc/*kafka-common-props* (cond-> tc/*kafka-common-props*
                                                (not use-sr?)
                                                (dissoc tc/schema-registry-url-prop))]
        (f)))))

(use-fixtures :once tc/kafka-with-schema-registry-docker-compose-manual-fixture
              (partial test-protobuf-data-fixture true protobuf-data-topic))

;; need one of these per test since they might run in parallel
(use-fixtures :each tc/kcr-client-fixture)

(deftest protobuf-schema-registry-handling-test
  ;; require to force the type handler to be registered
  (testing "list-topics works as expected"
    (is (contains? (into #{} (kcr/list-topics tc/*kcr-client*)) protobuf-data-topic)))
  (testing "reading Protobuf data"
    (let [records (kcr/read-from tc/*kcr-client* protobuf-data-topic 0 *start-offset* (count test-data) "protobuf")]
      (is (= (map (partial reduce-kv
                           (fn [acc k* v*]
                             (let [k (name k*)
                                   v (case k "timestamp"
                                             {"seconds" v*}
                                             v*)]
                               (assoc acc k v)))
                           {})
                  test-data) records)))))

