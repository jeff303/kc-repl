(ns us.jeffevans.kc-repl.test-common
  "Fixtures and related utils/constants for use in kc-repl tests"
  (:require [clojure.data.json :as json]
            [us.jeffevans.kc-repl :as kcr])
  (:import (org.testcontainers.containers KafkaContainer)
           (org.apache.kafka.clients.admin Admin NewTopic)
           (org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord)
           (clojure.lang IPersistentMap)
           (org.testcontainers.utility DockerImageName)
           (org.apache.kafka.clients.consumer ConsumerConfig)
           (org.apache.kafka.common.header.internals RecordHeader)
           (org.apache.kafka.common.serialization ByteArrayDeserializer)
           (java.math MathContext)
           (java.nio.charset StandardCharsets)))


(def ^:private ^:const string-serializer "org.apache.kafka.common.serialization.StringSerializer")
(def ^:private ^:const byte-array-serializer "org.apache.kafka.common.serialization.ByteArraySerializer")
(def ^:private ^:const string-deserializer "org.apache.kafka.common.serialization.StringDeserializer")
(def ^:private ^:const cp-kafka-image-version "7.1.0")

(def ^:private ^:const test-topic
  "Simple, single-partition test topic with JSON messages like {\"foo\": 0}, {\"foo\": 1}, etc."
  "test-topic")

(def ^:const test-poll-size
  "The number of records to poll at once for testing purposes"
  10)

(def ^:private ^:dynamic ^KafkaContainer *kafka-container* nil)
(def ^:private ^:dynamic ^Admin *kafka-admin* nil)
(def ^:private ^:dynamic ^KafkaProducer *kafka-producer* nil)
(def ^:private ^:dynamic ^IPersistentMap *kafka-common-props* nil)
(def ^:dynamic *kcr-client* nil)

(defn kafka-container-fixture
  "Fixture that creates the Kafka broker testcontainer, and binds an admin and producer dynamic var pointing to it.
  These are all closed upon suite completion."
  [f]
  (with-open [container (-> (format "confluentinc/cp-kafka:%s" cp-kafka-image-version)
                            DockerImageName/parse
                            (KafkaContainer.)
                            ;; https://github.com/testcontainers/testcontainers-java/issues/1816#issuecomment-529992060
                            (.withEnv "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR" "1")
                            (.withEnv "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR" "1"))]
    (.start container)
    (let [bootstrap-uri (.getBootstrapServers container)
          common-props  {ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-uri}]
      (with-open [admin    (Admin/create common-props)
                  producer (KafkaProducer. (assoc common-props
                                                  ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG byte-array-serializer
                                                  ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG byte-array-serializer
                                                  ProducerConfig/ENABLE_IDEMPOTENCE_CONFIG "true"
                                                  ProducerConfig/TRANSACTIONAL_ID_CONFIG "kc-repl-testing"
                                                  ProducerConfig/ACKS_CONFIG "all"))]
        (binding [*kafka-container* container
                  *kafka-admin* admin
                  *kafka-producer* producer
                  *kafka-common-props* common-props]
          (f))))))

(def fib
  "Returns the nth item from the Fibonnaci sequence"
  (memoize (fn [n]
             (condp = n
               0 1
               1 1
               (+ (fib (dec n)) (fib (- n 2)))))))

(defn sqrt
  "Returns the square root of n, a long, as a string"
  [n]
  (.toString (.sqrt (BigDecimal. (BigInteger/valueOf n)) MathContext/DECIMAL64)))

(defn kafka-test-topics-fixture
  "Fixture that creates and populates some test related topics"
  [f]
  (.createTopics *kafka-admin* [(NewTopic. test-topic 2 (short 1))])
  (.initTransactions *kafka-producer*)
  (.beginTransaction *kafka-producer*)
  (doseq [{:keys [:foo] :as msg} (map (fn [i]
                                        {:foo i}) (range 100))]
    (-> (.send *kafka-producer* (ProducerRecord. test-topic
                                                 (int (/ foo 50))
                                                 (.getBytes (str foo) StandardCharsets/UTF_8)
                                                 (.getBytes (json/write-str msg) StandardCharsets/UTF_8)
                                                 [(RecordHeader. "record-num" (.toByteArray (BigInteger/valueOf foo)))
                                                  (RecordHeader. "foo-sqrt"
                                                                 (.getBytes (sqrt foo) StandardCharsets/UTF_8))]))
        (.get)))
  (.commitTransaction *kafka-producer*)
  (f))

(defn kcr-client-props []
  (assoc *kafka-common-props*
         ConsumerConfig/MAX_POLL_RECORDS_CONFIG (int test-poll-size)
         ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG (.getName ByteArrayDeserializer)
         ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG (.getName ByteArrayDeserializer)))

(defn kcr-client-fixture
  "Fixture that creates a kc-repl client and binds it; will be stopped and closed at the end of the test suite"
  [f]
  (with-open [kcrc (kcr/make-kcr-client (kcr-client-props))]
    (kcr/initialize! kcrc)
    (binding [*kcr-client* kcrc]
      (f)
      (kcr/stop kcrc))))

(defmacro within-orig-fn
  "Executes `thunk` with the function given in `orig-fn` having been redefined to first evaluate the `in-fn` form before
  actually calling the function that `orig-fn` refers to. If needed, `in-fn` can reference the args to the `orig-fn`
  invocation through the \"args\" symbol."
  [orig-fn in-fn & thunk]
  `(let [orig-fn# @#'~orig-fn]
     (with-redefs [~orig-fn (fn [& ~(symbol "args")]
                              ~in-fn
                              (apply orig-fn# ~(symbol "args")))]
       ~@thunk)))
