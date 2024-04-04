(ns us.jeffevans.kc-repl
  (:gen-class)
  (:require
    [clojure.core.async :as a]
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.java.io]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [nrepl.cmdline :as n.c]
    [us.jeffevans.kc-repl.type-handlers :as th]
    [us.jeffevans.kc-repl.type-handler-load :as thl])
  (:import
    (clojure.lang IPersistentMap)
    (java.lang AutoCloseable)
    (java.nio.charset StandardCharsets)
    (java.time Duration)
    (java.util Properties)
    (org.apache.kafka.clients.consumer ConsumerRecord KafkaConsumer)
    (org.apache.kafka.common TopicPartition)
    (org.apache.kafka.common.header Header Headers)))

(def ^:private ^:const max-poll-interval-ms
  "Specifies the maximum time (in milliseconds) between calls to .poll that will be performed by the consumer. If no
  user-initiated polling request is made, then an empty/idle poll will occur to ensure the consumer stays alive and
  well-behaved."
  1000)


(defonce ^:private type-handler-create-fns {})

(defmethod th/parse-type "json" [_ b]
  (json/read-str (String. b StandardCharsets/UTF_8) :keywordize? true))

(defmethod th/parse-type "text" [_ b]
  (String. b StandardCharsets/UTF_8))

(defn- headers->clj
  "TODO: figure out how to add parsing of header values?"
  [^ConsumerRecord cr]
  (let [^Headers headers (.headers cr)]
    (reduce (fn [acc ^Header header]
              (assoc acc (.key header) (.value header)))
            {}
            (.toArray headers))))

(defn- parse-type-fn-with-handlers [^String type-name type-handlers v->clj?]
  (if-let [handler (get type-handlers type-name)]
    (if v->clj?
      (fn [topic v]
        (->> (th/parse-bytes handler topic v)
             (th/->clj handler)))
      (partial th/parse-bytes handler))
    (throw (ex-info (format "no type handler registered for %s" type-name) {::type-handler-keys (keys type-handlers)}))))

(defn- make-default-record-handler-fn [^String value-type type-handlers]
  (fn [^ConsumerRecord cr]
    (if-let [handler (get type-handlers value-type)]
      (let [parsed (th/parse-bytes handler (.topic cr) (.value cr))]
        (th/->clj handler parsed))
      (throw (ex-info (format "no type handler registered for %s, a %s" value-type (type value-type)) {::type-handler-keys (keys type-handlers)})))))

(defn- make-record-handler-fn [{:keys [::key-type ::key-parse-fn ::value-type ::value-parse-fn ::map-record-fn
                                       ::include-headers? ::include-topic? ::include-partition? ::include-offset?
                                       ::include-timestamp? ::key->clj? ::val->clj?] :as record-handling} type-handlers]
  (let [k-parse-fn (cond (fn? key-parse-fn)
                         key-parse-fn

                         (some? key-type)
                         (parse-type-fn-with-handlers key-type type-handlers key->clj?)

                         ;; by default, don't try to parse the key
                         true
                         (constantly nil))
        v-parse-fn (cond (fn? value-parse-fn)
                         value-parse-fn

                         (some? value-type)
                         (parse-type-fn-with-handlers value-type type-handlers val->clj?)

                         ;; by default, don't try to parse the value
                         true
                         (constantly nil))]
    (fn [^ConsumerRecord cr]
      (let [topic      (.topic cr)
            parsed-key (k-parse-fn topic (.key cr))
            parsed-val (v-parse-fn topic (.value cr))]
        (if (ifn? map-record-fn)
          (let [extra-args (cond-> {}
                                   include-headers? (assoc ::headers (headers->clj cr))
                                   include-offset? (assoc ::offset (.offset cr))
                                   include-partition? (assoc ::part (.partition cr))
                                   include-timestamp? (assoc ::timestamp (.timestamp cr))
                                   include-topic? (assoc ::topic (.topic cr))
                                   (some? parsed-key) (assoc ::parsed-key parsed-key)
                                   (some? parsed-val) (assoc ::parsed-value parsed-val))]
            (map-record-fn cr extra-args))
          ;; if no custom map-record-fn is supplied, just return the parsed value for each record
          parsed-val)))))

;; write a function that takes in record-handling args and returns a function that will
(defn- record-handling-opts>record-handler-fn
  "For the given `msg-record-handling-opts>` map or string, return a function that will be used to map each
  ConsumerRecord to some `mapped-record`.  `mapped-record` can be any arbitrary object (probably a Clojure data
  structure) that will then be passed along in the transduction pipeline to the filter, map, and reduce functions.

  If ::reducing-fn is included in record-handling-opts, it will be used as the reducing fn in the transduction."
  [record-handling-opts type-handlers]
  (condp instance? record-handling-opts
    IPersistentMap (cond-> {::consumer-record-map-fn (make-record-handler-fn record-handling-opts type-handlers)}
                           (fn? (get record-handling-opts ::reducing-fn))
                           (assoc ::poll-xf-reducing-fn (get record-handling-opts ::reducing-fn)))
    ;; if `msg-handling` is a String, interpret it as the value-type and return a
    ;; handler fn that simply parses the value and returns that
    String {::consumer-record-map-fn (make-default-record-handler-fn record-handling-opts type-handlers)}
    (throw (ex-info (format (str "record-handling %s was not recognized; must be either a"
                                 " string (message value format) or map")
                            record-handling-opts)
                    {::record-handling record-handling-opts}))))

