(ns us.jeffevans.kc-repl.type-handlers.avro
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [us.jeffevans.kc-repl.type-handlers :as th])
  (:import (io.confluent.kafka.schemaregistry.client CachedSchemaRegistryClient SchemaRegistryClient)
           (io.confluent.kafka.serializers KafkaAvroDeserializer)
           (org.apache.avro Schema$Field)
           (org.apache.avro.generic GenericRecord)
           (org.apache.avro.util Utf8)))

(declare dummy)

(def ^:const ^:private sr-url-prop "schema.registry.url")

(defrecord AvroHandler [^SchemaRegistryClient sr-client ^KafkaAvroDeserializer deser])

(extend-protocol th/type-handler AvroHandler
  (parse-bytes [this ^String topic ^bytes b]
    (let [^KafkaAvroDeserializer d (:deser this)]
      (.deserialize d topic b)))
  (->clj [this ^GenericRecord obj]
    (let [schema (.getSchema obj)
          fields (.getFields schema)]
      (reduce (fn [acc ^Schema$Field field]
                (let [fnm (.name field)
                      v*  (.get obj fnm)
                      v   (condp instance? v*
                            GenericRecord
                            (th/->clj this v*)

                            Utf8
                            (.toString v*)

                            v*)]
                    (assoc acc fnm v)))
              {}
              fields))))

(defmethod th/create-type-handler "avro" [kc-props & _]
  (let [sr-url (get kc-props sr-url-prop)]
    (if-not (str/blank? sr-url)
      (let [cache-sz  100
            sr-client (CachedSchemaRegistryClient. sr-url cache-sz kc-props)
            deser     (KafkaAvroDeserializer. sr-client kc-props)]
        (->AvroHandler sr-client deser))
      (log/warnf "No %s property found to initialize Schema Registry client" sr-url-prop))))