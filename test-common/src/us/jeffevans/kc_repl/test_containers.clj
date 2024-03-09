(ns us.jeffevans.kc-repl.test-containers
  "Testcontainers implementations needed for schema registry support.

  Adapted from https://github.com/findinpath/testcontainers-kafka-avro"
  (:import (org.testcontainers.containers GenericContainer)
           (org.testcontainers.utility TestcontainersConfiguration)
           #_(com.findinpath.testcontainers SchemaRegistryContainer)))

(def tc-cfg (TestcontainersConfiguration/getInstance))

(def ^:const cp-version "The Confluent Platform base version, to use for container images" "7.1.0")

(def zk-network-alias "zookeeper")

(def zk-internal-port 2181)
(def zk-tick-time 2000)

(defn make-zk-container []
  (GenericContainer. (.getEnvVarOrUserProperty tc-cfg "zookeeper.container.image" (str "confluentinc/cp-zookeeper:" cp-version))))

;; we can get away with this being static instead of a dynamic binding, since the network and port
;; are known ahead of time
(defn zk-internal-url "Gets the internal URL for the zk container instance" []
  (format "%s:%s" zk-network-alias zk-internal-port))

(def sr-network-alias "schema-registry")

(def sr-port 8081)

(defn make-sr-container []
  (GenericContainer. (.getEnvVarOrUserProperty tc-cfg "schemaregistry.container.image" (str "confluentinc/cp-schema-registry:" cp-version))))

