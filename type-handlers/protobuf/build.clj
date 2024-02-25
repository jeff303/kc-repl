(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]
            [clojure.tools.build.api :as b]
            [clojure.java.shell :as sh]))

(def lib 'net.clojars.jeff_evans/kc-repl-type-handler-protobuf)
(def version "1.1.0")

(def basis (delay (b/create-basis {:project "deps.edn"})))
(defn compile-java [& _]
      (b/delete {:path "target"})
      (b/javac {:src-dirs  ["test-java"]
                :basis @basis
                :class-dir "target/classes"}))
(defn protoc-compile "Compile test data protobufs" [opts]
      (let [cmd (format "/usr/local/bin/protoc -I=/usr/include \\
                                               -I=/usr/local/include \\
                                               -I=test-resources/proto \\
                                               --java_out=test-java \\
                                               test-resources/proto/us/jeffevans/testdata/*.proto")
            result (sh/sh cmd)]
           (println (str "Command output: " (:out result)))
           (println (str "Command error: " (:err result))
                    (println (str "Exit code: " (:exit result))))))
(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn print-classpath [opts]
  (let [classpath (System/getProperty "java.class.path")]
    (println "Current CLASSPATH:")
    (println classpath)))


(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (let [full-opts (assoc opts :lib lib :version version :transitive true)]
    (bb/clean full-opts)
    (compile-java)
    (-> full-opts
        (bb/run-tests)
        (bb/uber))))

(defn uberjar "Just build the uberjar" [opts]
      (-> opts
          (assoc :lib lib :version version)
          (bb/clean)
          (bb/uber)))