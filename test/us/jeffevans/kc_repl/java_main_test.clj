(ns us.jeffevans.kc-repl.java-main-test
  "Tests for the Java uberjar REPL, which is simulated by controlling lines read and written."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [us.jeffevans.kc-repl.java-main :as kcrm]
            [us.jeffevans.kc-repl.test-common :as tc]
            [clojure.java.io :as io]
            [us.jeffevans.kc-repl :as kcr])
  (:import (java.io File)
           (java.util Properties)))

(use-fixtures :once tc/kafka-container-fixture tc/kafka-test-topics-fixture)

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
                           "read-from --topic test-topic --part 0 --offset 0 --num-msg 1 --msg-format json"
                           "seek+ --by 2"
                           "poll --num-msg 2 --msg-format json"
                           "poll --msg-format json"
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
          (kcrm/-main (.getAbsolutePath prop-file))
          (let [final-lines (line-gathering-fn)]
            (is (= ["[\"test-topic\"]"
                    "[{\"foo\" 0}]"
                    "test-topic:0 at 1"
                    "test-topic:0 at 3"
                    "[{\"foo\" 3} {\"foo\" 4}]"
                    "test-topic:0 at 5"
                    (str "[" (str/join " " (map #(format "{\"foo\" %d}" %) (range 5 15))) "]")
                    "test-topic:0 at 15"
                    "true"] ; TODO: don't return true from last fn (::stop probably)
                   final-lines))))))))

;; TODO: add tests for the error messages generated from arg parsing (which can get hairy)