(defmulti handle-repl->client-message (fn [_consumer {:keys [::message-type]}]
                                        (log/debugf "Got %s message" message-type)
                                        message-type))

(defmethod handle-repl->client-message nil [_ _]
  ::stop)

(defmethod handle-repl->client-message ::stop [_ _]
  ::stop)

(defn internal-topic? [topic-name]
  (str/starts-with? topic-name "__"))

(defmethod handle-repl->client-message ::list-topics [^KafkaConsumer consumer _]
  (let [topic->partitions (.listTopics consumer)
        topic-names       (filterv (complement internal-topic?) (keys topic->partitions))]
    {::topics topic-names}))

(defn- poll-once!
  "Executes a single poll call for the given consumer"
  [^KafkaConsumer consumer]
  (let [poll-res              (.poll consumer (Duration/ofMillis 10000))
        partitions            (.partitions poll-res)]
    (reduce (fn [all-records ^TopicPartition partition]
              (let [part-records (.records poll-res partition)]
                (vec (concat part-records all-records))))
            []
            partitions)))

(defn- idle-poll!
  "Executes a single poll call for the given consumer, which is expected to return no records (since all assignments
  are paused). For heartbeat/liveness purposes only."
  [^KafkaConsumer consumer]
  (when (empty? (.assignment consumer))
    (let [topics (.listTopics consumer)
          part   (TopicPartition. (first (keys topics)) 0)]
      (log/debugf "Initializing consumer with a paused assignment of %s" (str part))
      (.assign consumer [part])
      (.pause consumer [part])))
  (.poll consumer (Duration/ofMillis 0)))

(defn- consumer-offsets [^KafkaConsumer consumer]
  (reduce (fn [acc ^TopicPartition part]
            (assoc acc part (.position consumer part)))
          {}
          (.assignment consumer)))

(defn- poll-seq*!
  "Helper function for returning the infinite sequence of poll results. Assumes that active partitions have been
  resumed."
  [^KafkaConsumer consumer]
  (let [start-offsets (consumer-offsets consumer)
        poll-res      (poll-once! consumer)
        end-offsets   (consumer-offsets consumer)]
    (when (or (not (empty? poll-res)) (not= start-offsets end-offsets))
      (lazy-cat poll-res (poll-seq*! consumer)))))

(defn- poll-seq!
  "Returns an infinite, lazy sequence of records returned by calling poll-once!  Before doing so, it resumes the
  \"active partitions\" that are stored in `active-assignments-atom` so that we actually get records from polling."
  [active-assignments-atom ^KafkaConsumer consumer]
  ;; TODO: fix this hackiness; we currently resume the active partitions here, but pause them again in the
  ;; transducer completion arity below (the kcr-xform), which is fairly unclean
  (.resume consumer @active-assignments-atom)
  (poll-seq*! consumer))

(defn- max*
  "Nil-safe implementation of max"
  [x y]
  (cond (nil? x)
        y
        (nil? y)
        x
        true
        (max x y)))

(defn- kcr-xform
  "Stateful transducer that tracks the max offsets seen per TopicPartition, then seeks the consumer to those offsets
  in the completion step. Also, in the completion arity, pauses the active partitions again."
  [active-assignments-atom ^KafkaConsumer consumer inc-last?]
  (fn [xf]
    (let [max-offsets (volatile! {})]
      (fn
        ([]
         (xf))
        ([result]
         (.pause consumer @active-assignments-atom)
         (doseq [topic (keys @max-offsets)]
           (doseq [[part offset] (get @max-offsets topic)]
             (log/debugf "Completed transducer; seeking %s partition %d to offset %d" topic part offset)
             (.seek consumer (TopicPartition. topic part) (if inc-last? (inc offset) offset))))
         (xf result))
        ([result ^ConsumerRecord record]
         (let [topic  (.topic record)
               part   (.partition record)
               offset (.offset record)]
           (vswap! max-offsets
                   update
                   topic
                   (fn [topic->parts]
                     (update topic->parts part max* offset)))
           (xf result record)))))))

(defn- consumer-assigments [^KafkaConsumer consumer active-assignments-atom]
  (reduce (fn [acc ^TopicPartition assignment]
            (let [offset  (.position consumer assignment)
                  active? (contains? @active-assignments-atom assignment)]
              (assoc acc assignment [offset active?])))
          {}
          (.assignment consumer)))

(defn- ok
  [& _]
  ::ok)

