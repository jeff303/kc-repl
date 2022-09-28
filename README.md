# kc-repl

`kc-repl` (Apache **K**afka **C**onsumer [REPL](https://en.wikipedia.org/wiki/Read%E2%80%93eval%E2%80%93print_loop)) is
an interactive tool for exploring Apache Kafka clusters.  It features:
* The ability to arbitrarily subscribe to any topics/partitions in the cluster (and remove those assignments)
* The ability to jump around to different offsets at will (either in relative terms, absolute terms, or by consuming
    the data and evaluating a predicate)
* The ability to provide arbitrary map and reduce functions over the records over any polling based operation

## Installation

Download from https://github.com/jeff303/kc-repl

## Usage

### Clojure REPL
This is the most fully-featured and powerful way to run `kc-repl`.  You will need install the latest (1.11+) version of
[Clojure](https://clojure.org/releases/downloads).  Then, clone this repo and run:

`clojure -M:run /path/to/your/kafka.consumer.properties`

After it starts, you should see output like the following

    2022-09-27 16:22:43 INFO  AppInfoParser:119 - Kafka version: 3.1.0
    2022-09-27 16:22:43 INFO  AppInfoParser:120 - Kafka commitId: 37edeed0777bacb3
    2022-09-27 16:22:43 INFO  AppInfoParser:121 - Kafka startTimeMs: 1664313763331
    2022-09-27 16:22:43 INFO  kc-repl:326 - Welcome to kc-repl!
    nREPL server started on port 61670 on host localhost - nrepl://localhost:61670
    nREPL 1.0.0
    Clojure 1.11.1
    OpenJDK 64-Bit Server VM 18.0.1.1+0
    Interrupt: Control+C
    Exit:      Control+D or (exit) or (quit)
    user=>

At this point, you can begin running functions to explore the data.  Simply run `(help)` to see the full list of
operations supported.

#### Usage Examples

Suppose you have a Kafka topic called `test-data` with a single partition, and messages in JSON (string) format like
```json lines
{"foo": 1}
{"foo": 1}
...
{"foo": 100}
```

The following REPL session shows how you can explore the data in this topic.

```clojure
;; see what topics we have
user=> (list-topics)
["test-data"]
;; read 5 messages from the test-data topic, partition 0, offset 0, in json format
user=> (read-from "test-data" 0 0 5 "json")
[{"foo" 1} {"foo" 2} {"foo" 3} {"foo" 4} {"foo" 5}]
;; skip forward by 20
user=> (seek+ 20)
:us.jeffevans.kc-repl/ok
;; poll two more messages, in json format
user=> (poll 2 "json")
[{"foo" 26} {"foo" 27}]
;; check where we are now
user=> (print-assignments)
test-data:0 at 27
nil
```

##### Advanced Record Handling

In the examples above, a single message handling option was given, and it was a string (`json`).  In this case,
the string is interpreted as the type of message value (in this case, a JSON payload encoded as a UTF-8 string).

`kc-repl` supports passing a map as this parameter value instead, which provides much more complete control over
record handling.

```clojure

{::kcr/key-type         "json"       ; the format the key should be parsed in (optional)
 ::kcr/key-parse-fn     (fn [b] nil) ; a custom function to parse the key (passed as a byte array)
                                     ; if both are omitted, the key won't be parsed
 ::kcr/value-type       "json"       ; the format the value should be parsed in (optional)
 ::kcr/value-parse-fn   (fn [b] nil) ; a custom function to parse the value (passed as a byte array)
                                     ; if both are omitted, the value won't be parsed
 ::kcr/include-headers?   true       ; whether to include message headers in extra-metadata
 ::kcr/include-offset?    true       ; whether to include the message offset in extra-metadata
 ::kcr/include-partition? true       ; whether to include the PartitionInfo in extra-metadata
 ::kcr/include-timestamp? true       ; whether to include the timestamp in extra-metadata
 ::kcr/include-topic?     true       ; whether to include the topic name in extra-metadata
 ;; reducing-fn is called at the end of the transducer, after the polling operation is complete
 ;; used to aggregate results
 ::kcr/reducing-fn      (fn ([]  ; init (0) arity
                             {}) ; initialize the blank accumulator
                          ([acc] ; completion (1) arity
                           nil)  ; return the final result (probably from accumulator)
                          ([acc mapped-record] ; step (2) arity
                           nil))               ; apply the mapped-record to the accumulator
 ;; map-record-fn is called once for each ConsumerRecord
 ;; it will be passed two parameters:
 ;;  - the ConsumerRecord (passed directly from the underlying KafkaConsumer), and
 ;;  - a map containing any extra metadata that might be available...
 ;;    - the parsed message key, under ::kcr/parsed-key (if available)
 ;;    - the parsed message value, under ::kcr/parsed-value (if available)
 ;;    - the offset, PartitionInfo, timestamp, header, or topic if requested (see above)
 ;; the output of this function is referred to as the mapped-record, and is passed along to the
 ;; rest of the processing pipeline (the predicate functions for seek-while/seek-until, the reducing-fn)
 ::kcr/map-record-fn    (fn [^ConsumerRecord cr extra-metadata]
                          {::order parsed-value, ::headers headers, ::offset offset})}
```

Here is an example `kc-repl` session showing some of these capabilities.  Suppose we have random "Orders" data
generated by the [Confluent Datagen Source Connector](https://www.confluent.io/hub/confluentinc/kafka-connect-datagen).
For reference, here are a couple of sample records that it generates (in JSON format, for our case).

```json
{"ordertime": 1495239240320,
  "orderid": 0,
  "itemid": "Item_546",
  "orderunits": 2.779222985601741,
  "address": {"city": "City_72", "state": "State_16", "zipcode": 58684}}
```

Suppose we want to find which zipcodes had at least two orders within the first 1000 orders.

```clojure
;; define a null-safe inc function
(defn- safe-inc [x]
  (if (nil? x) 1 (inc x)))
;; add the orders-json topic assignment, partition 0, offset 0
(assign "orders-json" 0 0)
;; run the polling operation, using the map-record-fn and reducing-fn
(poll
  1000
  {::kcr/value-type    "json"
   ::kcr/reducing-fn   (fn ([] ; init (0) arity
                               {})
                            ([acc] ; completion (1) arity
                             (reduce-kv (fn [acc* zip tot]
                                          (if (> tot 1)
                                            (assoc acc* zip tot)
                                            acc*))
                                        {}
                                        acc))
                            ([acc {:keys [::order]}] ; step (2) arity
                             (update acc (get-in order ["address" "zipcode"]) safe-inc)))
   ::kcr/map-record-fn (fn [_ {:keys [::kcr/parsed-value ::kcr/headers ::kcr/offset]}]
                            {::order parsed-value, ::headers headers, ::offset offset})})

=> {99859 2, 40612 2, 77762 2}
```

A similar approach can be taken with `seek-while` and `seek-until` (ex: if the exact number of records isn't known
ahead of time).  Any elements from the `ConsumerRecord` that aren't needed for the rest of the processing or filtering
can simply be ignored/not passed along from the `map-record-fn`

### Java (uberjar)

There is an uberjar build of `kc-repl` that is capable of running basic operations using `java`.  To use this mode,
download (or build) the uberjar, then run:

`java -Dclojure.core.async.pool-size=1 -jar kc-repl-*.jar /path/to/consumer.properties`

The syntax for the Java runtime is more similar to the original Kafka command line tools.  You can type `help` for a
more complete list.

### Library

`kc-repl` can also be used as a library from other Clojure/Java applications.  For examples of how this can work, refer
to the tests in `us.jeffevans.kc-repl-test`. 

## Development

### Implementation

An instance of the `KCRClient` record represents the main client object.  It encapsulates a
[`KafkaConsumer`](https://kafka.apache.org/32/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html) to read
records from the configured cluster.  The code attempts to ensure this `KafkaConsumer` is well-behaved (i.e. running
a `poll` at a regular cadence regardless of how quickly the user is typing commands).

The core operations that involve iterating over or polling records do so via a
[transducer](https://clojure.org/reference/transducers) over an "infinite sequence" of records
from the stream.  The final composed function of the transducer is a special one that updates the client's
offsets to match what was just consumed (for cases where we don't know until reading a record that we are "done"
advancing, such as `seek-while` and `seek-until`).

#### Extension

The current key and message types that can be parsed are limited (only text and JSON).  There is a multimethod,
`parse-type`, that provides an extension point through which parsing of new types can be supported.  In the future,
support for some of these will be added to this project (as submodules that can be built separately and loaded as
needed).  In theory, any Clojure code that defines a new method for this multimethod can take advantage of this
mechanism to read additional types (provided the bytecode is available on the classpath).

### Building the Uberjar
`clojure -J-Dclojure.core.async.pool-size=1 -T:build ci`

### Testing
`clojure -J-Dclojure.core.async.pool-size=1 -X:test`

### Roadmap and Bugs

Bugs and enhancement requests will be tracked in the GitHub project (via issues).

## License

Copyright © 2022 Jeff Evans

Distributed under the Eclipse Public License version 1.0.
