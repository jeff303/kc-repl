(ns us.jeffevans.kc-repl.java-main-test
  "Tests for the Java uberjar REPL, which is simulated by controlling lines read and written."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [us.jeffevans.kc-repl.java-main :as kcrm]
            [us.jeffevans.kc-repl.test-common :as tc]
            [clojure.java.io :as io]
            [us.jeffevans.kc-repl :as kcr]
            [us.jeffevans.kc-repl.type-handlers :as th])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)
           (java.util Properties)))

(use-fixtures :once tc/simple-kafka-container-fixture tc/kafka-test-topics-fixture)

(defrecord TestSetConfigHandler [k-to-args])

(extend-protocol th/type-handler TestSetConfigHandler
  (parse-bytes [_ _ ^bytes b]
    (String. b StandardCharsets/UTF_8))
  (->clj [_ obj]
    obj)
  (set-config! [this k args]
    (println "about to set config")
    (swap! (:k-to-args this) assoc k args)))

(def ^:private ^:const test-type-handler-nm "test-set-config")

(defmethod th/create-type-handler test-type-handler-nm [& _]
  (TestSetConfigHandler. (atom {})))

(defn- make-line-producing-fn [lines]
  (let [remaining-lines (atom lines)]
    (fn []
      (let [[l & rest-lines] @remaining-lines]
        (reset! remaining-lines rest-lines)
        l))))

(defn- make-line-gathering-fn []
  (let [gathered-lines (atom [])]
    (fn
      ([]
       ;; when called with zero args, return the lines
       @gathered-lines)
      ([line]
       (swap! gathered-lines conj line)))))

(deftest java-main-test
  (let [input-lines       ["list-topics"
                           "read-from --topic test-topic --part 0 --offset 0 --num-msg 1 --record-handling-opts json"
                           "seek+ --by 2"
                           "poll --num-msg 2 --record-handling-opts json"
                           "poll --record-handling-opts json"
                           "seek -o 0"
                           (format "set-type-handler-config! --type-name %s --k foo --args bar1 bar2 bar3 bar4" test-type-handler-nm)
                           "stop"]
        line-gathering-fn (make-line-gathering-fn)]
    (with-redefs [kcrm/get-input-line   (make-line-producing-fn input-lines)
                  kcr/print-output-line line-gathering-fn]
      (let [prop-file (doto (File/createTempFile "test-java-client" ".properties")
                        (.deleteOnExit))
            props     (doto (Properties.)
                        (.putAll (-> (tc/kcr-client-props)
                                     (update-vals str))))]
        (.store props (io/writer prop-file) nil)
        (try
          (kcrm/-main (.getAbsolutePath prop-file)
                      (fn [client]
                        (let [th (kcr/get-type-handler-by-name client test-type-handler-nm)]
                          (is (some? th))
                          (println "about to run assertion")
                          (let [k-to-args (:k-to-args th)]
                            (is (= {"foo" ["bar1" "bar2" "bar3" "bar4"]} @k-to-args))))))
          (let [final-lines (line-gathering-fn)]
            (is (= ["[\"test-topic\"]"
                    "[{\"foo\" 0}]"
                    "test-topic:0 at 1"
                    "test-topic:0 at 3"
                    "[{\"foo\" 3} {\"foo\" 4}]"
                    "test-topic:0 at 5"
                    (str "[" (str/join " " (map #(format "{\"foo\" %d}" %) (range 5 15))) "]")
                    "test-topic:0 at 15"
                    "test-topic:0 at 0"
                    "{\"foo\" [\"bar1\" \"bar2\" \"bar3\" \"bar4\"]}"
                    "test-topic:0 at 0" ; TODO: fix print-offsets? - it doesn't seem to work
                    "true"] ; TODO: don't return true from last fn (::stop probably)
                   final-lines))))))))