(defn- add-consumer-assignment!
  "Adds the given `assignment` (a TopicPartition) to the given `consumer`. If the consumer already had it assigned,
  this is a no-op. If `active?` is true, also adds the assignment to the given `active-assignments-atom`."
  [active-assignments-atom ^KafkaConsumer consumer ^TopicPartition assignment active?]
  (let [assignments        (set (.assignment consumer))]
    (swap! active-assignments-atom (if active? conj disj) assignment)
    (when-not (contains? assignments assignment)
      (let [new-assignments (conj assignments assignment)]
        (.assign consumer new-assignments)
        (.pause consumer new-assignments)
        (.seekToBeginning consumer [assignment])
        (.position consumer assignment)
        (let [beg (.beginningOffsets consumer new-assignments)]
          (print beg))))
    (idle-poll! consumer)))

(defn- remove-consumer-assignment!
  "Removes the given `assignment` (a TopicPartition) from the given `consumer`. If the consumer did not already
  have it assigned, this is a no-op."
  [active-assignments-atom ^KafkaConsumer consumer assignments]
  (doseq [topic (keys assignments)]
    (let [[part]          (get assignments topic)
          tp              (TopicPartition. topic part)
          cur-assignments (set (.assignment consumer))]
      (swap! active-assignments-atom disj tp)
      (when (contains? cur-assignments tp)
        (let [new-assignments (disj cur-assignments tp)]
          (.assign consumer new-assignments)))))
  (idle-poll! consumer)
  ::ok)

(defn- assign-and-seek! [^KafkaConsumer consumer assignments active-assignments-atom]
  (doseq [topic (keys assignments)]
    (let [[part ^long offset?] (get assignments topic)
          tp             (TopicPartition. topic part)]
      (add-consumer-assignment! active-assignments-atom consumer tp true)
      (if (some? offset?)
        (.seek consumer tp offset?))))
  ::ok)

(defmethod handle-repl->client-message ::assign [^KafkaConsumer consumer {:keys [::active-assignments-atom
                                                                                 ::new-assignment]}]
  (assign-and-seek! consumer new-assignment active-assignments-atom))

(defmethod handle-repl->client-message ::unassign [^KafkaConsumer consumer {:keys [::active-assignments-atom
                                                                                   ::remove-assignments]}]
  (remove-consumer-assignment! active-assignments-atom consumer remove-assignments))

(defmethod handle-repl->client-message ::seek [^KafkaConsumer consumer
                                               {:keys [::active-assignments-atom
                                                       ::to
                                                       ::forward-by
                                                       ::backward-by
                                                       ::while-fn
                                                       ::until-fn
                                                       ::poll-xf-args] :as seek-args}]
  (let [{:keys [::consumer-record-map-fn ::poll-xf-reducing-fn]} poll-xf-args]
    (cond (fn? while-fn)
          (do
            (transduce (comp (kcr-xform active-assignments-atom consumer false)
                             (map consumer-record-map-fn)
                             (take-while while-fn))
                       (if (fn? poll-xf-reducing-fn)
                         poll-xf-reducing-fn
                         ok)
                       (poll-seq! active-assignments-atom consumer)))

          (fn? until-fn)
          (transduce (comp (kcr-xform active-assignments-atom consumer false)
                           (map consumer-record-map-fn)
                           (take-while (complement until-fn)))
                     (if (fn? poll-xf-reducing-fn)
                       poll-xf-reducing-fn
                       ok)
                     (poll-seq! active-assignments-atom consumer))

          (int? to)
          (do
            (log/debugf "Seeking to offset %d" to)
            (if (> (count @active-assignments-atom) 1)
              (ex-info "Can only seek when one assignment is active"
                       {::active-assignments @active-assignments-atom})
              (do (.seek consumer (first @active-assignments-atom) to)
                  (ok))))

          (or (int? forward-by) (int? backward-by))
          (do
            (if (empty? @active-assignments-atom)
              (ex-info "Can only seek+ or seek- when one assignment is active"
                       {::active-assignments @active-assignments-atom})
              (let [active-assignment (first @active-assignments-atom)
                    curr              (.position consumer active-assignment)
                    adj               (if (int? backward-by) (* -1 backward-by) forward-by)]
                (do (.seek consumer active-assignment (+ curr adj))
                    (ok)))))

          ;; if no other clauses matched, there is an error
          true
          (ex-info (str "Must specify one of while-fn (fn), until-fn (fn), backward-by (long)"
                        "forward-by (long), or to (long)")
                   {::args seek-args}))))

(defmethod handle-repl->client-message ::pause [^KafkaConsumer consumer {:keys [::active-assignments-atom
                                                                                ::topic
                                                                                ::part]}]
  (add-consumer-assignment! active-assignments-atom consumer (TopicPartition. topic part) false)
  (ok))

(defmethod handle-repl->client-message ::resume [^KafkaConsumer consumer {:keys [::active-assignments-atom
                                                                                 ::topic
                                                                                 ::part]}]
  (add-consumer-assignment! active-assignments-atom consumer (TopicPartition. topic part) true)
  (ok))

