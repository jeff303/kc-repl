(ns us.jeffevans.kc-repl.type-handlers.protobuf
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [us.jeffevans.kc-repl.type-handlers :as th])
  (:import (com.google.protobuf Descriptors$FieldDescriptor$Type MessageOrBuilder)
           (io.confluent.kafka.schemaregistry.client CachedSchemaRegistryClient SchemaRegistryClient)
           (io.confluent.kafka.schemaregistry.protobuf ProtobufSchemaProvider)
           (io.confluent.kafka.serializers.protobuf KafkaProtobufDeserializer)
           (java.lang Class)
           (java.lang.reflect Method)))

(declare dummy)

(def ^:const ^:private sr-url-prop "schema.registry.url")

(defprotocol ProtobufHandlerProtocol
  (set-msg-class-for-topic! [_ topic message-class]))

(defrecord ProtobufHandler [^SchemaRegistryClient sr-client ^KafkaProtobufDeserializer deser topic-nm-to-message-class]
  ProtobufHandlerProtocol
  (set-msg-class-for-topic! [_ topic message-class]
    (let [msg-cls (condp instance? message-class
                    String (Class/forName message-class)
                    Class message-class
                    (throw
                      (ex-info
                        (format "the value given for topic %s was a %s, but it must be a String or Class"
                                topic
                                (type message-class))
                        {})))]
      (swap! topic-nm-to-message-class assoc topic msg-cls))))


(declare convert-field)

(def ^:const topic-name-to-message-class-config "protobuf-topic-name-to-message-class")

(defn- convert-message [message]
  (let [fields (into {} (.getAllFields message))]
    (into {}
          (for [[field value] fields]
            [(.getName field) (convert-field field value)]))))

(defn- convert-field [field value]
  (cond
    (.isMapField field)
    (let [sub-map (into {} (map (fn [[k v]] [(.toString k) (.toString v)]) value))]
      sub-map)

    (.isRepeated field)
    (mapv #(.toString %) value)

    (= (.getType field) Descriptors$FieldDescriptor$Type/MESSAGE)
    (convert-message value)

    :else
    value))

(defn protobuf-to-map [protobuf-message]
  (convert-message protobuf-message))

(defn- maybe-deserialize-from-class [^String topic ^bytes b topic-nm-to-message-class]
  #_(doseq [[k v] @topic-nm-to-message-class]
      (printf "%s (%s) -> %s (%s)" k (type k) v (type v)))
  (if-let [^Class msg-cls (get @topic-nm-to-message-class topic)]
    (let [^Method parse-fn (.getMethod msg-cls "parseFrom" (into-array Class [(type b)]))]
      (.invoke parse-fn nil (into-array Object [b])))
    (throw (IllegalStateException. (format "no Protobuf message class was registered for topic %s" topic)))))

(defn- add-config-vals-topic-nm-to-msg-class [handler args]
  (doseq [[topic-nm msg-cls] (partition 2 args)]
    (set-msg-class-for-topic! handler topic-nm msg-cls)))


(extend-protocol th/type-handler ProtobufHandler
  (parse-bytes [this ^String topic ^bytes b]
    (if-let [^KafkaProtobufDeserializer d (:deser this)]
      (.deserialize d topic b)
      (maybe-deserialize-from-class topic b (:topic-nm-to-message-class this))))
  (->clj [_ ^MessageOrBuilder msg]
    (convert-message msg))
  (set-config! [this k args]
    (condp = k
      topic-name-to-message-class-config (add-config-vals-topic-nm-to-msg-class this args)
      (throw (IllegalArgumentException. (format "protobuf handler has no config named %s" k))))))

(defmethod th/create-type-handler "protobuf" [kc-props & _]
  (let [sr-url (get kc-props sr-url-prop)]
    (if-not (str/blank? sr-url)
      (let [cache-sz  100
            sr-client (CachedSchemaRegistryClient. sr-url cache-sz [(ProtobufSchemaProvider.)] kc-props)
            deser     (KafkaProtobufDeserializer. sr-client kc-props)]
        (->ProtobufHandler sr-client deser nil))
      (do
        (log/warnf "No %s property found to initialize Schema Registry client; the protobuf handling
                  will only support use of the topic name to schema file mode, via the %s config"
                   sr-url-prop
                   topic-name-to-message-class-config)
        (->ProtobufHandler nil nil (atom {}))))))