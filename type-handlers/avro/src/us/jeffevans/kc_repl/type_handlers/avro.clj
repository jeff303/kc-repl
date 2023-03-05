(ns us.jeffevans.kc-repl.type-handlers.avro
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [us.jeffevans.kc-repl :as kcr])
  (:import (io.confluent.kafka.schemaregistry.client CachedSchemaRegistryClient SchemaRegistryClient)
           (io.confluent.kafka.serializers KafkaAvroDeserializer)
           (java.nio.charset StandardCharsets)))

(def ^:const ^:private sr-url-prop "schema.registry.url")

(defrecord AvroHandler [^SchemaRegistryClient sr-client ^KafkaAvroDeserializer deser])

(extend-protocol kcr/type-handler AvroHandler
  (parse-bytes [this ^String topic ^bytes b]
    (let [^KafkaAvroDeserializer d (:deser this)]
      (.deserialize d topic b))))

(defmethod kcr/create-type-handler "avro" [_ kc-props]
  (let [sr-url (get kc-props sr-url-prop)]
    (if-not (str/blank? sr-url)
      (let [cache-sz  100
            sr-client (CachedSchemaRegistryClient. sr-url cache-sz kc-props)
            deser     (KafkaAvroDeserializer. sr-client kc-props)]
        (->AvroHandler sr-client deser))
      (log/warnf "No %s property found to initialize Schema Registry client" sr-url-prop))))