(defn default-poll-xf-reducing-fn
  ([]
   [])
  ([acc]
   acc)
  ([acc rec]
   (vec (conj acc rec))))

(defmethod handle-repl->client-message ::poll [^KafkaConsumer consumer {:keys [::num-msg
                                                                               ::active-assignments-atom
                                                                               ::assignment-override
                                                                               ::poll-xf-args]}]
  (let [{:keys [::consumer-record-map-fn ::poll-xf-reducing-fn]} poll-xf-args]
    (assign-and-seek! consumer assignment-override active-assignments-atom)
    (let [result (transduce (comp (kcr-xform active-assignments-atom consumer true)
                                  (map consumer-record-map-fn)
                                  (take num-msg))
                            (if (fn? poll-xf-reducing-fn)
                                poll-xf-reducing-fn
                                default-poll-xf-reducing-fn)
                            (poll-seq! active-assignments-atom consumer))]
      {::result result, ::current-assignments (consumer-assigments consumer active-assignments-atom)})))

(defn- run-consumer-loop!
  "Runs the main KafkaConsumer loop.  Runs forever until a ::stop message is received.

  TODO: fix core async usage, or do something else entirely

  To work properly, this currently relies upon the core async thread pool size being 1, which is controlled by the
  JVM property -Dclojure.core.async.pool-size=1, but this is quite hacky

  Without this control, then we randomly get the \"KafkaConsumer is not safe for multi-threaded access\" exception.

  It might be better to use a lock to prevent multiple messages from being consumed by different threads, or some
  other mechanism to ensure that only one single thread ever owns the KafkaConsumer."
  [^KafkaConsumer consumer in-chan out-chan]
  (a/go-loop [] ;; TODO: get rid of go-loop somehow?
    (let [timeout-ch (a/timeout max-poll-interval-ms)]
      (a/alt!
        timeout-ch (do
                     (log/tracef "Didn't receive a REPL message within %d ms; idle polling to keep consumer alive"
                                 max-poll-interval-ms)
                     (idle-poll! consumer)
                     (recur))
        in-chan    ([msg]
                    (log/tracef "Received a REPL message %s" (pr-str msg))
                    (let [result (handle-repl->client-message consumer msg)
                          stop?  (= result ::stop)]
                      (if (instance? Exception result)
                          (a/>! out-chan {::error result})
                          (a/>! out-chan result))
                      (when-not stop?
                        (recur))))))))

(defn stop* [repl->consumer-chan]
  (a/>!! repl->consumer-chan {::message-type ::stop}))

(defn poll* ""
  [to-consumer-chan from-consumer-chan active-assignments-atom last-read-records-atom
   {:keys [::temp-assignment ::poll-xf-args ::num-msg]}]
  (-> (a/go
        (a/>! to-consumer-chan {::message-type            ::poll,
                                ::num-msg                 num-msg,
                                ::active-assignments-atom active-assignments-atom,
                                ::assignment-override     temp-assignment
                                ::poll-xf-args            poll-xf-args})
        (let [{:keys [::result]} (a/<! from-consumer-chan)]
          (reset! last-read-records-atom result)
          result))
      (a/<!!)))

(defn read-from*
  "TODO: fill in"
  [to-consumer-chan from-consumer-chan active-assignments-atom last-read-records-atom topic part poll-xf-args offset?
   num-msg?]
  (let [temp-assignment (when topic
                          {topic [part offset?]})]
    (poll* to-consumer-chan from-consumer-chan active-assignments-atom last-read-records-atom
           (cond-> {::temp-assignment temp-assignment, ::poll-xf-args poll-xf-args}
                   (some? num-msg?)
                   (assoc ::num-msg num-msg?)))))

(defn- consumer-roundtrip
  "Helped function that sends the given `msg` to the consumer using the given channels. Throws any exceptions coming
  from the consumer to generate a stack trace in the right thread."
  [to-consumer-chan from-consumer-chan msg]
  (-> (a/go
        (a/>! to-consumer-chan msg)
        (let [res (a/<! from-consumer-chan)]
          (if (instance? Exception res)
            (throw res)
            res)))
      (a/<!!)))

(defn- assign!
  ""
  [to-consumer-chan from-consumer-chan active-assignments-atom topic partition & [offset?]]
  ;; just override the assignment and read zero records
  (consumer-roundtrip to-consumer-chan from-consumer-chan {::message-type ::assign
                                                           ::active-assignments-atom active-assignments-atom
                                                           ::new-assignment {topic [partition offset?]}}))

(defn- unassign!
  ""
  [to-consumer-chan from-consumer-chan active-assignments-atom topic partition & [offset?]]
  (consumer-roundtrip to-consumer-chan from-consumer-chan {::message-type ::unassign
                                                           ::active-assignments-atom active-assignments-atom
                                                           ::remove-assignments {topic [partition]}}))


