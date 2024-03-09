(ns us.jeffevans.kc-repl-test
  "Tests for the core kc-repl client"
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [us.jeffevans.kc-repl :as kcr]
            [us.jeffevans.kc-repl.test-common :as tc])
  (:import (org.apache.kafka.common TopicPartition)
           (java.nio.charset StandardCharsets)))

(use-fixtures :once tc/simple-kafka-container-fixture tc/kafka-test-topics-fixture)

;; need one of these per test since they might run in parallel
(use-fixtures :each tc/kcr-client-fixture)

(deftest simple-read-tests
  (testing "list-topics works as expected"
    (is (= ["test-topic"] (kcr/list-topics tc/*kcr-client*))))
  (testing "read-from works as expected"
    (testing " from offset 0"
      (is (= [{"foo" 0}
              {"foo" 1}
              {"foo" 2}
              {"foo" 3}
              {"foo" 4}
              {"foo" 5}
              {"foo" 6}
              {"foo" 7}
              {"foo" 8}
              {"foo" 9}]
             (kcr/read-from tc/*kcr-client* "test-topic" 0 0 10 "json"))))
    (testing " from offset >0"
      (is (= [{"foo" 5}
              {"foo" 6}
              {"foo" 7}
              {"foo" 8}
              {"foo" 9}]
             (kcr/read-from tc/*kcr-client* "test-topic" 0 5 5 "json")))
      (testing " and new offsets are recorded correctly"
        (is (= {(TopicPartition. "test-topic" 0) [10 true]} (kcr/current-assignments tc/*kcr-client*)))))))

(deftest polling-tests
  (testing "the expected number of calls to poll happen during active reads"
    (let [num-recs       50
          expected-polls (/ num-recs tc/test-poll-size)
          calls          (atom 0)]
      (tc/within-orig-fn kcr/poll-once! (swap! calls inc)
        (is (= num-recs (count (kcr/read-from tc/*kcr-client* "test-topic" 0 0 num-recs "json"))))
        (is (= expected-polls @calls)))))
  (testing "idle/heartbeat polling happens as expected"
    (let [sleep-sec      5
          exp-idle-polls (* sleep-sec (/ @#'kcr/max-poll-interval-ms 1000))
          calls          (atom 0)]
      (tc/within-orig-fn kcr/idle-poll! (swap! calls inc)
        (Thread/sleep (* sleep-sec 1000))
        ;; allow for a bit of fuzziness here
        (is (contains? #{exp-idle-polls (dec exp-idle-polls) (inc exp-idle-polls)} @calls)
            (format "number of idle polls should be within %d +/- 1" exp-idle-polls)))))
  (testing "calling poll repeatedly advances offsets as expected"
    (kcr/seek tc/*kcr-client* 0)
    (doseq [i (range 3)]
      (is (= [{"foo" i}] (kcr/poll tc/*kcr-client* 1 "json"))))))

(deftest seek-tests
  (kcr/assign tc/*kcr-client* "test-topic" 0 0)
  (testing "seek-while works as expected"
    (kcr/seek-while tc/*kcr-client* (fn [record]
                                      (< (get record "foo") 5)) "json")
    (is (= [{"foo" 5}] (kcr/poll tc/*kcr-client* 1 "json"))))
  (testing "seek and seek-until work as expected"
    (kcr/seek tc/*kcr-client* 0)
    (kcr/seek-until tc/*kcr-client* (fn [record]
                                      (= (get record "foo") 8)) "json")
    (is (= [{"foo" 8}] (kcr/poll tc/*kcr-client* 1 "json"))))
  (testing "seek- works as expected"
    (kcr/seek- tc/*kcr-client* 3)
    ;; we were at 9 before seek-, so at this point, we should be back to 6
    (is (= [{"foo" 6}] (kcr/poll tc/*kcr-client* 1 "json"))))
  (testing "seek+ works as expected"
    (kcr/seek+ tc/*kcr-client* 9)
    ;; we were at 7 before seek+, so at this point, we should be at 16
    (is (= [{"foo" 16}] (kcr/poll tc/*kcr-client* 1 "json")))))

(deftest pause-resume-tests
  (testing "pause works on existing partition"
    (kcr/pause tc/*kcr-client* "test-topic" 0)
    (testing " and no records from it are produced on next poll"
      (is (= [] (kcr/poll tc/*kcr-client* 0 "json")))))
  (testing "resuming works to assign a new partition"
    (kcr/resume tc/*kcr-client* "test-topic" 1)
    (kcr/seek tc/*kcr-client* 0)
    (testing " and the next poll produces records from it"
      (is (= [{"foo" 50}
              {"foo" 51}] (kcr/poll tc/*kcr-client* 2 "json"))))))

(deftest complex-record-handling-tests
  (testing "value-type should be honored (reading as text)"
    (is (= ["{\"foo\":0}"]
           (kcr/read-from tc/*kcr-client* "test-topic" 0 0 1 {::kcr/value-type "text"}))))
  (testing "key-parse-fn should work"
    (is (= [5]
           (kcr/read-from tc/*kcr-client* "test-topic" 0 5 1 {::kcr/key-type    "text"
                                                              ::kcr/map-record-fn (fn [_ {:keys [::kcr/parsed-key]}]
                                                                                    (Integer/valueOf parsed-key))}))))
  (testing "map-record-fn and reducing-fn should work in conjunction with while-fn"
    (kcr/seek tc/*kcr-client* 0)
    (let [thru-foo    10 ; seek until the record's `foo` value is equal to this
          safe-+      (fn [a b]
                        (cond
                          (nil? a)
                          b
                          (nil? b)
                          a
                          true
                          (+ a b)))
          map-rec-fn  (fn [_ {:keys [::kcr/parsed-value ::kcr/headers]}]
                        (let [foo-val (get parsed-value "foo")
                              sqrt    (Double/parseDouble (String. (get headers "foo-sqrt") StandardCharsets/UTF_8))]
                          {::foo-val foo-val, ::foo-sqrt sqrt}))
          reducing-fn (fn ([] ; init (0) arity
                           {})
                          ([acc] ; completion (1) arity
                           acc)
                          ([acc {:keys [::foo-val ::foo-sqrt] :as mapped-rec}] ; step (2) arity
                           (-> (update acc ::foo-sum safe-+ foo-val)
                               (assoc ::last-sqrt foo-sqrt))))]
      (is (= {::foo-sum   (reduce + (range thru-foo))
              ::last-sqrt (Double/parseDouble (tc/sqrt (dec thru-foo)))}
             ;; move forward through the topic until we see a message where the `foo` value is 10
             ;; along the way, keep a running tally of the sum of all `foo` values seen, and the last
             ;; square root value seen, and return those at the end
             (kcr/seek-while tc/*kcr-client*
                             (fn [{:keys [::foo-val]}]
                               (< foo-val thru-foo))
                             {::kcr/value-type       "json"
                              ::kcr/include-headers? true
                              ::kcr/reducing-fn      reducing-fn
                              ::kcr/map-record-fn    map-rec-fn})))
      (testing " and the new offset is correct (we should now be at offset 10 for test-topic:0)"
        (is (= {(TopicPartition. "test-topic" 0) [10 true]} (kcr/current-assignments tc/*kcr-client*)))))))

