(ns us.jeffevans.kc-repl.java-main-arg-parse-test
  "Tests for Java main arg parsing, both the instaparse grammar and conversion to tools.cli args"
  (:require [clojure.test :refer :all]
            [instaparse.core :as insta]
            [us.jeffevans.kc-repl.java-main :as kcrm]))

(deftest arg-parse-test
  (let [topic-nm "protobuf-data"
        cls-nm   "us.jeffevans.kc_repl.testdata.SensorReadingOuterClass$SensorReading"
        cfg-nm   "protobuf-topic-name-to-message-class"
        cases [["dostuff -f a --bar \"baz man\" --zen a b c d"
                {::kcrm/command-name "dostuff"
                 ::kcrm/merged-args {"-f" ["a"]
                                     "--bar" ["baz man"]
                                     "--zen" ["a" "b" "c" "d"]}}]
               ["domorestuff -a a \"b c\" d -e -f \"gh -i\" j"
                {::kcrm/command-name "domorestuff"
                 ::kcrm/merged-args {
                                     "-a" ["a"
                                           "b c"
                                           "d"]
                                     "-e" []
                                     "-f" ["gh -i"
                                           "j"]}}]
               ["read-from --topic test-topic --part 0 --offset 0 --num-msg 1 --record-handling-opts json"
                {::kcrm/command-name "read-from"
                 ::kcrm/merged-args {"--topic" ["test-topic"]
                                     "--part" ["0"]
                                     "--offset" ["0"]
                                     "--num-msg" ["1"]
                                     "--record-handling-opts" ["json"]}}]
               ["seek+ --by 2"
                {::kcrm/command-name "seek+"
                 ::kcrm/merged-args {"--by" ["2"]}}]
               [(format "set-type-handler-config! --type-name protobuf --k %s --args %s %s" cfg-nm topic-nm cls-nm)
                {::kcrm/command-name "set-type-handler-config!"
                 ::kcrm/merged-args {"--type-name" ["protobuf"]
                                     "--k" [cfg-nm]
                                     "--args" [topic-nm cls-nm]}}]]]
    (doseq [[input-ln exp-map] cases]
      (let [parse-res (kcrm/parse-and-transform-input-line input-ln)]
        (is (= exp-map parse-res))))))

(deftest build-tools-cli-args-test
  (testing "valid args are parsed correctly")
  (let [cases [["foo -a b c --d --e f g -h i"
                {::kcrm/arg-vector ["foo" "-a" "b" "-a" "c" "--d" "--e" "f" "--e" "g" "-h" "i"]}]]]
    (doseq [[input-ln exp-args] cases]
      (let [args-res (kcrm/parse-line-as-cli-args input-ln)]
        (is (= exp-args args-res)))))
  (testing "errors are returned accordingly"
    (let [{:keys [::kcrm/parse-error]} (kcrm/parse-line-as-cli-args "foo bar --baz")]
      (is (some? parse-error))
      (is (insta/failure? parse-error)))))