(s/def ::consumer-record-map-fn fn?)
(s/def ::poll-xf-reducing-fn fn?)

(s/def ::poll-xf-args (s/keys :opt [::consumer-record-map-fn ::poll-xf-reducing-fn]))

(defn- seek!
  ""
  [to-consumer-chan from-consumer-chan seek-args]
  (consumer-roundtrip to-consumer-chan from-consumer-chan (assoc seek-args ::message-type ::seek)))

(defn- pause!
  [to-consumer-chan from-consumer-chan active-assignments-atom topic part]
  (consumer-roundtrip to-consumer-chan from-consumer-chan {::message-type ::pause
                                                           ::active-assignments-atom active-assignments-atom
                                                           ::topic topic
                                                           ::part part}))

(defn- resume!
  [to-consumer-chan from-consumer-chan active-assignments-atom topic part]
  (consumer-roundtrip to-consumer-chan from-consumer-chan {::message-type ::resume
                                                           ::active-assignments-atom active-assignments-atom
                                                           ::topic topic
                                                           ::part part}))

(defn print-output-line [line]
  (when line
    (-> (if (string? line) line (pr-str line))
        println)))

(defn print-error-line [err]
  (when err
    (print-output-line (str "ERROR: " err))))

(defn print-assignments*
  [current-assignments]
  (->> (map (fn [[^TopicPartition assignment [offset active?]]]
              (format "%s:%d at %d%s"
                      (.topic assignment)
                      (.partition assignment)
                      offset
                      (if-not active? "*" "")))
            current-assignments)
       (str/join ", ")
       print-output-line))

(defn last-read* [last-read-records-atom record-handling-opts type-handlers]
  (map (record-handling-opts>record-handler-fn record-handling-opts type-handlers) @last-read-records-atom))

(defn list-topics*
  "TODO: fill in"
  [to-consumer-chan from-consumer-chan]
  (-> (a/go
        (a/>! to-consumer-chan {::message-type ::list-topics})
        (let [resp (a/<! from-consumer-chan)]
          (log/tracef "Got response from consumer for list-topics: %s" (pr-str resp))
          (::topics resp)))
      (a/<!!)))

(s/def ::key-type string?)
(s/def ::value-type string?)
(s/def ::key-parse-fn fn?)
(s/def ::value-parse-fn fn?)

(s/def ::key->clj? boolean?)
(s/def ::val->clj? boolean?)
(s/def ::map-record-fn fn?)

(s/def ::include-headers? boolean?)
(s/def ::include-topic? boolean?)
(s/def ::include-partition? boolean?)
(s/def ::include-offset? boolean?)
(s/def ::include-timestamp? boolean?)
(s/def ::reducing-fn fn?)

(s/def ::record-handling-opts (s/keys :opt [::key-type ::value-type ::key-parse-fn ::value-parse-fn ::map-record-fn
                                            ::reducing-fn ::key->clj? ::val->clj?]))

(defn record-handling-opts->poll-xf-args
  "For the given record-handling-opts, and type-handlers, produce a poll-xf-args map, which can then be passed into a
  transduction upon a stream of ConsumerRecord instances.

  record-handling-options must either be a string, or a map. If a string (the simplified form), then it is
  assumed to be the type of a ConsumerRecord value, which will be interpreted by building its corresponding
  type-handler and setting that as the value parser (and ignoring the ConsumerRecord key, offset, headers, etc.)

  type-handlers is a map from strings (type names) to instances of the type-handler interface

  If a map is given, then more complete control over the iteration can be achieved. The following keys are supported
    ::key-type - the type of the ConsumerRecord's key (to be parsed); ignored if key-parse-fn is provided
    ::value-type - the type of the ConsumerRecord's value (to be parsed); ignored if value-parse-fn is provided
    ::key-parse-fn - a function that will be invoked (passing the ConsumerRecord's key) to produce a parsed key
    ::value-parse-fn - a function that will be invoked (passing the ConsumerRecord's value) to produce a parsed value
    ::map-record-fn - a function that will be invoked to produce a single return value for each ConsumerRecord, which
                      will be passed the key (if parsed), the value (if parsed), the headers, and offset information.
                      In essence, this produces a `mapped-record` from a `ConsumerRecord`. The default implementation,
                      if none is provided, is simply to return the parsed value."
  [record-handling-opts type-handlers]
  (record-handling-opts>record-handler-fn record-handling-opts type-handlers))

(s/fdef record-handling-opts->poll-xf-args
        :args (s/cat :record-handling-opts (s/or :string? string? :record-handling-opts? ::record-handling-opts))
        :ret ::poll-xf-args)

(defrecord JsonHandler [])

(extend-protocol th/type-handler JsonHandler
  (parse-bytes [_ _ ^bytes b]
    (json/read-str (String. b StandardCharsets/UTF_8) :keywordize? true))
  (->clj [_ obj] ;;TODO: figure out how to do a default impl of this one
    obj)
  (set-config! [& _]
    (throw (UnsupportedOperationException. "json handler has no configs to set"))))


(defmethod th/create-type-handler "json" [& _] (JsonHandler.))

(defrecord TextHandler [])

(extend-protocol th/type-handler TextHandler
  (parse-bytes [_ _ ^bytes b]
    (String. b StandardCharsets/UTF_8))
  (->clj [_ obj]
    obj)
  (set-config! [& _]
    (throw (UnsupportedOperationException. "text handler has no configs to set"))))

(defmethod th/create-type-handler "text" [& _] (TextHandler.))


(defn clj-main-entrypoint
  "Entrypoint for running clj-main
  TODO: can this be the same as uberjar entrypoint?"
  [config-fname]
  nil)

(defprotocol KCRClientInterface
  (initialize! [this])
  (metrics [this])
  (list-topics [this])
  (print-assignments [this])
  (read-from [this topic part offset num-msg record-handling-opts])
  (last-read [this record-handling-opts])
  (assign [this topic part offset])
  (unassign [this topic part])
  (seek [this offset])
  (seek+ [this offset+])
  (seek- [this offset-])
  (seek-while [this while-fn record-handling-opts])
  (seek-until [this until-fn record-handling-opts])
  (pause [this topic part])
  (resume [this topic part])
  (poll [this num-msg record-handling-opts])
  (stop [this])
  (current-assignments [this])
  (set-type-handler-config! [this type-name k args])
  (get-type-handler-by-name [this nm]))

(defrecord KCRClient [^KafkaConsumer consumer to-consumer-chan from-consumer-chan active-assignments-atom
                      last-read-records-atom type-handlers]
  KCRClientInterface
  (initialize! [_]
    (run-consumer-loop! consumer to-consumer-chan from-consumer-chan))
  (metrics [_]
    (.metrics consumer))
  (list-topics [_]
    (list-topics* to-consumer-chan from-consumer-chan))
  (print-assignments [kcrc]
    (print-assignments* (current-assignments kcrc)))
  (read-from [_ topic part offset num-msg record-handling-opts]
    (read-from* to-consumer-chan from-consumer-chan active-assignments-atom last-read-records-atom topic part
                (record-handling-opts->poll-xf-args record-handling-opts type-handlers) offset num-msg))
  (last-read [_ record-handling-opts]
    (last-read* last-read-records-atom record-handling-opts type-handlers))
  (assign [_ topic part offset]
    (assign! to-consumer-chan from-consumer-chan active-assignments-atom topic part offset))
  (unassign [_ topic part]
    (unassign! to-consumer-chan from-consumer-chan active-assignments-atom topic part))
  (seek [_ offset]
    (seek! to-consumer-chan from-consumer-chan {::active-assignments-atom active-assignments-atom
                                                ::to offset}))
  (seek+ [_ offset+]
    (seek! to-consumer-chan from-consumer-chan {::active-assignments-atom active-assignments-atom
                                                ::forward-by offset+}))
  (seek- [_ offset-]
    (seek! to-consumer-chan from-consumer-chan {::active-assignments-atom active-assignments-atom
                                                ::backward-by offset-}))
  (seek-while [_ while-fn record-handling-opts]
    (seek! to-consumer-chan
           from-consumer-chan
           {::active-assignments-atom active-assignments-atom
            ::while-fn                while-fn
            ::poll-xf-args            (record-handling-opts->poll-xf-args record-handling-opts type-handlers)}))
  (seek-until [_ until-fn record-handling-opts]
    (seek! to-consumer-chan
           from-consumer-chan
           {::active-assignments-atom active-assignments-atom
            ::until-fn                until-fn
            ::poll-xf-args            (record-handling-opts->poll-xf-args record-handling-opts type-handlers)}))
  (pause [_ topic part]
    (pause! to-consumer-chan from-consumer-chan active-assignments-atom topic part))
  (resume [_ topic part]
    (resume! to-consumer-chan from-consumer-chan active-assignments-atom topic part))
  (poll [_ num-msg record-handling-opts]
    (poll* to-consumer-chan
           from-consumer-chan
           active-assignments-atom
           last-read-records-atom
           {::num-msg      num-msg
            ::poll-xf-args (record-handling-opts->poll-xf-args record-handling-opts type-handlers)}))
  (current-assignments [_]
    (consumer-assigments consumer active-assignments-atom))
  (stop [_]
    (stop* to-consumer-chan))
  (set-type-handler-config! [_ type-name k args]
    #_(log/infof "type-name %s k %s v %s" type-name k v)
    (if-let [th (get type-handlers type-name)]
      (th/set-config! th k args)
      (throw (IllegalArgumentException. (format "no type handler found for %s" type-name)))))
  (get-type-handler-by-name [_ nm]
    (get type-handlers nm))

  AutoCloseable
  (close [_]
    (log/debugf "Closing channels")
    (a/close! to-consumer-chan)
    (a/close! from-consumer-chan)
    (log/debugf "Closing KafkaConsumer")
    (.close consumer)))

(defn make-kcr-client ^KCRClient [config-fname-or-props]
  (let [props (cond (map? config-fname-or-props)
                    (doto (Properties.)
                      (.putAll config-fname-or-props))

                    (string? config-fname-or-props)
                    (do
                      (log/debugf "Assuming %s is a config file name; initializing consumer from that"
                                  config-fname-or-props)
                      (doto (Properties.)
                        (.load (io/reader config-fname-or-props))))

                    true
                    (throw (ex-info "Parameter must be a map (property key/values) or a String (file path)"
                                    {:arg config-fname-or-props})))
        consumer (KafkaConsumer. props)
        ;; construct an instance of each known type-handler here, which currently relies upon the namespace
        ;; providing it to have been required; may need to change to something more sophisticated later?
        ths (reduce-kv (fn [acc t f]
                         (assoc acc t (f props)))
                       {}
                       (methods th/create-type-handler))]

    (log/infof "starting with type handlers %s" (keys ths))
    (->KCRClient consumer (a/chan 1) (a/chan 1) (atom #{}) (atom {}) ths)))

(def ^:private java-cmds
  "A map from subcommand (i.e. operation) name to another map containing the tools.cli options, the actual fn to
  invoke, and metadata about whether offsets should be printed after serializing/outputting the result"
  {})

(def ^:private clj-help-lines
  "A vector of help lines for Clojure (nREPL) usage"
  [])

(defn- op-arg->metadata [op-arg]
  (let [m        (meta op-arg)
        parse-fn (case (:tag m)
                   int #(Integer/parseInt %)
                   long #(Long/parseLong %)
                   nil)]
    (cond-> {::arg-description (:doc m)
             ::arg-default (:default m)
             ::required? (::required? m)
             ::arg-name (::arg-name m)
             ::varargs? (::varargs? m)}

            (some? parse-fn)
            (assoc ::arg-parse-fn parse-fn))))

(defn- op-args->metadata
  [op-args]
  (mapv op-arg->metadata op-args))

(defn- make-clj-help-lines [op-nm op-desc op-args args-metadata]
  [(str ";; " (name op-nm) (when op-desc (str ": " op-desc)))
   (str "(" (name op-nm) (when-not (empty? op-args) (str " " (str/join " " (map name op-args)))) ")")])

(defmacro defop
  "Defines a kc-repl operation

  TODO: replace with a different macro that wraps defprotocol, so we can avoid duplicating method signatures

  nm - the operation name
  kcr-client - an initialized kcr-client instance, upon which operations will be called
  java? - boolean indicating a Java method hook should be created
  print-offsets? - boolean indicating whether offsets should be printed after the op completes
  op-args - the arguments to the operation, with type hints to perform parsing"
  [nm kcr-client java? print-offsets? & op-args]
  (let [num-args      (count op-args)
        op-desc       (:doc (meta nm))
        args-metadata (op-args->metadata op-args)]
    `(let [the-fn# (fn [& args#]
                     (log/tracef "in the-fn# for %s, got args# %s" ~nm args#)
                     (let [op-fn# (partial ~nm ~kcr-client)]
                       (apply op-fn# args#)))]
          (intern ~(the-ns 'user) (quote ~nm) the-fn#)
          (alter-var-root #'clj-help-lines concat ~(make-clj-help-lines nm op-desc op-args args-metadata))
          (when ~java?
            (log/tracef "Defining %s as a Java command" (str (quote ~nm)))
            (alter-var-root
             #'java-cmds
             assoc
             (str (quote ~nm))
             {::invoke-fn (fn [{options# :options summary# :summary errors# :errors :as cli-opts#}]
                            (log/tracef "Got cli-opts: %s" cli-opts#)
                            (if errors#
                              (do (print-output-line errors#)
                                  (print-output-line summary#))
                              (let [full-args# (if (> ~num-args 0)
                                                 ((juxt ~@(map (fn [arg-nm]
                                                                 (keyword arg-nm)) op-args)) options#)
                                                 [])]
                                (log/tracef "About to invoke the-fn# with full-args# %s" full-args#)
                                (apply the-fn# full-args#))))
              ::description ~op-desc
              ::opts-spec [~@(map-indexed
                               (fn [idx arg]
                                (let [arg-metadata (nth args-metadata idx)
                                      {:keys [::arg-name ::arg-parse-fn ::arg-default ::arg-description ::required? ::varargs?]} arg-metadata
                                      arg-nm (or arg-name (name arg))
                                      long-opt (str "--" arg-nm " " (str/upper-case arg-nm))]
                                  (log/tracef "arg-metadata for %s: %s" arg-nm (pr-str arg-metadata))
                                  (-> (cond-> [(when required? (str "-" (first arg-nm))) long-opt arg-description
                                               :parse-fn arg-parse-fn]
                                              (some? arg-default)
                                              (concat [:default arg-default])

                                              required?
                                              (concat [:missing (format "%s must be provided" arg-nm)])

                                              varargs?
                                              (concat [:multi true, :default [], :update-fn conj]))
                                      vec))) ;; TODO: fix ugliness
                               op-args)]
              ::print-offsets? ~print-offsets?})))))

(def ^:dynamic ^KCRClient *client* "The kcr-client bound within the entrypoint" nil)

(defn- print-clj-help []
  (println (str/join "\n" clj-help-lines)))

(defn maybe-string->clj [str-value]
 (try
   (edn/read-string str-value)
   (catch Exception e
     (log/infof e "Error parsing string %s with EDN reader" str-value)
     nil)))

(defn- parse-config-opt-val
  "Attempts to parse the given `v` as a Clojure data structure via the EDN reader, if it is a String.  If that fails, or
  if `v` is not a String, returns `v` unmodified"
  [v]
  (if (string? v)
    (if-let [clj-val (maybe-string->clj v)]
      clj-val
      v)
    v))

(defn exec-entrypoint
  "Entrypoint for running in an existing REPL"
  [config-fname run-body]
  (let [kcr-client (make-kcr-client config-fname)]
    (initialize! kcr-client)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (.close kcr-client)
                                           (shutdown-agents))))
    (binding [*client* kcr-client]
      (defop ^{:doc "List the topics in the cluster"} list-topics kcr-client true false)
      ;; Java already prints out the assignments after various ops
      (defop ^{:doc "Print the current consumer assignments"} print-assignments kcr-client false false)
      (defop ^{:doc "Read from the given topic/partition/offset"} read-from kcr-client true true
             ^{:doc "The topic name to read from", ::required? true} topic
             ^long ^{:doc "The topic partition to read from", :default 0} part
             ^long ^{:doc "The offset to start reading from", :default 0} offset
             ^long ^{:doc "The number of messages to read", :default 10} num-msg
             ;; TODO: figure out how to get by with something like msg-format instead of record-handling-opts in Java
             ^{:doc "Record handling options (see documentation)", ::required? true} record-handling-opts)
      (defop ^{:doc "Read from the current active assignments"} poll kcr-client true true
             ^long ^{:doc "The number of messages to read", :default 10} num-msg
             ^{:doc "Record handling options (see documentation)", ::required? true} record-handling-opts)
      (defop ^{:doc "Add a topic/partition to the active assignments"} assign kcr-client true true
             ^{:doc "The topic name to read from", ::required? true} topic
             ^long ^{:doc "The topic partition to read from", ::required? true} part
             ^long ^{:doc "The offset to start reading from", ::required? true} offset)
      (defop ^{:doc "Remove a topic/partition from the active assignments"} unassign kcr-client true true
             ^{:doc "The topic name to read from", ::required? true} topic
             ^long ^{:doc "The topic partition to read from", ::required? true} part)
      (defop ^{:doc "Change the offset to poll from next for the given topic/partition"} seek kcr-client true true
             ^long ^{:doc "The offset to seek to", ::required? true} offset)
      (defop ^{:doc "Increment the offset for the current assignment by the given amount"} seek+ kcr-client true true
             ^long ^{:doc "The number of offsets to advance by", ::required? true} by)
      (defop ^{:doc "Decrement the offset for the current assignment by the given amount"} seek- kcr-client true true
             ^long ^{:doc "The number of offsets to recede by", ::required? true} by)
      (defop ^{:doc "Seek forward in the message stream while some condition is met"} seek-while kcr-client false false
             ^{:doc "Conditional (while) function; operates on the mapped record"} while-fn
             ^{:doc "Record handling options (see documentation)", ::required? true} record-handling-opts)
      (defop ^{:doc "Seek forward in the message stream while some condition is met"} seek-until kcr-client false false
             ^{:doc "Conditional (until) function; operates on the mapped record"} while-fn
             ^{:doc "Record handling options (see documentation)", ::required? true} record-handling-opts)
      ;; will revisit this once we have a better Java args parser
      (defop ^{:doc "Set a config option for a type handler arr", ::print-offsets? false} set-type-handler-config! kcr-client true true
             ^{:doc "The type on which to set the config option", ::required? true} type-name
             ^{:doc "The config option's key", ::required? true} k
             ^{:doc "The config option's arguments", ::required? true, ::varargs? true} args)
      (defop ^{:doc "Print the last read results"} last-read kcr-client true true
             ^{:doc "Record handling options (see documentation)", ::required? true} record-handling-opts)
      (defop ^{:doc "Stop the client and disconnect the session"} stop kcr-client true false)
      (intern (the-ns 'user) 'help print-clj-help)
      (when (ifn? run-body)
        (run-body kcr-client)))))

(defmacro entrypoint [config-fname & run-body]
  `(exec-entrypoint ~config-fname ~@run-body))

(defn -main [config-file & more]
  (thl/load-type-handlers)
  (entrypoint config-file (fn [kcr-client]
                            (log/infof "Welcome to kc-repl!" (pr-str (metrics kcr-client)))
                            (n.c/-main "-i"))))


